package com.bydmate.app.navdata

/** Waze instruction text -> GAODE code used by the factory HUD, plus Russian voice phrases.
 *  The numeric output contract comes from @rbgboost's YandexHUD work; only the navigation-data
 *  source and text recognizer are Waze-specific. */
object NavManeuverCodes {
    private val WHITESPACE = Regex("""\s+""")
    private val SYMBOLIC_TAG = Regex("""^[A-Za-z][A-Za-z0-9]*(?:[_-][A-Za-z0-9]+)+$""")
    const val GAODE_LEFT = 1
    const val GAODE_RIGHT = 2
    const val GAODE_SLIGHT_LEFT = 3
    const val GAODE_SLIGHT_RIGHT = 4
    const val GAODE_HARD_LEFT = 7
    const val GAODE_HARD_RIGHT = 8
    const val GAODE_UTURN = 9
    const val GAODE_UTURN_RIGHT = 10
    const val GAODE_STRAIGHT = 11
    const val GAODE_ROUNDABOUT_ENTER = 13
    const val GAODE_ROUNDABOUT_EXIT = 24
    const val GAODE_WAYPOINT = 45
    const val GAODE_FERRY = 46
    const val GAODE_ARRIVE = 48
    const val GAODE_TUNNEL = 49

    /**
     * Codes that describe where the car must steer, as opposed to a route-lifecycle or road-feature
     * state.
     *
     * Only these may be inferred by an untargeted tree scan. ARRIVE, WAYPOINT, FERRY and TUNNEL are
     * legitimate maneuvers when Waze publishes them on its own maneuver node, but they are also
     * ordinary words in ETA, destination and progress panels ("Прибытие 19:12"), so a scan that is
     * allowed to return them will label a still-running route from unrelated screen text.
     */
    private val DIRECTIONAL_CODES = setOf(
        GAODE_LEFT, GAODE_RIGHT,
        GAODE_SLIGHT_LEFT, GAODE_SLIGHT_RIGHT,
        GAODE_HARD_LEFT, GAODE_HARD_RIGHT,
        GAODE_UTURN, GAODE_UTURN_RIGHT,
        GAODE_STRAIGHT,
        GAODE_ROUNDABOUT_ENTER, GAODE_ROUNDABOUT_EXIT,
    )

    fun isDirectionalManeuver(gaode: Int): Boolean = gaode in DIRECTIONAL_CODES

    /** Privacy-safe parsing result used by diagnostics: it exposes only recognized directions,
     *  never Waze's raw instruction or road names. [recognizedCodes] is in textual order. */
    data class ParseResult(
        val gaode: Int,
        val recognizedCodes: List<Int>,
    ) {
        fun diagnosticSummary(): String {
            val recognized = recognizedCodes.joinToString(">") { codeName(it) }.ifEmpty { "UNKNOWN" }
            return "recognized=$recognized selected=${codeName(gaode)} gaode=$gaode"
        }
    }

    private data class ManeuverPattern(val regex: Regex, val code: Int)
    private data class Candidate(val code: Int, val start: Int, val endExclusive: Int, val rank: Int) {
        val length: Int get() = endExclusive - start
        fun overlaps(other: Candidate): Boolean = start < other.endExclusive && other.start < endExclusive
    }

    private val EN_NUMBERED_EXIT_RE = Regex(
        """(?:(?:(?:at|on)\s+)?(?:the\s+)?roundabout\b.{0,48}?)?(?:take\s+)?(?:the\s+)?(\d+)(?:st|nd|rd|th)?\s+exit\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RU_NUMBERED_EXIT_RE = Regex(
        """(?:(?:кольц\p{L}*|кругов\p{L}*)(?!\p{L}).{0,48}?)?(\d+)[-‑ ]?(?:й|я|е)?\s+съезд(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    private fun literalRegex(value: String): Regex = Regex(
        """(?<!\p{L})${Regex.escape(value)}(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    private val MANEUVER_PATTERNS: List<ManeuverPattern> = buildList {
        fun add(code: Int, vararg phrases: String) {
            phrases.forEach { add(ManeuverPattern(literalRegex(it), code)) }
        }

        add(GAODE_UTURN_RIGHT,
            "развернитесь направо", "разворот направо",
            "make a u-turn to the right", "make a u turn to the right", "u-turn right", "u turn right",
            "otocte se doprava", "otočte se doprava",
            "向右掉头")
        add(GAODE_UTURN,
            "развернитесь налево", "разворот налево", "развернитесь", "разворот",
            "make a u-turn", "make a u turn", "u-turn", "u turn", "掉头")
        add(GAODE_UTURN, "otocte se", "otočte se")
        add(GAODE_HARD_LEFT, "резкий поворот налево", "резко налево", "sharp left", "ostře vlevo")
        add(GAODE_HARD_RIGHT, "резкий поворот направо", "резко направо", "sharp right", "ostře vpravo")
        add(GAODE_SLIGHT_LEFT,
            "плавный поворот налево", "плавно налево", "держитесь левее", "левее",
            "slight left", "keep left", "bear left", "fork left",
            "držte se vlevo", "mírně vlevo", "靠左")
        add(GAODE_SLIGHT_RIGHT,
            "плавный поворот направо", "плавно направо", "держитесь правее", "правее",
            "slight right", "keep right", "bear right", "fork right",
            "držte se vpravo", "mírně vpravo", "靠右")
        add(GAODE_ROUNDABOUT_EXIT,
            "выезд с кольца", "съезд с кольца", "съезжайте с кольца", "выезжайте из кольца",
            "exit the roundabout", "leave the roundabout")
        add(GAODE_ROUNDABOUT_ENTER,
            "кольцевое", "круговое", "въезжайте на кольцо", "войдите в кольцо", "кольцо", "roundabout", "环岛")
        add(GAODE_FERRY, "въезд на паром", "board the ferry", "паром")
        add(GAODE_STRAIGHT, "съезд с парома", "выезд с парома", "leave the ferry")
        add(GAODE_WAYPOINT, "промежуточная точка")
        add(GAODE_ARRIVE,
            "you have arrived", "arrive at your destination", "destination reached", "reached your destination",
            "вы прибыли", "прибытие", "маршрут окончен", "маршрут завершён", "до конца маршрута",
            "конец маршрута", "конечная", "достигнут")
        add(GAODE_TUNNEL, "тоннель", "туннель", "tunnel")
        add(GAODE_LEFT,
            "поверните налево", "поворот налево", "съезд налево", "налево",
            "take the left", "turn left", "exit left",
            "odbočte vlevo", "zahněte vlevo", "doleva", "vlevo", "向左转", "左转")
        add(GAODE_RIGHT,
            "поверните направо", "поворот направо", "съезд направо", "направо",
            "take the right", "turn right", "exit right",
            "odbočte vpravo", "zahněte vpravo", "doprava", "vpravo", "向右转", "右转")
        add(GAODE_STRAIGHT,
            "продолжайте прямо", "двигайтесь прямо", "продолжайте", "двигайтесь", "прямо",
            "keep straight", "continue straight", "continue", "straight",
            "pokračujte rovně", "jeďte rovně", "rovně", "直行")
    }

    fun parseInstructionText(text: String?): ParseResult {
        if (text.isNullOrBlank()) return ParseResult(0, emptyList())
        if (text.trim() == ">>>") return ParseResult(GAODE_STRAIGHT, listOf(GAODE_STRAIGHT))
        val trimmed = text.trim()
        val symbolicTag = SYMBOLIC_TAG.matches(trimmed)
        // Waze sometimes exposes the arrow as a resource/test tag such as TURN_RIGHT instead of
        // localized spoken text. Treat only a compact resource-style tag as symbolic; underscores
        // in ordinary street text must not become direction instructions.
        val normalized = trimmed.lowercase()
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace('\u2011', '-')
            .let { value ->
                if (symbolicTag) value.replace('_', ' ').replace('-', ' ') else value
            }
            .replace(WHITESPACE, " ")

        // Waze 5.x sometimes exposes only a short accessibility description for the arrow.
        // Keep these exact so a road/street containing the word cannot become a false maneuver.
        if (normalized == "left") return ParseResult(GAODE_LEFT, listOf(GAODE_LEFT))
        if (normalized == "right") return ParseResult(GAODE_RIGHT, listOf(GAODE_RIGHT))

        val candidates = mutableListOf<Candidate>()
        fun collect(regex: Regex, code: Int, rank: Int) {
            regex.findAll(normalized).forEach { match ->
                candidates += Candidate(code, match.range.first, match.range.last + 1, rank)
            }
        }
        fun collectNumberedExit(regex: Regex, rank: Int) {
            regex.findAll(normalized).forEach { match ->
                val exit = match.groupValues.getOrNull(1)?.toIntOrNull()
                if (exit in 1..10) {
                    candidates += Candidate(
                        GAODE_ROUNDABOUT_EXIT,
                        match.range.first,
                        match.range.last + 1,
                        rank,
                    )
                }
            }
        }
        collectNumberedExit(EN_NUMBERED_EXIT_RE, rank = -2)
        collectNumberedExit(RU_NUMBERED_EXIT_RE, rank = -1)
        MANEUVER_PATTERNS.forEachIndexed { index, pattern ->
            collect(pattern.regex, pattern.code, rank = index)
        }
        if (symbolicTag) {
            // Bare direction words are accepted only inside an explicit symbolic tag. In normal
            // prose they would misread road names such as "Left Bank Road" as a maneuver.
            collect(literalRegex("left"), GAODE_LEFT, rank = MANEUVER_PATTERNS.size)
            collect(literalRegex("right"), GAODE_RIGHT, rank = MANEUVER_PATTERNS.size + 1)
        }

        // Specific overlapping phrases win ("slight right" over "right"). Non-overlapping
        // matches remain in textual order, so compound Waze instructions select the maneuver
        // the driver must perform first rather than whichever branch happened to run first.
        val ordered = candidates.sortedWith(
            compareBy<Candidate> { it.start }
                .thenByDescending { it.length }
                .thenBy { it.rank },
        )
        val semantic = mutableListOf<Candidate>()
        ordered.forEach { candidate ->
            if (semantic.none(candidate::overlaps)) semantic += candidate
        }
        val codes = semantic.sortedBy { it.start }.map { it.code }
        return ParseResult(codes.firstOrNull() ?: 0, codes)
    }

    fun fromInstructionText(text: String?): Int = parseInstructionText(text).gaode

    /** GAODE -> short Russian phrase; used by get_route_info when only hub numerics exist. */
    private val PHRASES = mapOf(
        GAODE_LEFT to "налево",
        GAODE_RIGHT to "направо",
        GAODE_SLIGHT_LEFT to "левее",
        GAODE_SLIGHT_RIGHT to "правее",
        GAODE_HARD_LEFT to "резко налево",
        GAODE_HARD_RIGHT to "резко направо",
        GAODE_UTURN to "разворот",
        GAODE_UTURN_RIGHT to "разворот направо",
        GAODE_STRAIGHT to "прямо",
        GAODE_ROUNDABOUT_ENTER to "круговое движение",
        GAODE_ROUNDABOUT_EXIT to "съезд с кольца",
        GAODE_WAYPOINT to "промежуточная точка",
        GAODE_FERRY to "паром",
        GAODE_ARRIVE to "прибытие",
        GAODE_TUNNEL to "тоннель",
    )

    fun gaodePhrase(gaode: Int): String? = PHRASES[gaode]

    internal fun codeName(code: Int): String = when (code) {
        GAODE_LEFT -> "LEFT"
        GAODE_RIGHT -> "RIGHT"
        GAODE_SLIGHT_LEFT -> "SLIGHT_LEFT"
        GAODE_SLIGHT_RIGHT -> "SLIGHT_RIGHT"
        GAODE_HARD_LEFT -> "HARD_LEFT"
        GAODE_HARD_RIGHT -> "HARD_RIGHT"
        GAODE_UTURN -> "UTURN_LEFT"
        GAODE_UTURN_RIGHT -> "UTURN_RIGHT"
        GAODE_STRAIGHT -> "STRAIGHT"
        GAODE_ROUNDABOUT_ENTER -> "ROUNDABOUT_ENTER"
        GAODE_ROUNDABOUT_EXIT -> "ROUNDABOUT_EXIT"
        GAODE_WAYPOINT -> "WAYPOINT"
        GAODE_FERRY -> "FERRY"
        GAODE_ARRIVE -> "ARRIVE"
        GAODE_TUNNEL -> "TUNNEL"
        else -> "UNKNOWN"
    }
}
