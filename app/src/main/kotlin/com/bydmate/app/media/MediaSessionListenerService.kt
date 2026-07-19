package com.bydmate.app.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bydmate.app.navdata.NavPackages
import java.util.concurrent.ConcurrentHashMap

/** Listener with two narrow jobs: (1) its mere enabled existence lets
 *  MediaSessionManager.getActiveSessions() accept our component (Wave G, music playback);
 *  (2) it passively mirrors Waze navigation notifications into NaviRouteHolder
 *  for the get_route_info voice tool. No other package's notifications are read or stored.
 *  Listener access is self-granted through the helper daemon (see MediaSessionGrant). */
class MediaSessionListenerService : NotificationListenerService() {

    companion object {
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

    /** Waze also posts "Waze is running" and hazard notifications. Only keys accepted as
     *  navigation guidance may end a route when removed. */
    private val navigationNotificationKeys = ConcurrentHashMap.newKeySet<String>()

    // Both callbacks run on the framework binder thread: an uncaught exception there kills
    // the process AND unbinds this listener, breaking music-session access (Wave G role)
    // along with the navi mirror. Navigator's notification shape is not contractual, so
    // parsing failures must degrade to "no route info", never to a crash.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            if (!NavPackages.isNavigationPackage(sbn.packageName)) return
            // Unknown Waze channels include police/hazard/community/engagement alerts. Do not
            // infer their purpose from wording: only Android's navigation category or Waze's
            // confirmed navigation channel may feed the route/HUD fallback.
            if (!shouldAcceptNavigationNotification(
                    sbn.notification.category,
                    sbn.notification.channelId,
                )) return
            val extras = sbn.notification.extras
            val parsed = runCatching { NaviNotificationParser.parse(sbn.notification) }.getOrNull()
            navigationNotificationKeys.add(sbn.key)
            if (parsed?.hasGuidance != true) {
                Log.d("WazeNotifParser", NaviNotificationParser.dump(sbn.notification))
            }
            NaviRouteHolder.update(
                sbn.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                System.currentTimeMillis(),
                parsed,
            )
            // Same parse also feeds the unified guidance hub (numerics for HUD + agent).
            parsed?.guidance?.let {
                com.bydmate.app.navdata.NavGuidanceHub.updateFromNotification(
                    it,
                    System.currentTimeMillis(),
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching {
            if (!navigationNotificationKeys.remove(sbn.key)) return
            if (navigationNotificationKeys.isNotEmpty()) return
            NaviRouteHolder.clear(sbn.packageName)
            // Unless the a11y feed still delivers fresh guidance, the last accepted Waze
            // navigation notification disappearing means route guidance ended.
            com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
        }
    }
}
