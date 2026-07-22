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
    val services = sections.firstOrNull { it.label == "services" }

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
        // The schema header documents the recovered AIDL contract even when the live service is
        // absent. Only a successfully captured `service list` section may prove presence.
        autoContainerServicePresent = services
            ?.takeIf(ClusterProbeSection::usable)
            ?.body
            ?.contains("auto_container:", ignoreCase = true) == true,
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

/**
 * Vendor-stack inventory, for the question the Android display sections cannot answer.
 *
 * C07 established that the container command produces a visible reaction on the physical cluster
 * while the Android display inventory does not change at all. Whatever renders there is therefore
 * a native path, and the only way to narrow it down without unsafe Binder probing is to see which
 * vendor packages and services exist and which of them change state around the command.
 */
internal data class NativeClusterFacts(
    /** Every list is de-duplicated and sorted: `dumpsys` line order is not stable evidence. */
    val vendorServices: List<String> = emptyList(),
    val navClusterPackages: List<String> = emptyList(),
    val runningNavServices: List<String> = emptyList(),
    val foregroundActivities: List<String> = emptyList(),
    val someIpGatewayPresent: Boolean = false,
    val autoContainerNativePresent: Boolean = false,
    /** At least one schema 2+ vendor section header was present, whatever its exit code. */
    val vendorSectionsPresent: Boolean = false,
    val vendorServicesParsed: Boolean = false,
    val navPackagesParsed: Boolean = false,
    val runningNavServicesParsed: Boolean = false,
    val foregroundParsed: Boolean = false,
) {
    /** Every vendor section this report is expected to carry ran successfully. */
    val sectionsParsed: Boolean
        get() = vendorSectionsPresent && vendorServicesParsed && navPackagesParsed &&
            runningNavServicesParsed && foregroundParsed

    /**
     * Only the two sections [clusterNativeDeltaLine] actually compares. A delta must not be
     * blocked by an unrelated failed section, and must never be concluded without these two.
     */
    val deltaInputsParsed: Boolean
        get() = runningNavServicesParsed && foregroundParsed

    fun parsedBreakdown(): String = "vendorServices=$vendorServicesParsed," +
        "navPackages=$navPackagesParsed,runningNavServices=$runningNavServicesParsed," +
        "foreground=$foregroundParsed"
}

private val SERVICE_LINE = Regex("""\d+\s+([A-Za-z0-9_.]+):""")
private val PACKAGE_LINE = Regex("""package:([A-Za-z0-9_.]+)""")
private val SERVICE_RECORD = Regex("""ServiceRecord\{[^}]*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")
private val COMPONENT = Regex("""([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")

/** The SOME/IP gateway the working windshield HUD binds to. Never probed here, only counted. */
internal const val SOMEIP_GATEWAY_PACKAGE = "com.ts.car.someip.service"

private val VENDOR_SECTION_LABELS = listOf(
    "vendor_services_wide",
    "nav_cluster_packages",
    "nav_cluster_running_services",
    "foreground_activities",
)

internal fun parseNativeClusterFacts(report: String?): NativeClusterFacts {
    if (report.isNullOrBlank()) return NativeClusterFacts()
    val sections = parseClusterProbeSections(report)
    fun section(label: String): ClusterProbeSection? = sections.firstOrNull { it.label == label }

    /**
     * These sections are `... | grep ... | head -n N` pipelines, so a no-match run still exits 0
     * with no output. An empty body is therefore a real observation ("nothing matched"), unlike
     * the display sections where empty output means the dump itself failed. Only a non-zero exit
     * or a missing header counts as not parsed.
     */
    fun parsed(label: String): Boolean = section(label)?.exitCode == 0

    fun <T> values(label: String, extract: (String) -> Sequence<T>): List<T> =
        section(label)
            ?.takeIf { it.exitCode == 0 }
            ?.takeIf(ClusterProbeSection::usable)
            ?.let { extract(it.body).distinct().toList() }
            .orEmpty()

    // Sorted on purpose: `dumpsys` may reorder its lines between two runs, and an ordering
    // difference is not a vendor state change.
    val vendorServices = values("vendor_services_wide") { body ->
        SERVICE_LINE.findAll(body).map { it.groupValues[1] }
    }.sorted()
    val packages = values("nav_cluster_packages") { body ->
        PACKAGE_LINE.findAll(body).map { it.groupValues[1] }
    }.sorted()
    val runningServices = values("nav_cluster_running_services") { body ->
        SERVICE_RECORD.findAll(body).map { "${it.groupValues[1]}/${it.groupValues[2]}" }
    }.sorted()
    val foreground = values("foreground_activities") { body ->
        COMPONENT.findAll(body).map { it.value }
    }.sorted().take(12)

    return NativeClusterFacts(
        vendorServices = vendorServices,
        navClusterPackages = packages,
        runningNavServices = runningServices,
        foregroundActivities = foreground,
        someIpGatewayPresent = report.contains(SOMEIP_GATEWAY_PACKAGE, ignoreCase = true),
        autoContainerNativePresent = report.contains("AutoContainerNative", ignoreCase = true),
        // schema=2 is the first probe revision that carries the vendor sections at all.
        vendorSectionsPresent = VENDOR_SECTION_LABELS.any { section(it) != null },
        vendorServicesParsed = parsed("vendor_services_wide"),
        navPackagesParsed = parsed("nav_cluster_packages"),
        runningNavServicesParsed = parsed("nav_cluster_running_services"),
        foregroundParsed = parsed("foreground_activities"),
    )
}

internal fun clusterNativePathLine(stage: String, report: String?): String {
    val facts = parseNativeClusterFacts(report)
    return buildString {
        append("cluster_native_path stage=$stage sectionsParsed=${facts.sectionsParsed} ")
        append("sectionStatus=${facts.parsedBreakdown()} ")
        append("someIpGateway=${facts.someIpGatewayPresent} ")
        append("autoContainerNative=${facts.autoContainerNativePresent} ")
        append("vendorServices=${facts.vendorServices.joinToString("|").ifEmpty { "none" }} ")
        append("navClusterPackages=${facts.navClusterPackages.joinToString("|").ifEmpty { "none" }} ")
        append("runningNavServices=${facts.runningNavServices.joinToString("|").ifEmpty { "none" }} ")
        append("foreground=${facts.foregroundActivities.joinToString("|").ifEmpty { "none" }}")
    }
}

/**
 * What changed in the vendor stack across the container command.
 *
 * This is the one comparison that can point at the native renderer: the driver saw the cluster
 * react, so something on this list is expected to differ between the before and final snapshots.
 * An empty delta with a confirmed visual reaction means the renderer lives below the Android
 * framework entirely (a separate ECU or a system process invisible to these dumps).
 */
internal fun clusterNativeDeltaLine(before: String?, afterWatch: String?): String {
    val b = parseNativeClusterFacts(before)
    val a = parseNativeClusterFacts(afterWatch)
    // Gated on the two sections this delta reads, not on the whole vendor block: an unrelated
    // failed section must not hide a real change, and a failure in either of these two must never
    // be reported as "no change".
    if (!b.deltaInputsParsed || !a.deltaInputsParsed) {
        return "cluster_native_delta status=EVIDENCE_INCOMPLETE " +
            "beforeParsed=${b.deltaInputsParsed} finalParsed=${a.deltaInputsParsed} " +
            "beforeSections=${b.parsedBreakdown()} finalSections=${a.parsedBreakdown()}"
    }
    val startedServices = a.runningNavServices - b.runningNavServices.toSet()
    val stoppedServices = b.runningNavServices - a.runningNavServices.toSet()
    // Set comparison: the same components in a different dump order are not a change.
    val foregroundChanged = a.foregroundActivities.toSet() != b.foregroundActivities.toSet()
    val anyChange = startedServices.isNotEmpty() || stoppedServices.isNotEmpty() || foregroundChanged
    return buildString {
        append("cluster_native_delta status=")
        append(if (anyChange) "VENDOR_STATE_CHANGED" else "NO_VENDOR_STATE_CHANGE_OBSERVED")
        append(" startedServices=${startedServices.joinToString("|").ifEmpty { "none" }}")
        append(" stoppedServices=${stoppedServices.joinToString("|").ifEmpty { "none" }}")
        append(" foregroundChanged=$foregroundChanged")
        append(" beforeForeground=${b.foregroundActivities.joinToString("|").ifEmpty { "none" }}")
        append(" finalForeground=${a.foregroundActivities.joinToString("|").ifEmpty { "none" }}")
        // Even a completely empty delta cannot rule the native path out; it only moves it below
        // what these dumps can see.
        append(" nativeClusterPath=$NATIVE_CLUSTER_PATH_STATUS")
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
    // Transaction 2 is confirmed as IAutoContainer.sendInfo. Its return value is still only a
    // service-level result, not proof that the native cluster kept its previous visual state.
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
