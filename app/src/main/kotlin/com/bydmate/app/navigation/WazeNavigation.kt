package com.bydmate.app.navigation

import android.net.Uri

/** Single navigation-app contract used by voice, automations, widget and cluster projection. */
object WazeNavigation {
    const val PACKAGE_NAME = WazeDeepLinkContract.PACKAGE_NAME
    const val APP_LABEL = WazeDeepLinkContract.APP_LABEL
    const val BASE_URL = WazeDeepLinkContract.BASE_URL

    sealed interface Target {
        data class Coordinates(val latitude: Double, val longitude: Double) : Target
        data class Search(val query: String) : Target
        enum class Favorite : Target { HOME, WORK }
    }

    data class Request(
        val target: Target,
        val startNavigation: Boolean,
        val zoom: Int? = null,
    )

    /** Packages written by older BYDMate versions; used only for one-way preference migration. */
    const val LEGACY_DEFAULT_PACKAGE = "ru.yandex.yandexnavi"
    val LEGACY_YANDEX_PACKAGES = setOf(
        LEGACY_DEFAULT_PACKAGE,
        "ru.yandex.yandexnavi.inhouse",
        "ru.yandex.yandexnavi.rustore",
    )

    fun routeTo(lat: Double, lon: Double, source: String): Uri = uri(
        Request(Target.Coordinates(lat, lon), startNavigation = true),
        source,
    )

    fun showPoint(lat: Double, lon: Double, source: String): Uri = uri(
        Request(Target.Coordinates(lat, lon), startNavigation = false, zoom = 8),
        source,
    )

    fun search(query: String, startNavigation: Boolean, source: String): Uri = uri(
        Request(Target.Search(query), startNavigation),
        source,
    )

    fun favorite(name: String, source: String): Uri {
        val favorite = when (name.lowercase()) {
            "home" -> Target.Favorite.HOME
            "work" -> Target.Favorite.WORK
            else -> throw IllegalArgumentException("Unsupported Waze favorite: $name")
        }
        return uri(Request(favorite, startNavigation = true), source)
    }

    fun uri(request: Request, source: String): Uri {
        WazeDeepLinkContract.requireValidSource(source)
        val builder = Uri.parse(BASE_URL).buildUpon()
        when (val target = request.target) {
            is Target.Coordinates -> {
                WazeDeepLinkContract.requireValidCoordinates(target.latitude, target.longitude)
                builder.appendQueryParameter("ll", "${target.latitude},${target.longitude}")
            }
            is Target.Search -> {
                require(target.query.isNotBlank()) { "Waze search query cannot be blank" }
                builder.appendQueryParameter("q", target.query.trim())
            }
            Target.Favorite.HOME -> builder.appendQueryParameter("favorite", "home")
            Target.Favorite.WORK -> builder.appendQueryParameter("favorite", "work")
        }
        if (request.startNavigation) builder.appendQueryParameter("navigate", "yes")
        request.zoom?.let {
            require(request.target is Target.Coordinates && !request.startNavigation) {
                "Waze zoom is only valid when showing a coordinate"
            }
            require(it in 6..8_192) { "Invalid Waze zoom" }
            builder.appendQueryParameter("z", it.toString())
        }
        return builder.appendQueryParameter("utm_source", source).build().also {
            check(WazeDeepLinkContract.isAllowed(it.toString())) { "Built invalid Waze deep link" }
        }
    }

    fun isAllowedDeepLink(raw: String): Boolean = WazeDeepLinkContract.isAllowed(raw)
}
