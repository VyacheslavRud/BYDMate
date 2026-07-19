package com.bydmate.app.media

import com.bydmate.app.navigation.WazeNavigation

/** Latest Waze navigation-notification content, captured passively by
 *  MediaSessionListenerService. The voice agent reads it via the get_route_info tool.
 *  Content is whatever the Navigator renders (remaining distance, next maneuver);
 *  ETA and remaining distance are also available in bigTexts when the parser runs. */
object NaviRouteHolder {
    data class RouteSnapshot(
        val title: String?,
        val text: String?,
        val subText: String?,
        val postedAtMs: Long,
        val maneuver: String? = null,
        val maneuverDistance: String? = null,
        val street: String? = null,
        val remainingDistance: String? = null,
        val remainingTime: String? = null,
        val bigTexts: List<String> = emptyList(),
    )

    const val NAVI_PACKAGE = WazeNavigation.PACKAGE_NAME

    @Volatile var latest: RouteSnapshot? = null
        private set

    @Synchronized
    fun update(
        pkg: String,
        title: String?,
        text: String?,
        subText: String?,
        nowMs: Long,
        parsed: NaviNotificationParser.Parsed? = null,
    ) {
        if (!com.bydmate.app.navdata.NavPackages.isNavigationPackage(pkg)) return
        val hasParsed = parsed != null &&
            (parsed.maneuver != null || parsed.distance != null || parsed.bigTexts.isNotEmpty())
        if (title.isNullOrBlank() && text.isNullOrBlank() && subText.isNullOrBlank() && !hasParsed) return
        latest = RouteSnapshot(
            title, text, subText, nowMs,
            maneuver = parsed?.maneuver,
            maneuverDistance = parsed?.distance,
            street = parsed?.street,
            remainingDistance = parsed?.remainingDistance,
            remainingTime = parsed?.remainingTime,
            bigTexts = parsed?.bigTexts ?: emptyList(),
        )
    }

    /** A removed notification is normally cleared, but this TTL also protects process restarts
     *  and missed listener callbacks from exposing an old route indefinitely. */
    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): RouteSnapshot? {
        val value = latest ?: return null
        if (nowMs - value.postedAtMs <= ROUTE_TIMEOUT_MS) return value
        latest = null
        return null
    }

    @Synchronized
    fun clear(pkg: String) {
        if (com.bydmate.app.navdata.NavPackages.isNavigationPackage(pkg)) latest = null
    }

    const val ROUTE_TIMEOUT_MS = com.bydmate.app.navdata.NavGuidanceHub.ACTIVE_TIMEOUT_MS
}
