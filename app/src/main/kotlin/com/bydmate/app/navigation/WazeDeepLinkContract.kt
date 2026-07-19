package com.bydmate.app.navigation

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Shared, strict contract for the only external URI accepted by the privileged helper.
 *
 * Keeping validation next to URI construction prevents the app and shell-uid process from
 * gradually accepting different Waze link shapes. The helper verifies values as well as keys:
 * an allow-listed key with an empty, duplicated or malformed value is still rejected.
 */
object WazeDeepLinkContract {
    const val PACKAGE_NAME = "com.waze"
    const val APP_LABEL = "Waze"
    const val SCHEME = "https"
    const val HOST = "waze.com"
    const val PATH = "/ul"
    const val BASE_URL = "$SCHEME://$HOST$PATH"

    private const val MAX_URI_LENGTH = 4_096
    private const val MAX_QUERY_LENGTH = 512
    private const val MAX_SOURCE_LENGTH = 128
    private val allowedKeys = setOf("ll", "navigate", "utm_source", "q", "favorite", "z")
    private val targetKeys = setOf("ll", "q", "favorite")

    fun isAllowed(raw: String): Boolean {
        if (raw.isBlank() || raw.length > MAX_URI_LENGTH) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return false
        if (!uri.host.equals(HOST, ignoreCase = true)) return false
        if (uri.port != -1 || uri.userInfo != null || uri.fragment != null || uri.rawPath != PATH) {
            return false
        }

        val parameters = parseQuery(uri.rawQuery ?: return false) ?: return false
        if (parameters.keys.any { it !in allowedKeys }) return false
        if (parameters.keys.count { it in targetKeys } != 1) return false

        val source = parameters["utm_source"] ?: return false
        if (!isValidSource(source)) return false
        if (parameters["navigate"]?.let { it != "yes" } == true) return false

        val ll = parameters["ll"]
        val query = parameters["q"]
        val favorite = parameters["favorite"]
        val zoom = parameters["z"]

        if (ll != null && !isValidCoordinates(ll)) return false
        if (query != null && (query.isBlank() || query.length > MAX_QUERY_LENGTH)) return false
        if (favorite != null && (favorite !in setOf("home", "work") || parameters["navigate"] != "yes")) {
            return false
        }
        if (zoom != null) {
            if (ll == null || parameters["navigate"] != null) return false
            if (zoom.toIntOrNull()?.let { it in 6..8_192 } != true) return false
        }
        return true
    }

    fun requireValidSource(source: String) {
        require(isValidSource(source)) { "Invalid Waze utm_source" }
    }

    fun requireValidCoordinates(latitude: Double, longitude: Double) {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
    }

    private fun isValidSource(source: String): Boolean =
        source.isNotBlank() && source.length <= MAX_SOURCE_LENGTH &&
            source.matches(Regex("[A-Za-z0-9._-]+"))

    private fun isValidCoordinates(value: String): Boolean {
        val parts = value.split(',')
        if (parts.size != 2) return false
        val latitude = parts[0].toDoubleOrNull() ?: return false
        val longitude = parts[1].toDoubleOrNull() ?: return false
        return latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    private fun parseQuery(rawQuery: String): Map<String, String>? {
        if (rawQuery.isBlank()) return null
        val result = LinkedHashMap<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isBlank() || '=' !in part) return null
            val rawKey = part.substringBefore('=')
            val rawValue = part.substringAfter('=')
            val key = decode(rawKey) ?: return null
            val value = decode(rawValue) ?: return null
            if (key.isBlank() || result.put(key, value) != null) return null
        }
        return result
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
