package com.bydmate.app.cluster

/**
 * Pure interpretation of the fixed cluster system probe.
 *
 * Scope is deliberately narrow. Everything here is derived from what standard Android APIs expose —
 * `DisplayManager`, `dumpsys display`, `dumpsys SurfaceFlinger` — so it can only decide whether a
 * Waze task can be projected onto an Android display. The physical instrument cluster may still be
 * driven by a native BYD compositor, a separate ECU, a system-owned Surface outside the public API,
 * or a vendor data protocol. None of those are observable from here, so no verdict in this file may
 * describe the instrument cluster itself as reachable or unreachable — only the Android path.
 *
 * Nothing here powers hardware, launches a task or touches the windshield HUD path.
 */

/** One `[label] exit=N` block of the probe, with the raw body that followed it. */
internal data class ClusterProbeSection(
    val label: String,
    val exitCode: Int,
    val body: String,
) {
    /** The command ran and produced something a parser may read. */
    val usable: Boolean get() = exitCode == 0 && body.isNotBlank() && body.trim() != "(empty)"
}

/** Facts extracted from one probe report. Null means "not established", never "zero". */
internal data class ClusterProbeFacts(
    /** Non-null only when a usable SurfaceFlinger section listed at least one HWC display. */
    val physicalDisplayCount: Int? = null,
    val displayNames: List<String> = emptyList(),
    val virtualDisplayOwners: List<String> = emptyList(),
    val clusterCandidateNames: List<String> = emptyList(),
    val centralFloatingCompositorPresent: Boolean = false,
    val autoContainerServicePresent: Boolean = false,
    /** Raw transport evidence only. Non-zero means the service returned an error-shaped int. */
    val containerReplyStatus: Int? = null,
    val displayManagerParsed: Boolean = false,
    val surfaceFlingerParsed: Boolean = false,
    val sectionExitCodes: Map<String, Int> = emptyMap(),
) {
    /** Both inventories were readable, so an absence in them is real rather than a gap. */
    val evidenceComplete: Boolean
        get() = displayManagerParsed && surfaceFlingerParsed && physicalDisplayCount != null
}

/**
 * What the Android display inventory says about projecting a task onto the instrument cluster.
 *
 * These names describe the Android path and nothing else. A native or proprietary cluster mechanism
 * is never evaluated here and is always reported separately as unverified.
 */
internal enum class ClusterAndroidProjectionVerdict {
    /** A probe section was missing, failed, empty or in an unrecognized format. */
    EVIDENCE_INCOMPLETE,

    /** A projection surface the cluster panel is known to composite is present. */
    PROJECTION_DISPLAY_PRESENT,

    /** More than one physical display exists, so the Android path is not ruled out. */
    SECONDARY_PHYSICAL_DISPLAY_PRESENT,

    /**
     * One physical display, and the only virtual display is the central floating-window
     * compositor. A task placed there renders on the centre screen, not on the cluster.
     */
    CENTRAL_FLOATING_COMPOSITOR_ONLY,

    /** One physical display and no projection surface visible at all. */
    NO_PROJECTION_DISPLAY_VISIBLE,
    ;

    /**
     * Null when the evidence does not decide it. False states only that a Waze task cannot be
     * placed on an Android display of the cluster — never that the cluster cannot render Waze.
     */
    val directAndroidTaskProjectionAvailable: Boolean?
        get() = when (this) {
            EVIDENCE_INCOMPLETE -> null
            PROJECTION_DISPLAY_PRESENT -> true
            SECONDARY_PHYSICAL_DISPLAY_PRESENT -> null
            CENTRAL_FLOATING_COMPOSITOR_ONLY, NO_PROJECTION_DISPLAY_VISIBLE -> false
        }
}

/**
 * Status of every cluster mechanism this laboratory cannot observe.
 *
 * A native BYD compositor, a separate cluster ECU, a system-owned Surface or a vendor route-data
 * protocol leave no trace in `DisplayManager` or `SurfaceFlinger`. The export states this
 * explicitly so a negative Android verdict is never read as "the feature is impossible".
 */
internal const val NATIVE_CLUSTER_PATH_STATUS = "UNVERIFIED_BY_THIS_LAB"

/** How the Android display inventory responded to the container command over the whole window. */
internal enum class ClusterContainerEffect {
    EVIDENCE_INCOMPLETE,
    NO_ANDROID_DISPLAY_CHANGE_OBSERVED,
    DISPLAY_APPEARED_IMMEDIATELY,
    DISPLAY_APPEARED_AFTER_DELAY,
    DISPLAY_APPEARED_AND_DISAPPEARED,
}

private val SECTION_HEADER = Regex("""\[([A-Za-z_]+)\]\s+exit=(-?\d+)""")
private val HWC_DISPLAY = Regex("""\(HWC display \d+\)""")
private val DISPLAY_DEVICE_NAME = Regex("""DisplayDeviceInfo\{"([^"]*)"""")
private val VIRTUAL_UNIQUE_ID = Regex("""uniqueId="virtual:([^,"]+),""")
private val REPLY_STATUS = Regex("""replyStatus=(-?\d+)""")

internal fun parseClusterProbeSections(report: String?): List<ClusterProbeSection> {
    if (report.isNullOrBlank()) return emptyList()
    val headers = SECTION_HEADER.findAll(report).toList()
    return headers.mapIndexed { index, match ->
        val bodyStart = match.range.last + 1
        val bodyEnd = headers.getOrNull(index + 1)?.range?.first ?: report.length
        ClusterProbeSection(
            label = match.groupValues[1],
            exitCode = match.groupValues[2].toIntOrNull() ?: -999,
            body = report.substring(bodyStart, bodyEnd).trim(),
        )
    }
}

internal fun parseClusterSystemProbe(report: String?): ClusterProbeFacts {
    if (report.isNullOrBlank()) return ClusterProbeFacts()
    val sections = parseClusterProbeSections(report)
    val displayManager = sections.firstOrNull { it.label == "display_manager" }
    val surfaceFlinger = sections.firstOrNull { it.label == "surface_flinger_displays" }

    val names = displayManager
        ?.takeIf(ClusterProbeSection::usable)
        ?.let { section -> DISPLAY_DEVICE_NAME.findAll(section.body).map { it.groupValues[1] }.toList() }
        .orEmpty()
    val displayManagerParsed = displayManager?.usable == true && names.isNotEmpty()

    // A usable section that yields no HWC line is an unrecognized format, not a car without
    // screens: the centre display physically exists on every supported vehicle. Reporting zero
    // here would let NO_PROJECTION_DISPLAY_VISIBLE be concluded from a parser gap.
    val hwcCount = surfaceFlinger
        ?.takeIf(ClusterProbeSection::usable)
        ?.let { HWC_DISPLAY.findAll(it.body).count() }
        ?.takeIf { it > 0 }

    return ClusterProbeFacts(
        physicalDisplayCount = hwcCount,
        displayNames = names,
        virtualDisplayOwners = displayManager
            ?.takeIf(ClusterProbeSection::usable)
            ?.let { section ->
                VIRTUAL_UNIQUE_ID.findAll(section.body).map { it.groupValues[1] }.distinct().toList()
            }
            .orEmpty(),
        clusterCandidateNames = names.filter(::isClusterProjectionDisplayName),
        centralFloatingCompositorPresent = names.any { it.contains("fission_bg", true) },
        autoContainerServicePresent = report.contains("auto_container:", ignoreCase = true) ||
            report.contains("IAutoContainer", ignoreCase = true),
        containerReplyStatus = REPLY_STATUS.find(report)?.groupValues?.get(1)?.toIntOrNull(),
        displayManagerParsed = displayManagerParsed,
        surfaceFlingerParsed = hwcCount != null,
        sectionExitCodes = sections.associate { it.label to it.exitCode },
    )
}

internal fun clusterAndroidProjectionVerdict(
    facts: ClusterProbeFacts,
): ClusterAndroidProjectionVerdict = when {
    // A present candidate is self-evident and does not need the SurfaceFlinger section.
    facts.displayManagerParsed && facts.clusterCandidateNames.isNotEmpty() ->
        ClusterAndroidProjectionVerdict.PROJECTION_DISPLAY_PRESENT
    !facts.evidenceComplete -> ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE
    checkNotNull(facts.physicalDisplayCount) > 1 ->
        ClusterAndroidProjectionVerdict.SECONDARY_PHYSICAL_DISPLAY_PRESENT
    facts.centralFloatingCompositorPresent ->
        ClusterAndroidProjectionVerdict.CENTRAL_FLOATING_COMPOSITOR_ONLY
    else -> ClusterAndroidProjectionVerdict.NO_PROJECTION_DISPLAY_VISIBLE
}

/**
 * Compares the three C07 snapshots.
 *
 * The immediate snapshot alone cannot distinguish "nothing happened" from "the display took longer
 * than one dumpsys to appear", which is why the final snapshot is mandatory: without it the answer
 * is [ClusterContainerEffect.EVIDENCE_INCOMPLETE] rather than "no change".
 */
internal fun clusterContainerEffect(
    before: ClusterProbeFacts,
    immediate: ClusterProbeFacts,
    afterWatch: ClusterProbeFacts,
): ClusterContainerEffect {
    if (!before.displayManagerParsed ||
        !immediate.displayManagerParsed ||
        !afterWatch.displayManagerParsed
    ) {
        return ClusterContainerEffect.EVIDENCE_INCOMPLETE
    }
    val baseline = before.displayNames.toSet()
    val newImmediately = immediate.displayNames.any { it !in baseline }
    val newAfterWatch = afterWatch.displayNames.any { it !in baseline }
    return when {
        newImmediately && newAfterWatch -> ClusterContainerEffect.DISPLAY_APPEARED_IMMEDIATELY
        newImmediately -> ClusterContainerEffect.DISPLAY_APPEARED_AND_DISAPPEARED
        newAfterWatch -> ClusterContainerEffect.DISPLAY_APPEARED_AFTER_DELAY
        else -> ClusterContainerEffect.NO_ANDROID_DISPLAY_CHANGE_OBSERVED
    }
}

/** One export line another developer can read without parsing raw dumpsys chunks. */
internal fun clusterPlatformVerdictLine(
    stage: String,
    report: String?,
    capturedAtMs: Long? = null,
    captureDurationMs: Long? = null,
    facts: ClusterProbeFacts = parseClusterSystemProbe(report),
    verdict: ClusterAndroidProjectionVerdict = clusterAndroidProjectionVerdict(facts),
): String = buildString {
    append("cluster_platform stage=$stage androidVerdict=$verdict ")
    append(
        "directAndroidTaskProjectionAvailable=" +
            "${verdict.directAndroidTaskProjectionAvailable ?: "unknown"} ",
    )
    append("nativeClusterPath=$NATIVE_CLUSTER_PATH_STATUS ")
    append("capturedAtMs=${capturedAtMs ?: "unknown"} ")
    append("captureDurationMs=${captureDurationMs ?: "unknown"} ")
    append("evidenceComplete=${facts.evidenceComplete} ")
    append("displayManagerParsed=${facts.displayManagerParsed} ")
    append("surfaceFlingerParsed=${facts.surfaceFlingerParsed} ")
    append("physicalDisplays=${facts.physicalDisplayCount ?: "unknown"} ")
    append("displayNames=${facts.displayNames.joinToString("|").ifEmpty { "none" }} ")
    append("virtualOwners=${facts.virtualDisplayOwners.joinToString("|").ifEmpty { "none" }} ")
    append("clusterCandidates=${facts.clusterCandidateNames.joinToString("|").ifEmpty { "none" }} ")
    append("centralFloatingCompositor=${facts.centralFloatingCompositorPresent} ")
    append("autoContainerService=${facts.autoContainerServicePresent} ")
    // Raw transport value only. Without a published AIDL contract a non-zero int says the service
    // returned an error shape, not that the native cluster kept its previous state.
    append("containerReplyStatus=${facts.containerReplyStatus ?: "unknown"} ")
    append("containerReplyMeaning=${containerReplyMeaning(facts.containerReplyStatus)} ")
    append("sectionExits=${facts.sectionExitCodes.entries.joinToString(",") { "${it.key}=${it.value}" }
        .ifEmpty { "none" }}")
}

internal fun containerReplyMeaning(status: Int?): String = when {
    status == null -> "NO_STATUS_DECODED"
    status == 0 -> "ZERO_REPLY_SERVICE_LEVEL_OK"
    else -> "NON_ZERO_SERVICE_REPLY_HARDWARE_UNVERIFIED"
}

/** Terminal C07 line: the three-snapshot comparison plus the limits of what it can mean. */
internal fun clusterContainerEffectLine(
    before: ClusterProbeFacts,
    immediate: ClusterProbeFacts,
    afterWatch: ClusterProbeFacts,
    replyStatus: Int?,
    visualObservationPending: Boolean = true,
): String = buildString {
    val effect = clusterContainerEffect(before, immediate, afterWatch)
    append("cluster_container_effect effect=$effect ")
    append("beforeParsed=${before.displayManagerParsed} ")
    append("immediateParsed=${immediate.displayManagerParsed} ")
    append("finalParsed=${afterWatch.displayManagerParsed} ")
    append("beforeDisplays=${before.displayNames.size} ")
    append("immediateDisplays=${immediate.displayNames.size} ")
    append("finalDisplays=${afterWatch.displayNames.size} ")
    append("containerReplyStatus=${replyStatus ?: "unknown"} ")
    append("containerReplyMeaning=${containerReplyMeaning(replyStatus)} ")
    // Kept as a separate axis on purpose: an earlier C07 run recorded a user observation of
    // VISIBLE while the reply was -1 and the Android inventory was unchanged. Neither axis may
    // overrule the other, and neither decides the native cluster state.
    append("visualObservation=${if (visualObservationPending) "PENDING_USER_REPORT" else "RECORDED"} ")
    append("nativeClusterPath=$NATIVE_CLUSTER_PATH_STATUS")
}
