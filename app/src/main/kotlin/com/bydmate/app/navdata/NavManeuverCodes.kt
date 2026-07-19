package com.bydmate.app.navdata

/** Waze instruction text -> GAODE code used by the factory HUD, plus Russian voice phrases.
 *  The numeric output contract comes from @rbgboost's YandexHUD work; only the navigation-data
 *  source and text recognizer are Waze-specific. */
object NavManeuverCodes {
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

    private val ROUNDABOUT_EXIT_RE = Regex(
        """(?:(\d+)[-‑]й\s+съезд|(\d+)(?:st|nd|rd|th)?\s+exit)""",
        RegexOption.IGNORE_CASE,
    )

    fun fromInstructionText(text: String?): Int {
        if (text == null || text.isBlank()) return 0
        if (text == ">>>") return GAODE_STRAIGHT
        // NBSP via explicit escape: a literal NBSP is invisible and gets lost in copy/transcription
        val lower = text.lowercase().trim().replace('\u00A0', ' ')

        val match = ROUNDABOUT_EXIT_RE.find(lower)
        val exitNum = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        if (exitNum != null && exitNum in 1..10) return GAODE_ROUNDABOUT_EXIT

        // Waze 5.x sometimes exposes only a short accessibility description for the arrow.
        // Keep these exact so a road/street containing the word cannot become a false maneuver.
        if (lower == "left") return GAODE_LEFT
        if (lower == "right") return GAODE_RIGHT

        return when {
            "въезд на паром" in lower || "board the ferry" in lower -> GAODE_FERRY
            "выезд с кольца" in lower || "съезд с кольца" in lower ||
                "exit the roundabout" in lower || "leave the roundabout" in lower -> GAODE_ROUNDABOUT_EXIT
            "кольцевое" in lower || "круговое" in lower || "roundabout" in lower -> GAODE_ROUNDABOUT_ENTER
            "промежуточная точка" in lower -> GAODE_WAYPOINT
            "съезд с парома" in lower || "выезд с парома" in lower || "leave the ferry" in lower -> GAODE_STRAIGHT
            "прибытие" in lower || "маршрут окончен" in lower || "конечная" in lower ||
                "достигнут" in lower || "destination" in lower || "you have arrived" in lower -> GAODE_ARRIVE
            "тоннель" in lower || "туннель" in lower || "tunnel" in lower -> GAODE_TUNNEL
            "плавный поворот налево" in lower || "плавно налево" in lower || "держитесь левее" in lower ||
                "slight left" in lower || "keep left" in lower -> GAODE_SLIGHT_LEFT
            "плавный поворот направо" in lower || "плавно направо" in lower || "держитесь правее" in lower ||
                "slight right" in lower || "keep right" in lower -> GAODE_SLIGHT_RIGHT
            "резкий поворот налево" in lower || "резко налево" in lower || "sharp left" in lower -> GAODE_HARD_LEFT
            "резкий поворот направо" in lower || "резко направо" in lower || "sharp right" in lower -> GAODE_HARD_RIGHT
            "разворот" in lower || "развернитесь" in lower || "u-turn" in lower || "u turn" in lower ->
                if ("направо" in lower || "right" in lower) GAODE_UTURN_RIGHT else GAODE_UTURN
            "поверните налево" in lower || "поворот налево" in lower || "налево" in lower ||
                "turn left" in lower || "exit left" in lower -> GAODE_LEFT
            "поверните направо" in lower || "поворот направо" in lower || "направо" in lower ||
                "turn right" in lower || "exit right" in lower -> GAODE_RIGHT
            "прямо" in lower || "продолжайте" in lower || "двигайтесь" in lower ||
                "straight" in lower || "continue" in lower -> GAODE_STRAIGHT
            else -> fromTextFallback(lower)
        }
    }

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

    // -- fallback: donor's internal-enum phrase table, collapsed straight to GAODE --

    private val RU_PHRASES = linkedMapOf(
        "развернитесь направо" to GAODE_UTURN_RIGHT,
        "разворот направо" to GAODE_UTURN_RIGHT,
        "развернитесь налево" to GAODE_UTURN,
        "развернитесь" to GAODE_UTURN,
        "разворот" to GAODE_UTURN,
        "u-turn" to GAODE_UTURN,
        "резкий поворот налево" to GAODE_HARD_LEFT,
        "резко налево" to GAODE_HARD_LEFT,
        "резкий поворот направо" to GAODE_HARD_RIGHT,
        "резко направо" to GAODE_HARD_RIGHT,
        "плавный поворот налево" to GAODE_SLIGHT_LEFT,
        "плавно налево" to GAODE_SLIGHT_LEFT,
        "держитесь левее" to GAODE_SLIGHT_LEFT,
        "плавный поворот направо" to GAODE_SLIGHT_RIGHT,
        "плавно направо" to GAODE_SLIGHT_RIGHT,
        "держитесь правее" to GAODE_SLIGHT_RIGHT,
        "поверните налево" to GAODE_LEFT,
        "поворот налево" to GAODE_LEFT,
        "налево" to GAODE_LEFT,
        "левее" to GAODE_SLIGHT_LEFT,
        "правее" to GAODE_SLIGHT_RIGHT,
        "поверните направо" to GAODE_RIGHT,
        "поворот направо" to GAODE_RIGHT,
        "направо" to GAODE_RIGHT,
        "въезжайте на кольцо" to GAODE_ROUNDABOUT_ENTER,
        "войдите в кольцо" to GAODE_ROUNDABOUT_ENTER,
        "съезжайте с кольца" to GAODE_ROUNDABOUT_EXIT,
        "выезжайте из кольца" to GAODE_ROUNDABOUT_EXIT,
        "съезд с кольца" to GAODE_ROUNDABOUT_EXIT,
        "выезд с кольца" to GAODE_ROUNDABOUT_EXIT,
        "въезд на паром" to GAODE_FERRY,
        "вы прибыли" to GAODE_ARRIVE,
        "маршрут завершён" to GAODE_ARRIVE,
        "до конца маршрута" to GAODE_ARRIVE,
        "конец маршрута" to GAODE_ARRIVE,
        "конечная" to GAODE_ARRIVE,
        "достигнут" to GAODE_ARRIVE,
        "прибытие" to GAODE_ARRIVE,
        "прямо" to GAODE_STRAIGHT,
        "продолжайте прямо" to GAODE_STRAIGHT,
        "продолжить" to GAODE_STRAIGHT,
        "двигайтесь прямо" to GAODE_STRAIGHT,
    )

    private val WORD_BOUNDARY_PHRASES = linkedMapOf(
        "левый" to GAODE_LEFT,
        "правый" to GAODE_RIGHT,
        "паром" to GAODE_FERRY,
        "кольцо" to GAODE_ROUNDABOUT_ENTER,
        "круговое" to GAODE_ROUNDABOUT_ENTER,
        "туннель" to GAODE_TUNNEL,
        "тоннель" to GAODE_TUNNEL,
    )

    private val EN_PHRASES = linkedMapOf(
        "make a u-turn" to GAODE_UTURN,
        "make a u turn" to GAODE_UTURN,
        "bear left" to GAODE_SLIGHT_LEFT,
        "bear right" to GAODE_SLIGHT_RIGHT,
        "take the left" to GAODE_LEFT,
        "take the right" to GAODE_RIGHT,
        "turn left" to GAODE_LEFT,
        "turn right" to GAODE_RIGHT,
        "keep straight" to GAODE_STRAIGHT,
        "continue straight" to GAODE_STRAIGHT,
        "you have arrived" to GAODE_ARRIVE,
    )

    private fun fromTextFallback(lower: String): Int {
        // lower is already NBSP-normalized by fromInstructionText
        val norm = lower.replace(Regex("\\s+"), " ")
        for ((phrase, code) in RU_PHRASES) if (phrase in norm) return code
        for ((phrase, code) in EN_PHRASES) if (phrase in norm) return code
        for ((phrase, code) in WORD_BOUNDARY_PHRASES) {
            if (Regex("""(?:^|\s|[\p{Punct}])${Regex.escape(phrase)}(?:$|\s|[\p{Punct}])""").containsMatchIn(norm)) return code
        }
        return 0
    }
}
