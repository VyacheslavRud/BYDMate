package com.bydmate.app.media

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bydmate.app.navdata.NavPackages
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class NavigationNotificationMirror(
    val packageName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val parsed: NaviNotificationParser.Parsed?,
    val postTimeMs: Long,
    val observedSequence: Long,
)

internal fun newestNavigationNotification(
    notifications: Collection<NavigationNotificationMirror>,
): NavigationNotificationMirror? = notifications.maxWithOrNull(
    compareBy<NavigationNotificationMirror> { it.observedSequence }
        .thenBy { it.postTimeMs },
)

/** Listener with two narrow jobs: (1) its mere enabled existence lets
 *  MediaSessionManager.getActiveSessions() accept our component (Wave G, music playback);
 *  (2) it passively mirrors Waze navigation notifications into NaviRouteHolder
 *  for the get_route_info voice tool. No other package's notifications are read or stored.
 *  Listener access is self-granted through the helper daemon (see MediaSessionGrant). */
class MediaSessionListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WazeNotifListener"
        private const val HUD_OVERLAY_RECOVERY_DELAY_MS = 1_500L

        /** Framework binding state. Secure-settings membership alone is not enough on DiLink:
         * the vendor notification manager can retain the grant without reconnecting the service. */
        @Volatile
        var isConnected: Boolean = false
            private set

        // Exact Waze 5.21 channel used by its Android Auto/Automotive navigation path. DiLink
        // standalone builds can use a vendor channel; those are accepted only with strong parsed
        // route evidence in shouldAcceptNavigationNotification().
        internal const val WAZE_NAVIGATION_CHANNEL = "Waze Navigation Instructions"

        internal fun shouldAcceptNavigationNotification(
            category: String?,
            channelId: String?,
            parsedHasGuidance: Boolean = false,
            hasStrongRouteEvidence: Boolean = false,
        ): Boolean =
            category == Notification.CATEGORY_NAVIGATION ||
                channelId == WAZE_NAVIGATION_CHANNEL ||
                (parsedHasGuidance && hasStrongRouteEvidence)

        internal fun hasStrongRouteEvidence(parsed: NaviNotificationParser.Parsed?): Boolean {
            val guidance = parsed?.guidance ?: return false
            return guidance.maneuverGaode > 0 && (
                guidance.distanceMeters > 0 ||
                    guidance.etaSeconds > 0 ||
                    guidance.arrivalTime.isNotBlank() ||
                    guidance.totalDistMeters > 0
                )
        }
    }

    /** Plain parsed mirrors, not framework Notification objects. Waze can briefly keep an old and
     * a replacement navigation notification at the same time; retaining each key lets removal of
     * the newest one restore the still-active predecessor instead of exposing stale latest data. */
    private val navigationNotifications =
        ConcurrentHashMap<String, NavigationNotificationMirror>()
    private val notificationSequence = AtomicLong()
    private val navigationResources = ConcurrentHashMap<String, android.content.res.Resources>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hudOverlayRecoveryRunnable = Runnable {
        val hub = com.bydmate.app.navdata.NavGuidanceHub
        if (hub.snapshot().active) {
            hub.requestHudRefresh()
            Log.i(TAG, "Waze navigation overlay settled; requested HUD clear/redraw")
        }
    }

    // Both callbacks run on the framework binder thread: an uncaught exception there kills
    // the process AND unbinds this listener, breaking music-session access (Wave G role)
    // along with the navi mirror. Navigator's notification shape is not contractual, so
    // parsing failures must degrade to "no route info", never to a crash.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { processPosted(sbn) }
            .onFailure { Log.w(TAG, "notification post handling failed", it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching {
            removeNavigationMirror(sbn.key, sbn.packageName)
        }.onFailure { Log.w(TAG, "notification removal handling failed", it) }
    }

    /**
     * NotificationListenerService may reconnect after Waze has already posted the active route.
     * Android does not replay those old posts, so without this bootstrap the notification fallback
     * stays blind until Waze happens to replace the instruction. Rebuild the accepted-key set and
     * process the current notifications oldest-to-newest so the latest instruction wins.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        runCatching {
            val active = activeNotifications.orEmpty()
                .filter(::isAcceptedNavigationNotification)
                .sortedBy(StatusBarNotification::getPostTime)
            navigationNotifications.clear()
            // Reconcile any source retained across a listener disconnect. A replayed
            // guidance-bearing notification immediately cancels the end marker.
            com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
            if (active.isEmpty()) {
                // The platform's active list is authoritative for the notification mirror. Do not
                // clear A11Y: markNotificationEnded removes only the notification source and keeps
                // recent accessibility guidance alive.
                NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
            } else {
                active.forEach(::processPosted)
            }
            Log.i(TAG, "listener connected; restored ${active.size} active navigation notification(s)")
        }.onFailure {
            // A transient SecurityException or vendor listener race is recoverable: normal post
            // callbacks continue to work, and the accessibility feed remains the primary source.
            Log.w(TAG, "active notification bootstrap failed", it)
        }
    }

    override fun onListenerDisconnected() {
        isConnected = false
        // A future connection rebuilds this map from activeNotifications. Do not clear route data
        // or declare route end: disconnect says nothing about Waze's actual navigation state.
        navigationNotifications.clear()
        Log.w(TAG, "listener disconnected; preserving last route until a source confirms its state")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        isConnected = false
        mainHandler.removeCallbacks(hudOverlayRecoveryRunnable)
        super.onDestroy()
    }

    private fun processPosted(sbn: StatusBarNotification) {
        if (!NavPackages.isNavigationPackage(sbn.packageName)) return
        val extras = sbn.notification.extras
        val resolveName = navigationResourceResolver(sbn.packageName)
        val parsed = runCatching { NaviNotificationParser.parse(sbn.notification, resolveName) }
            .onFailure { Log.w(TAG, "Waze notification parse failed", it) }
            .getOrNull()
        if (!shouldAcceptNavigationNotification(
                category = sbn.notification.category,
                channelId = sbn.notification.channelId,
                parsedHasGuidance = parsed?.hasGuidance == true,
                hasStrongRouteEvidence = hasStrongRouteEvidence(parsed),
            )
        ) {
            // DiLink's progress phase can update an already accepted vendor key with distance
            // only. Preserve the last strong instruction and use this weak update solely as an
            // overlay-recovery signal; deleting it here made the HUD disappear at the 500 m card.
            // A real removal, an explicit no-route A11Y tree or the route lease still clears it.
            if (navigationNotifications.containsKey(sbn.key)) {
                scheduleHudRefreshAfterOverlay()
            }
            Log.d(
                "WazeNotifParser",
                NaviNotificationParser.dump(sbn.notification, resolveName),
            )
            return
        }
        val now = System.currentTimeMillis()
        val mirror = NavigationNotificationMirror(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            parsed = parsed,
            postTimeMs = sbn.postTime,
            observedSequence = notificationSequence.incrementAndGet(),
        )
        navigationNotifications[sbn.key] = mirror
        applyMirror(mirror, now)
        scheduleHudRefreshAfterOverlay()
    }

    /**
     * A DiLink navigation notification can temporarily replace the factory windshield card. Its
     * progress updates may reuse the same key, so this is a trailing-edge debounce: one clear and
     * redraw runs only after the burst settles instead of flickering on every progress update.
     */
    private fun scheduleHudRefreshAfterOverlay() {
        mainHandler.removeCallbacks(hudOverlayRecoveryRunnable)
        mainHandler.postDelayed(hudOverlayRecoveryRunnable, HUD_OVERLAY_RECOVERY_DELAY_MS)
    }

    private fun applyMirror(mirror: NavigationNotificationMirror, now: Long) {
        NaviRouteHolder.update(
            mirror.packageName,
            mirror.title,
            mirror.text,
            mirror.subText,
            now,
            mirror.parsed,
        )
        // Same parse also feeds the unified guidance hub (numerics for HUD + agent).
        mirror.parsed?.guidance?.let {
            com.bydmate.app.navdata.NavGuidanceHub.updateFromNotification(it, now)
        }
    }

    private fun removeNavigationMirror(key: String, packageName: String) {
        val removed = navigationNotifications.remove(key) ?: return
        val remaining = newestNavigationNotification(navigationNotifications.values)
        if (remaining == null) {
            NaviRouteHolder.clear(packageName)
            // Unless the a11y feed still delivers fresh guidance, the last accepted Waze
            // navigation notification disappearing means route guidance ended.
            com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
        } else if (remaining.observedSequence < removed.observedSequence) {
            // The removed notification owned the visible latest instruction. Restore the newest
            // still-active mirror; removing an older key leaves current state intact.
            com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
            applyMirror(remaining, System.currentTimeMillis())
        }
    }

    private fun isAcceptedNavigationNotification(sbn: StatusBarNotification): Boolean {
        if (!NavPackages.isNavigationPackage(sbn.packageName)) return false
        val parsed = runCatching {
            NaviNotificationParser.parse(
                sbn.notification,
                navigationResourceResolver(sbn.packageName),
            )
        }.getOrNull()
        // The standalone Waze build on DiLink posts real turn instructions on a vendor-specific
        // channel. Unknown channels are accepted only with a recognized maneuver plus an
        // independent route metric; engagement and community alerts remain rejected.
        return shouldAcceptNavigationNotification(
            sbn.notification.category,
            sbn.notification.channelId,
            parsedHasGuidance = parsed?.hasGuidance == true,
            hasStrongRouteEvidence = hasStrongRouteEvidence(parsed),
        )
    }

    /** Resource ids in RemoteViews belong to Waze, not BYDMate. */
    private fun navigationResourceResolver(packageName: String): (Int) -> String? = { id ->
        if (id == 0) {
            null
        } else {
            runCatching {
                navigationResources.getOrPut(packageName) {
                    packageManager.getResourcesForApplication(packageName)
                }.getResourceEntryName(id)
            }.getOrNull()
        }
    }
}
