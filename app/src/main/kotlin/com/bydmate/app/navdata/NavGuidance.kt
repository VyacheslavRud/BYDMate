package com.bydmate.app.navdata

/** Snapshot of Waze guidance in numeric form. 0 / "" = unknown. */
data class NavGuidance(
    val maneuverGaode: Int = 0,
    val distanceMeters: Int = 0,
    val road: String = "",
    val etaSeconds: Int = 0,
    /** Absolute local arrival clock normalized to HH:mm; preferred over relative ETA. */
    val arrivalTime: String = "",
    val totalDistMeters: Int = 0,
    val speedLimit: Int = 0,
)

/** Pure parsers: raw Waze widget strings -> NavGuidance. Shared by the a11y
 *  extractor and the notification channel; every field is optional and soft-fails. */
object NavGuidanceParser {
    private const val MAX_DISTANCE_METERS = 20_000_000
    private const val MAX_ETA_SECONDS = 30 * 24 * 60 * 60
    private const val MIN_SPEED_LIMIT = 5
    private const val MAX_SPEED_LIMIT = 250

    // A unit may be followed by punctuation in a complete Waze instruction ("500 m, turn").
    // Reject only another letter so `m` cannot steal the prefix of `mi`/`mile`.
    private val DIST_KM = Regex("""(?<![-+\d.,])(\d+(?:[.,]\d+)?)\s*(км|km)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(?<![-+\d.,])(\d+)\s*(м|m)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val DIST_MI = Regex("""(?<![-+\d.,])(\d+(?:[.,]\d+)?)\s*(mi|mile|miles)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val DIST_FT = Regex("""(?<![-+\d.,])(\d+(?:[.,]\d+)?)\s*(ft|foot|feet)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val ETA_HR_MIN = Regex(
        """(?<![-+\d])(\d+)\s*(?:ч|h|hr|hrs|hour|hours)\s*(\d+)\s*(?:мин|min|mins|minute|minutes)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val ETA_HR = Regex(
        """(?<![-+\d])(\d+)\s*(?:ч|h|hr|hrs|hour|hours)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val ETA_MIN = Regex(
        """(?<![-+\d])(\d+)\s*(?:мин|min|mins|minute|minutes)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val SPEED_LIMIT = Regex("""(?<![-+\d])(\d{1,4})(?!\d)""")
    private val ARRIVAL_12_HOUR = Regex(
        """(?<![-+\d])(\d{1,2}):(\d{2})\s*([ap])\.?\s*m\.?(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val ARRIVAL_24_HOUR = Regex("""(?<![-+\d])(\d{1,2}):(\d{2})(?!\d)""")

    data class RawFields(
        val maneuverDesc: String?,
        val exitNumber: String?,
        val distance: String?,
        val nextStreet: String?,
        val etaTime: String?,
        val arrivalTime: String?,
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
            etaSeconds = parseDurationSeconds(raw.etaTime),
            // Some Waze layouts put the arrival clock in the generic time node. Preserve the
            // dedicated value when present, but accept that fallback without treating a duration
            // such as "18 min" as a wall-clock time.
            arrivalTime = normalizeArrivalTime(raw.arrivalTime)
                ?: normalizeArrivalTime(raw.etaTime)
                ?: "",
            totalDistMeters = parseDistanceText(raw.etaDistance),
            speedLimit = parseSpeedLimit(raw.speedLimit),
        )
    }

    /** Combined metric or imperial Waze distance -> meters (notification and ETA distance). */
    fun parseDistanceText(text: String?): Int {
        val s = text?.trim() ?: return 0
        data class DistanceMatch(val start: Int, val meters: Int)
        val matches = buildList {
            DIST_KM.findAll(s).forEach {
                add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 1000.0)))
            }
            DIST_MI.findAll(s).forEach {
                add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 1609.344)))
            }
            DIST_FT.findAll(s).forEach {
                add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 0.3048)))
            }
            DIST_M.findAll(s).forEach {
                add(DistanceMatch(it.range.first, scaledMeters(it.groupValues[1], 1.0)))
            }
        }
        return matches.minByOrNull { it.start }?.meters ?: 0
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
        scaledMeters(rawText, 1.0).takeIf { it > 0 }?.let { return it }
        return 0
    }

    /** Waze duration label -> remaining seconds. A wall-clock time intentionally returns zero. */
    fun parseDurationSeconds(etaTime: String?): Int {
        val s = etaTime ?: return 0
        ETA_HR_MIN.find(s)?.let {
            val hours = it.groupValues[1].toLongOrNull() ?: return 0
            val minutes = it.groupValues[2].toLongOrNull() ?: return 0
            if (hours > MAX_ETA_SECONDS / 3600L || minutes > MAX_ETA_SECONDS / 60L) return 0
            return plausibleEtaSeconds(hours * 3600L + minutes * 60L)
        }
        ETA_HR.find(s)?.let {
            val hours = it.groupValues[1].toLongOrNull() ?: return 0
            if (hours > MAX_ETA_SECONDS / 3600L) return 0
            return plausibleEtaSeconds(hours * 3600L)
        }
        ETA_MIN.find(s)?.let {
            val minutes = it.groupValues[1].toLongOrNull() ?: return 0
            if (minutes > MAX_ETA_SECONDS / 60L) return 0
            return plausibleEtaSeconds(minutes * 60L)
        }
        return 0
    }

    private fun plausibleEtaSeconds(seconds: Long): Int =
        seconds.takeIf { it in 1..MAX_ETA_SECONDS.toLong() }?.toInt() ?: 0

    private fun scaledMeters(value: String, scale: Double): Int {
        val numeric = value.replace(',', '.').toDoubleOrNull() ?: return 0
        val meters = numeric * scale
        if (!meters.isFinite() || meters !in 1.0..MAX_DISTANCE_METERS.toDouble()) return 0
        return meters.toInt()
    }

    private fun parseSpeedLimit(value: String?): Int = value
        ?.let { SPEED_LIMIT.find(it)?.groupValues?.get(1) }
        ?.toIntOrNull()
        ?.takeIf { it in MIN_SPEED_LIMIT..MAX_SPEED_LIMIT }
        ?: 0

    /** Accepts 24-hour and English 12-hour Waze clocks and returns canonical local HH:mm. */
    fun normalizeArrivalTime(value: String?): String? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val twelveHour = ARRIVAL_12_HOUR.find(text)
        if (twelveHour != null) {
            val rawHour = twelveHour.groupValues[1].toIntOrNull() ?: return null
            val minute = twelveHour.groupValues[2].toIntOrNull() ?: return null
            if (rawHour !in 1..12 || minute !in 0..59) return null
            val pm = twelveHour.groupValues[3].equals("p", ignoreCase = true)
            val hour = when {
                rawHour == 12 && !pm -> 0
                rawHour < 12 && pm -> rawHour + 12
                else -> rawHour
            }
            return "%02d:%02d".format(java.util.Locale.US, hour, minute)
        }
        val twentyFourHour = ARRIVAL_24_HOUR.find(text)
            ?: return null
        val hour = twentyFourHour.groupValues[1].toIntOrNull() ?: return null
        val minute = twentyFourHour.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(java.util.Locale.US, hour, minute)
    }
}
