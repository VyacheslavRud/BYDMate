package com.bydmate.app.navdata

/** Snapshot of Waze guidance in numeric form. 0 / "" = unknown. */
data class NavGuidance(
    val maneuverGaode: Int = 0,
    val distanceMeters: Int = 0,
    val road: String = "",
    val etaSeconds: Int = 0,
    val totalDistMeters: Int = 0,
    val speedLimit: Int = 0,
)

/** Pure parsers: raw Waze widget strings -> NavGuidance. Shared by the a11y
 *  extractor and the notification channel; every field is optional and soft-fails. */
object NavGuidanceParser {

    // A unit may be followed by punctuation in a complete Waze instruction ("500 m, turn").
    // Reject only another letter so `m` cannot steal the prefix of `mi`/`mile`.
    private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(\d+)\s*(м|m)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val DIST_MI = Regex("""(\d+(?:[.,]\d+)?)\s*(mi|mile|miles)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val DIST_FT = Regex("""(\d+(?:[.,]\d+)?)\s*(ft|foot|feet)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val ETA_HR_MIN = Regex(
        """(\d+)\s*(?:ч|h|hr|hrs|hour|hours)\s*(\d+)\s*(?:мин|min|mins|minute|minutes)""",
        RegexOption.IGNORE_CASE,
    )
    private val ETA_HR = Regex(
        """(\d+)\s*(?:ч|h|hr|hrs|hour|hours)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val ETA_MIN = Regex(
        """(\d+)\s*(?:мин|min|mins|minute|minutes)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    data class RawFields(
        val maneuverDesc: String?,
        val exitNumber: String?,
        val distance: String?,
        val nextStreet: String?,
        val etaTime: String?,
        val etaDistance: String?,
        val speedLimit: String?,
    )

    /** Null when no guidance widget is visible (donor's hasManeuverBalloon gate). */
    fun parse(raw: RawFields): NavGuidance? {
        val guidanceVisible = raw.maneuverDesc != null || raw.distance != null || raw.nextStreet != null
        if (!guidanceVisible) return null
        return NavGuidance(
            maneuverGaode = resolveManeuver(raw),
            distanceMeters = resolveDistance(raw.distance),
            road = raw.nextStreet.orEmpty(),
            etaSeconds = resolveEta(raw.etaTime),
            totalDistMeters = parseDistanceText(raw.etaDistance),
            speedLimit = raw.speedLimit?.trim()?.toIntOrNull() ?: 0,
        )
    }

    /** Combined metric or imperial Waze distance -> meters (notification and ETA distance). */
    fun parseDistanceText(text: String?): Int {
        val s = text?.trim() ?: return 0
        DIST_KM.find(s)?.let {
            return ((it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) * 1000).toInt()
        }
        DIST_MI.find(s)?.let {
            return ((it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) * 1609.344).toInt()
        }
        DIST_FT.find(s)?.let {
            return ((it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) * 0.3048).toInt()
        }
        DIST_M.find(s)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        return 0
    }

    private fun resolveManeuver(raw: RawFields): Int {
        // A numbered exit (1..10) is a sufficient roundabout signal even if Waze exposes only
        // a generic turn phrase in the maneuver view.
        val exitNum = raw.exitNumber?.let { Regex("""\d+""").find(it)?.value }?.toIntOrNull()
        if (exitNum != null && exitNum in 1..10) return NavManeuverCodes.GAODE_ROUNDABOUT_EXIT
        return NavManeuverCodes.fromInstructionText(raw.maneuverDesc)
    }

    private fun resolveDistance(distance: String?): Int {
        val rawText = distance?.trim() ?: return 0
        parseDistanceText(rawText).takeIf { it > 0 }
            ?.let { return it }
        // A unit-less Waze accessibility value is treated as metres, matching its UI contract.
        rawText.replace(',', '.').toDoubleOrNull()?.let { return it.toInt() }
        return 0
    }

    private fun resolveEta(etaTime: String?): Int {
        val s = etaTime ?: return 0
        ETA_HR_MIN.find(s)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 0) * 3600 + (it.groupValues[2].toIntOrNull() ?: 0) * 60
        }
        ETA_HR.find(s)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 3600 }
        ETA_MIN.find(s)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) * 60 }
        return 0
    }
}
