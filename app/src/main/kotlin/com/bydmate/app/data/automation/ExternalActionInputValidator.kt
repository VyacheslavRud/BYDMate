package com.bydmate.app.data.automation

import java.net.URI

/**
 * Pure validation shared by the automation editor, persisted-rule validation and
 * the runtime dispatcher. Runtime validation is mandatory because restored or
 * legacy database rows do not necessarily pass through the current editor.
 */
object ExternalActionInputValidator {
    private val PHONE_PATTERN = Regex("^\\+?[0-9 ()-]{5,20}$")
    private val SAFE_APP_SCHEMES = setOf("yandexmusic", "geo", "waze")

    fun isValidPhone(raw: String): Boolean {
        val phone = raw.trim()
        return PHONE_PATTERN.matches(phone) && phone.count(Char::isDigit) >= 5
    }

    fun isValidUrl(raw: String): Boolean {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return false
        }
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank()
            in SAFE_APP_SCHEMES -> !uri.rawSchemeSpecificPart.isNullOrBlank()
            else -> false
        }
    }
}
