package com.bydmate.app.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bydmate.app.navdata.NavPackages
import com.bydmate.app.navdata.WazeVisualManeuverReader
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
        private const val MIN_ICON_SIDE_PX = 24
        private const val DEFAULT_ICON_SIDE_PX = 128
        private const val MAX_ICON_SIDE_PX = 256

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
    private val navigationContexts = ConcurrentHashMap<String, Context>()
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
            // Census must observe every active Waze notification, not only the accepted subset:
            // filtering before processPosted left posted=0 covering both "Waze posts nothing" and
            // "Waze posts something we reject", which is the ambiguity the census exists to break.
            // processPosted records the census first and mirrors only accepted notifications.
            val navigation = activeNotifications.orEmpty()
                .filter { NavPackages.isNavigationPackage(it.packageName) }
                .sortedBy(StatusBarNotification::getPostTime)
            val accepted = navigation.filter(::isAcceptedNavigationNotification)
            navigationNotifications.clear()
            // Reconcile any source retained across a listener disconnect. A replayed
            // guidance-bearing notification immediately cancels the end marker.
            com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
            if (accepted.isEmpty()) {
                // The platform's active list is authoritative for the notification mirror. Do not
                // clear A11Y: markNotificationEnded removes only the notification source and keeps
                // recent accessibility guidance alive.
                NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
            }
            navigation.forEach(::processPosted)
            Log.i(
                TAG,
                "listener connected; restored ${accepted.size} of ${navigation.size} " +
                    "active navigation notification(s)",
            )
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
        val classifyImage = navigationImageClassifier(sbn.packageName)
        val parsed = runCatching {
            NaviNotificationParser.parse(sbn.notification, resolveName, classifyImage)
        }
            .onFailure { Log.w(TAG, "Waze notification parse failed", it) }
            .getOrNull()
        val accepted = shouldAcceptNavigationNotification(
            category = sbn.notification.category,
            channelId = sbn.notification.channelId,
            parsedHasGuidance = parsed?.hasGuidance == true,
            hasStrongRouteEvidence = hasStrongRouteEvidence(parsed),
        )
        // Shape-only census: without it a diagnostic export cannot distinguish "Waze posts no
        // navigation notification" from "it posts one we reject" or "it uses a standard template
        // whose RemoteViews are null". Counters and type names only, never notification content.
        runCatching { WazeNotificationCensus.record(sbn.notification, accepted, resolveName) }
        if (!accepted) {
            // DiLink's progress phase can update an already accepted vendor key with distance
            // only. Preserve the last strong instruction and use this weak update solely as an
            // overlay-recovery signal; deleting it here made the HUD disappear at the 500 m card.
            // A real removal, an explicit no-route A11Y tree or the route lease still clears it.
            if (navigationNotifications.containsKey(sbn.key)) {
                scheduleHudRefreshAfterOverlay()
            }
            Log.d(
                "WazeNotifParser",
                NaviNotificationParser.dump(sbn.notification, resolveName, classifyImage),
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
                navigationImageClassifier(sbn.packageName),
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

    /**
     * Waze 4.105 on DiLink keeps the maneuver arrow in a bitmap/Icon action while its activity
     * window is hidden from accessibility. Decode only that bounded image in memory, classify its
     * direction, then release any temporary bitmap immediately. URI icons are deliberately ignored.
     */
    private fun navigationImageClassifier(
        packageName: String,
    ): (Any, Int?) -> WazeVisualManeuverReader.Classification? = { value, resourceId ->
        runCatching { classifyNavigationImage(packageName, value, resourceId) }
            .onFailure {
                Log.d(TAG, "Waze notification image classification failed: ${it.javaClass.simpleName}")
            }
            .getOrNull()
    }

    private fun classifyNavigationImage(
        packageName: String,
        value: Any,
        resourceId: Int?,
    ): WazeVisualManeuverReader.Classification? {
        var temporary: Bitmap? = null
        var bitmap = when (value) {
            is Bitmap -> value
            is Int -> drawableForResource(packageName, resourceId ?: value)?.let { drawable ->
                drawableBitmap(drawable).also { if (drawable !is BitmapDrawable) temporary = it }
            }
            is Icon -> {
                if (value.type == Icon.TYPE_URI ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                        value.type == Icon.TYPE_URI_ADAPTIVE_BITMAP)
                ) {
                    null
                } else {
                    val packageContext = navigationPackageContext(packageName) ?: return null
                    value.loadDrawable(packageContext)?.let { drawable ->
                        drawableBitmap(drawable).also {
                            if (drawable !is BitmapDrawable) temporary = it
                        }
                    }
                }
            }
            else -> null
        } ?: return null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        ) {
            val software = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            temporary?.takeUnless(Bitmap::isRecycled)?.recycle()
            temporary = software
            bitmap = software
        }
        return try {
            WazeVisualManeuverReader.classifyBitmap(bitmap)
        } finally {
            temporary?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun drawableForResource(packageName: String, resourceId: Int): Drawable? {
        if (resourceId == 0) return null
        return runCatching {
            navigationResources.getOrPut(packageName) {
                packageManager.getResourcesForApplication(packageName)
            }.getDrawable(resourceId, null)
        }.getOrNull()
    }

    private fun navigationPackageContext(packageName: String): Context? = runCatching {
        navigationContexts.getOrPut(packageName) {
            createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        }
    }.getOrNull()

    private fun drawableBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) return drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_SIDE_PX)
            ?: DEFAULT_ICON_SIDE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ICON_SIDE_PX)
            ?: DEFAULT_ICON_SIDE_PX
        if (width < MIN_ICON_SIDE_PX || height < MIN_ICON_SIDE_PX) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
