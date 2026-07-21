package com.bydmate.app.media

import android.app.Notification
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceParser
import com.bydmate.app.navdata.NavManeuverCodes

/** Parses Waze's standard notification fields plus a bounded semantic RemoteViews arrow hint. */
object NaviNotificationParser {
    private val STREET_PATTERNS = listOf(
        Regex(
            """\b(?:onto|toward|towards)\s+(.+?)(?=\s*(?:[,;]|→)?\s*(?:and\s+then|then)\b|$)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?:на улицу|в сторону|к)\s+(.+?)(?=\s*(?:[,;]|→)?\s*(?:затем|потом|далее)(?!\p{L})|$)""",
            RegexOption.IGNORE_CASE,
        ),
    )
    private val REMAINING_KEYWORDS = Regex(
        """(?<!\p{L})(?:remaining|remain|left|осталось|осталось ехать)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val STANDALONE_DISTANCE = Regex(
        """^(?:in\s+|через\s+)?\d+(?:[.,]\d+)?\s*(?:км|km|м|m|mi|mile|miles|ft|foot|feet)(?!\p{L})$""",
        RegexOption.IGNORE_CASE,
    )
    private val DURATION_LABELS = listOf(
        Regex(
            """(?<![-+\d])\d+\s*(?:ч|h|hr|hrs|hour|hours)\s*\d+\s*(?:мин|min|mins|minute|minutes)(?!\p{L})""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?<![-+\d])\d+\s*(?:ч|h|hr|hrs|hour|hours|мин|min|mins|minute|minutes)(?!\p{L})""",
            RegexOption.IGNORE_CASE,
        ),
    )
    private val DISTANCE_LABEL = Regex(
        """(?<![-+\d.,])\d+(?:[.,]\d+)?\s*(?:км|km|м|m|mi|mile|miles|ft|foot|feet)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    data class Parsed(
        val maneuver: String?,
        val distance: String?,
        val street: String?,
        val bigTexts: List<String>,
        val remainingDistance: String? = null,
        val remainingTime: String? = null,
        val arrivalTime: String? = null,
        val guidance: NavGuidance? = null,
    ) {
        val hasGuidance: Boolean get() = guidance != null
    }

    fun parse(
        notification: Notification,
        resolveName: (Int) -> String? = { null },
    ): Parsed {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map(CharSequence::toString)
            .orEmpty()
        val remoteViews = WazeRemoteViewsManeuverReader.inspect(notification, resolveName)
        return fromText(
            title,
            text,
            subText,
            bigText,
            textLines,
            maneuverHints = semanticManeuverHints(notification) + remoteViews.semanticTextHints,
            maneuverCodeHint = remoteViews.maneuverGaode,
        )
    }

    /** Pure parser used by tests and by calibration of real Waze notification shapes. */
    fun fromText(
        title: String?,
        text: String?,
        subText: String?,
        bigText: String?,
        textLines: List<String> = emptyList(),
        maneuverHints: List<String> = emptyList(),
        maneuverCodeHint: Int = 0,
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
            ?: maneuverHints.firstOrNull { NavManeuverCodes.fromInstructionText(it) != 0 }
        val maneuverCode = NavManeuverCodes.fromInstructionText(maneuverLine)
            .takeIf { it != 0 }
            ?: maneuverCodeHint
        // A route summary such as "12 km · 18 min" is remaining distance, never distance to
        // the next maneuver. Accept maneuver distance only inside the instruction itself or as
        // a standalone title ("350 m") paired with a recognized instruction.
        val distanceLine = maneuverLine?.takeIf { NavGuidanceParser.parseDistanceText(it) > 0 }
            ?: lines.firstOrNull(::isStandaloneDistance).takeIf { maneuverCode != 0 }
        val distanceLabel = extractDistanceLabel(distanceLine)
        val distanceMeters = NavGuidanceParser.parseDistanceText(distanceLine)
        val routeLines = lines.filter { it != maneuverLine && it != distanceLine }
        val remainingTime = routeLines.firstNotNullOfOrNull(::extractDurationLabel)
        val arrivalTime = routeLines.firstNotNullOfOrNull(NavGuidanceParser::normalizeArrivalTime)
        val remainingDistance = routeLines
            .firstOrNull(::looksLikeRemainingDistance)
            ?.let(::extractDistanceLabel)
        val routeSummaryLines = routeLines.filter(::looksLikeRouteSummary)
        val street = extractStreet(maneuverLine)
            ?: lines.firstOrNull { candidate ->
                candidate != maneuverLine && candidate != distanceLine &&
                    candidate !in routeSummaryLines &&
                    !looksLikeEta(candidate) && !isStandaloneDistance(candidate) &&
                    NavManeuverCodes.fromInstructionText(candidate) == 0
            }
        val guidance = NavGuidance(
            maneuverGaode = maneuverCode,
            distanceMeters = distanceMeters,
            road = street.orEmpty(),
            etaSeconds = NavGuidanceParser.parseDurationSeconds(remainingTime),
            arrivalTime = arrivalTime.orEmpty(),
            totalDistMeters = NavGuidanceParser.parseDistanceText(remainingDistance),
        // Distance-only and road-only notifications can be community alerts. A recognized
        // instruction is required before notification text can activate or overwrite the HUD.
        ).takeIf { it.maneuverGaode != 0 }
        return Parsed(
            maneuver = NavManeuverCodes.gaodePhrase(maneuverCode),
            distance = distanceLabel,
            street = street,
            bigTexts = lines,
            remainingDistance = remainingDistance,
            remainingTime = remainingTime,
            arrivalTime = arrivalTime,
            guidance = guidance,
        )
    }

    fun dump(
        notification: Notification,
        resolveName: (Int) -> String? = { null },
    ): String {
        val e = notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map(CharSequence::toString).orEmpty()
        val maneuverHints = semanticManeuverHints(notification)
        val remoteViews = WazeRemoteViewsManeuverReader.inspect(notification, resolveName)
        val parseResult = sequenceOf(title, text, sub, big)
            .plus(lines.asSequence())
            .plus(maneuverHints.asSequence())
            .filterNotNull()
            .map(NavManeuverCodes::parseInstructionText)
            .firstOrNull { it.recognizedCodes.isNotEmpty() }
            ?: NavManeuverCodes.ParseResult(0, emptyList())
        val parsed = runCatching {
            fromText(
                title,
                text,
                sub,
                big,
                lines,
                maneuverHints + remoteViews.semanticTextHints,
                remoteViews.maneuverGaode,
            )
        }.getOrNull()
        return "source=notification category=${notification.category} channel=${notification.channelId} " +
            "title=${fieldShape(title)} text=${fieldShape(text)} sub=${fieldShape(sub)} " +
            "big=${fieldShape(big)} lines=${lines.size} semanticHints=${maneuverHints.size} " +
            "remoteViews=${remoteViews.remoteViewsPresent} " +
            "remoteActions=${remoteViews.actionsInspected} " +
            "remoteImageResources=${remoteViews.imageResourcesInspected} " +
            "remoteManeuver=${NavManeuverCodes.codeName(remoteViews.maneuverGaode)}" +
            "(${remoteViews.maneuverGaode}) " +
            "remoteResource=${remoteViews.maneuverResource ?: "none"} " +
            "guidance=${parsed?.hasGuidance == true} " +
            "${parseResult.diagnosticSummary()}"
    }

    /**
     * Waze vendor builds sometimes keep the instruction in a non-standard string extra while the
     * standard title/text contain only distance and street. Inspect string-like extras in memory,
     * with strict count/length bounds; raw values are never logged or persisted.
     */
    @Suppress("DEPRECATION")
    private fun semanticManeuverHints(notification: Notification): List<String> = buildList {
        val extras = notification.extras ?: return@buildList
        for (key in extras.keySet().sorted()) {
            if (size >= MAX_SEMANTIC_HINTS) break
            val value = runCatching { extras.get(key) }.getOrNull()
            when (value) {
                is CharSequence -> addBoundedHint(value.toString())
                is Array<*> -> value.asSequence()
                    .filterIsInstance<CharSequence>()
                    .forEach { addBoundedHint(it.toString()) }
                is Iterable<*> -> value.asSequence()
                    .filterIsInstance<CharSequence>()
                    .forEach { addBoundedHint(it.toString()) }
            }
        }
    }.distinct()

    private fun MutableList<String>.addBoundedHint(value: String) {
        if (size >= MAX_SEMANTIC_HINTS) return
        value.trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_SEMANTIC_HINT_CHARS }
            ?.let(::add)
    }

    private fun fieldShape(value: String?): String =
        if (value == null) "absent" else "present(len=${value.length})"

    private fun isGenericWazeLine(value: String): Boolean {
        val lower = value.lowercase()
        return lower == "waze" || lower == "waze is running" ||
            lower == "running. tap to open." || lower == "waze запущен"
    }

    private fun extractStreet(instruction: String?): String? {
        if (instruction == null) return null
        return STREET_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(instruction)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    private fun looksLikeEta(value: String): Boolean =
        NavGuidanceParser.parseDurationSeconds(value) > 0 ||
            NavGuidanceParser.normalizeArrivalTime(value) != null

    private fun looksLikeRemainingDistance(value: String): Boolean =
        extractDistanceLabel(value) != null &&
            (looksLikeEta(value) || isStandaloneDistance(value) ||
                REMAINING_KEYWORDS.containsMatchIn(value))

    private fun looksLikeRouteSummary(value: String): Boolean =
        looksLikeEta(value) || looksLikeRemainingDistance(value)

    private fun isStandaloneDistance(value: String): Boolean =
        STANDALONE_DISTANCE.matches(value.trim())

    private fun extractDurationLabel(value: String): String? {
        return DURATION_LABELS.firstNotNullOfOrNull { it.find(value)?.value?.trim() }
    }

    private fun extractDistanceLabel(value: String?): String? =
        value?.let { DISTANCE_LABEL.find(it)?.value?.trim() }

    private const val MAX_SEMANTIC_HINTS = 48
    private const val MAX_SEMANTIC_HINT_CHARS = 256
}
