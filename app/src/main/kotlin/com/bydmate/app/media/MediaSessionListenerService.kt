package com.bydmate.app.media

import android.app.Notification
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

        /** Framework binding state. Secure-settings membership alone is not enough on DiLink:
         * the vendor notification manager can retain the grant without reconnecting the service. */
        @Volatile
        var isConnected: Boolean = false
            private set

        // Exact Waze 5.21 channel used by its Android Auto/Automotive navigation path.
        // Standalone Waze normally exposes only CLOSE_WAZE_CHANNEL, which is intentionally ignored.
        internal const val WAZE_NAVIGATION_CHANNEL = "Waze Navigation Instructions"

        internal fun shouldAcceptNavigationNotification(
            category: String?,
            channelId: String?,
        ): Boolean =
            category == Notification.CATEGORY_NAVIGATION ||
                channelId == WAZE_NAVIGATION_CHANNEL
    }

    /** Plain parsed mirrors, not framework Notification objects. Waze can briefly keep an old and
     * a replacement navigation notification at the same time; retaining each key lets removal of
     * the newest one restore the still-active predecessor instead of exposing stale latest data. */
    private val navigationNotifications =
        ConcurrentHashMap<String, NavigationNotificationMirror>()
    private val notificationSequence = AtomicLong()

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
            val removed = navigationNotifications.remove(sbn.key) ?: return
            val remaining = newestNavigationNotification(navigationNotifications.values)
            if (remaining == null) {
                NaviRouteHolder.clear(sbn.packageName)
                // Unless the a11y feed still delivers fresh guidance, the last accepted Waze
                // navigation notification disappearing means route guidance ended.
                com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
            } else if (remaining.observedSequence < removed.observedSequence) {
                // The removed notification owned the visible latest instruction. Restore the
                // newest still-active mirror; removing an older key leaves current state intact.
                // Mark the removed source first: a guidance-bearing predecessor cancels this
                // marker in updateFromNotification(), while a non-guidance predecessor cannot
                // accidentally keep the removed maneuver alive.
                com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
                applyMirror(remaining, System.currentTimeMillis())
            }
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
        super.onDestroy()
    }

    private fun processPosted(sbn: StatusBarNotification) {
        if (!isAcceptedNavigationNotification(sbn)) return
        val extras = sbn.notification.extras
        val parsed = runCatching { NaviNotificationParser.parse(sbn.notification) }
            .onFailure { Log.w(TAG, "Waze notification parse failed", it) }
            .getOrNull()
        if (parsed?.hasGuidance != true) {
            Log.d("WazeNotifParser", NaviNotificationParser.dump(sbn.notification))
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

    private fun isAcceptedNavigationNotification(sbn: StatusBarNotification): Boolean {
        if (!NavPackages.isNavigationPackage(sbn.packageName)) return false
        // Unknown Waze channels include police/hazard/community/engagement alerts. Do not infer
        // their purpose from wording: only Android's navigation category or Waze's confirmed
        // navigation channel may feed the route/HUD fallback.
        return shouldAcceptNavigationNotification(
            sbn.notification.category,
            sbn.notification.channelId,
        )
    }
}
