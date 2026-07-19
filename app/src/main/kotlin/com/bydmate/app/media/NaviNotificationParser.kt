package com.bydmate.app.media

import android.app.Notification
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceParser
import com.bydmate.app.navdata.NavManeuverCodes

/** Parses Waze's standard navigation-notification extras without private RemoteViews reflection. */
object NaviNotificationParser {
    data class Parsed(
        val maneuver: String?,
        val distance: String?,
        val street: String?,
        val bigTexts: List<String>,
        val remainingDistance: String? = null,
        val remainingTime: String? = null,
        val guidance: NavGuidance? = null,
    ) {
        val hasGuidance: Boolean get() = guidance != null
    }

    fun parse(notification: Notification): Parsed {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map(CharSequence::toString)
            .orEmpty()
        return fromText(title, text, subText, bigText, textLines)
    }

    /** Pure parser used by tests and by calibration of real Waze notification shapes. */
    fun fromText(
        title: String?,
        text: String?,
        subText: String?,
        bigText: String?,
        textLines: List<String> = emptyList(),
    ): Parsed {
        val lines = buildList {
            add(title)
            add(text)
            add(subText)
            add(bigText)
            addAll(textLines)
        }.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .filterNot(::isGenericWazeLine)
            .distinct()

        val maneuverLine = lines.firstOrNull { NavManeuverCodes.fromInstructionText(it) != 0 }
        val maneuverCode = NavManeuverCodes.fromInstructionText(maneuverLine)
        // A route summary such as "12 km · 18 min" is remaining distance, never distance to
        // the next maneuver. Accept maneuver distance only inside the instruction itself or as
        // a standalone title ("350 m") paired with a recognized instruction.
        val distanceLine = maneuverLine?.takeIf { NavGuidanceParser.parseDistanceText(it) > 0 }
            ?: maneuverLine?.let { lines.firstOrNull(::isStandaloneDistance) }
        val distanceLabel = extractDistanceLabel(distanceLine)
        val distanceMeters = NavGuidanceParser.parseDistanceText(distanceLine)
        val routeSummary = lines.firstOrNull { looksLikeEta(it) && extractDistanceLabel(it) != null }
        val street = extractStreet(maneuverLine)
            ?: lines.firstOrNull { candidate ->
                candidate != maneuverLine && candidate != distanceLine &&
                    !looksLikeEta(candidate) && !isStandaloneDistance(candidate) &&
                    NavManeuverCodes.fromInstructionText(candidate) == 0
            }
        val guidance = NavGuidance(
            maneuverGaode = maneuverCode,
            distanceMeters = distanceMeters,
            road = street.orEmpty(),
        // Distance-only and road-only notifications can be community alerts. A recognized
        // instruction is required before notification text can activate or overwrite the HUD.
        ).takeIf { it.maneuverGaode != 0 }
        return Parsed(
            maneuver = NavManeuverCodes.gaodePhrase(maneuverCode),
            distance = distanceLabel,
            street = street,
            bigTexts = lines,
            remainingDistance = extractDistanceLabel(routeSummary),
            remainingTime = routeSummary?.let(::extractDurationLabel),
            guidance = guidance,
        )
    }

    fun dump(notification: Notification): String {
        val e = notification.extras
        return "category=${notification.category} channel=${notification.channelId} " +
            "title=${e.getCharSequence(Notification.EXTRA_TITLE)} " +
            "text=${e.getCharSequence(Notification.EXTRA_TEXT)} " +
            "sub=${e.getCharSequence(Notification.EXTRA_SUB_TEXT)} " +
            "big=${e.getCharSequence(Notification.EXTRA_BIG_TEXT)} " +
            "lines=${e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.contentToString()}"
    }

    private fun isGenericWazeLine(value: String): Boolean {
        val lower = value.lowercase()
        return lower == "waze" || lower == "waze is running" ||
            lower == "running. tap to open." || lower == "waze запущен"
    }

    private fun extractStreet(instruction: String?): String? {
        if (instruction == null) return null
        val patterns = listOf(
            Regex("""\b(?:onto|toward|towards)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""(?:на улицу|в сторону|к)\s+(.+)$""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(instruction)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    private fun looksLikeEta(value: String): Boolean =
        Regex("""\b\d{1,2}:\d{2}\b""").containsMatchIn(value) ||
            Regex("""\b\d+\s*(?:мин|min|mins|minute|minutes|ч|h|hr|hours)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(value)

    private fun isStandaloneDistance(value: String): Boolean = Regex(
        """^(?:in\s+|через\s+)?\d+(?:[.,]\d+)?\s*(?:км|km|м|m|mi|mile|miles|ft|foot|feet)(?!\p{L})$""",
        setOf(RegexOption.IGNORE_CASE),
    ).matches(value.trim())

    private fun extractDurationLabel(value: String): String? = Regex(
        """\b\d+\s*(?:мин|min|mins|minute|minutes|ч|h|hr|hours)\b""",
        RegexOption.IGNORE_CASE,
    ).find(value)?.value?.trim()

    private fun extractDistanceLabel(value: String?): String? = value?.let {
        Regex(
            """\d+(?:[.,]\d+)?\s*(?:км|km|м|m|mi|mile|miles|ft|foot|feet)(?!\p{L})""",
            RegexOption.IGNORE_CASE,
        ).find(it)?.value?.trim()
    }
}
