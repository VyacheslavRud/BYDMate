package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.ClusterProjectionPreset
import com.bydmate.app.data.vehicle.VehicleProfile
import com.bydmate.app.navigation.WazeNavigation

/** Default projection target. The actual package is user-selectable in settings (KEY_TARGET_PACKAGE). */
const val NAVI_PACKAGE = WazeNavigation.PACKAGE_NAME

/** Cluster projection state (OFF / FULLSCREEN). */
enum class ClusterMode { OFF, FULLSCREEN }

/** Fine-grained state retained for the diagnostics screen even after a failed attempt falls back
 * to [ClusterMode.OFF]. */
enum class ClusterProjectionPhase { OFF, STARTING, WAITING_FOR_DISPLAY, ACTIVE, FAILED }

data class ClusterDisplayDiagnostic(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val state: Int,
    val isClusterCandidate: Boolean,
)

data class ClusterProjectionDiagnosticState(
    val phase: ClusterProjectionPhase = ClusterProjectionPhase.OFF,
    val attemptStartedAtMs: Long? = null,
    val attemptFinishedAtMs: Long? = null,
    val displaySearchElapsedMs: Long? = null,
    val selectedDisplay: ClusterDisplayDiagnostic? = null,
    val visibleDisplays: List<ClusterDisplayDiagnostic> = emptyList(),
    val monitoredDisplayId: Int? = null,
    val lastDisplayEvent: String? = null,
    val lastDisplayEventAtMs: Long? = null,
    val lastFailure: String? = null,
    val lastSuccessAtMs: Long? = null,
)

/** Where Navi renders on the cluster overlay. VirtualDisplay size == SurfaceView size (1:1). */
data class ClusterGeometry(val width: Int, val height: Int, val xOffset: Int, val yOffset: Int)

/**
 * Window size bounds (% of the cluster panel), shared by the settings sliders and [geometryFor].
 * The minimum must stay below the native mini-window width: on Sea Lion 07 the mini zone is roughly
 * a third of the 1920 px panel, so the old 50% floor made the window wider than the zone and the
 * panel cut off Navi's left edge (where the maneuver/ETA panels live) (#48).
 */
const val MIN_PROJECTION_PCT = 20
const val MAX_PROJECTION_PCT = 100

/**
 * Window position bounds (% of the free space left by a sub-100% window), shared by the position
 * sliders and [geometryFor]. 0 = pinned to the left/top edge, 50 = centered (legacy behaviour),
 * 100 = pinned to the right/bottom edge. Lets the user slide the rendered map into the visible
 * region of the native mini-cluster window (#48).
 */
const val MIN_OFFSET_PCT = 0
const val MAX_OFFSET_PCT = 100
const val CENTER_OFFSET_PCT = 50

/**
 * Content scale for the projected app, tuning what it renders INSIDE the window (how much map
 * fits) rather than where/how big the window is. Orthogonal to [geometryFor].
 *
 * [MIN_SCALE_PCT]..[MAX_SCALE_PCT] set the VirtualDisplay density as a % of the cluster's native dpi:
 * below 100 the app sees a "roomier" logical screen (UI smaller, more map), 100 = native (current
 * behaviour), above 100 = bigger UI / less map.
 */
const val MIN_SCALE_PCT = 50
const val MAX_SCALE_PCT = 150
const val DEFAULT_SCALE_PCT = 100

internal data class ClusterDefaultsSnapshot(
    val migrationDone: Boolean,
    val widthPct: Int?,
    val heightPct: Int?,
    val offsetXPct: Int?,
    val offsetYPct: Int?,
    val scalePct: Int?,
    val autoContainer: Boolean?,
)

internal data class ClusterDefaultsMigration(
    /** Complete geometry to persist, or null when the existing complete custom tuple is safe. */
    val geometryToPersist: ClusterProjectionPreset?,
    /** null means an explicit stored choice must be preserved. */
    val autoContainer: Boolean?,
    val markDone: Boolean,
)

/**
 * Replaces an effective old full-screen tuple with the Sea Lion preset. A partial custom tuple is
 * completed with the legacy runtime defaults and persisted: otherwise its absent fields would
 * silently start using the new Sea Lion fallbacks after the update and change the user's actual
 * geometry. A complete custom tuple needs no write. Auto-container is switched off only when the
 * old build never persisted an explicit user choice.
 */
internal fun planClusterDefaultsMigration(
    snapshot: ClusterDefaultsSnapshot,
    preset: ClusterProjectionPreset = VehicleProfile.CURRENT.clusterProjectionPreset,
): ClusterDefaultsMigration {
    if (snapshot.migrationDone) {
        return ClusterDefaultsMigration(null, null, markDone = false)
    }

    val storedGeometry = listOf(
        snapshot.widthPct, snapshot.heightPct, snapshot.offsetXPct,
        snapshot.offsetYPct, snapshot.scalePct,
    )
    val effectiveLegacyGeometry = ClusterProjectionPreset(
        widthPct = snapshot.widthPct ?: MAX_PROJECTION_PCT,
        heightPct = snapshot.heightPct ?: MAX_PROJECTION_PCT,
        offsetXPct = snapshot.offsetXPct ?: CENTER_OFFSET_PCT,
        offsetYPct = snapshot.offsetYPct ?: CENTER_OFFSET_PCT,
        scalePct = snapshot.scalePct ?: DEFAULT_SCALE_PCT,
    )
    val oldFullScreenGeometry = ClusterProjectionPreset(
        widthPct = MAX_PROJECTION_PCT,
        heightPct = MAX_PROJECTION_PCT,
        offsetXPct = CENTER_OFFSET_PCT,
        offsetYPct = CENTER_OFFSET_PCT,
        scalePct = DEFAULT_SCALE_PCT,
    )
    val geometryToPersist = when {
        effectiveLegacyGeometry == oldFullScreenGeometry -> preset
        storedGeometry.any { it == null } -> effectiveLegacyGeometry
        else -> null
    }
    return ClusterDefaultsMigration(
        geometryToPersist = geometryToPersist,
        autoContainer = false.takeIf { snapshot.autoContainer == null },
        markDone = true,
    )
}

/**
 * Geometry for [mode] on a [clusterW] x [clusterH] cluster. OFF → null. FULLSCREEN → a
 * rectangle scaled to [widthPct]/[heightPct] (% of the panel, each coerced to
 * [MIN_PROJECTION_PCT]..[MAX_PROJECTION_PCT]) and positioned by [offsetXPct]/[offsetYPct] within
 * the free space (% coerced to [MIN_OFFSET_PCT]..[MAX_OFFSET_PCT], 50 = centered). 100/100 size =
 * the whole cluster (no free space, so position has no effect). Smaller values shrink Navi's render
 * target, so the native cluster shows through the translucent overlay around the window.
 */
fun geometryFor(
    mode: ClusterMode,
    clusterW: Int,
    clusterH: Int,
    widthPct: Int = MAX_PROJECTION_PCT,
    heightPct: Int = MAX_PROJECTION_PCT,
    offsetXPct: Int = CENTER_OFFSET_PCT,
    offsetYPct: Int = CENTER_OFFSET_PCT,
): ClusterGeometry? = when (mode) {
    ClusterMode.OFF -> null
    ClusterMode.FULLSCREEN -> {
        val w = clusterW * widthPct.coerceIn(MIN_PROJECTION_PCT, MAX_PROJECTION_PCT) / 100
        val h = clusterH * heightPct.coerceIn(MIN_PROJECTION_PCT, MAX_PROJECTION_PCT) / 100
        val x = (clusterW - w) * offsetXPct.coerceIn(MIN_OFFSET_PCT, MAX_OFFSET_PCT) / 100
        val y = (clusterH - h) * offsetYPct.coerceIn(MIN_OFFSET_PCT, MAX_OFFSET_PCT) / 100
        ClusterGeometry(w, h, x, y)
    }
}

/**
 * VirtualDisplay buffer size + logical density for a window. [bufferWidth]/[bufferHeight] are the
 * pixels the projected app renders into (== the window). [densityDpi] is the logical density the
 * app sees.
 */
data class RenderPlan(val bufferWidth: Int, val bufferHeight: Int, val densityDpi: Int)

/**
 * Render plan for [geo] given the content scale. [clusterDensityDpi] is the cluster panel's
 * native density; [scalePct] (coerced to [MIN_SCALE_PCT]..[MAX_SCALE_PCT]) scales it. The default
 * reproduces native rendering: buffer == window, density == cluster dpi.
 */
fun renderPlanFor(
    geo: ClusterGeometry,
    clusterDensityDpi: Int,
    scalePct: Int = DEFAULT_SCALE_PCT,
): RenderPlan {
    val dpi = clusterDensityDpi * scalePct.coerceIn(MIN_SCALE_PCT, MAX_SCALE_PCT) / 100
    return RenderPlan(geo.width, geo.height, dpi)
}

/**
 * Freeform window bounds on the cluster display for [geo]: [left, top, right, bottom].
 * Direct projection reuses the exact overlay-window geometry, so the user's per-car
 * width/height/offset presets carry over unchanged from the VirtualDisplay pipeline.
 */
fun freeformBounds(geo: ClusterGeometry): IntArray =
    intArrayOf(geo.xOffset, geo.yOffset, geo.xOffset + geo.width, geo.yOffset + geo.height)

/** The other projection state — drives the steering-wheel toggle (приборка ↔ центр). */
fun nextMode(current: ClusterMode): ClusterMode =
    if (current == ClusterMode.FULLSCREEN) ClusterMode.OFF else ClusterMode.FULLSCREEN

/**
 * True when a compositor-on marker persisted by a PRIOR process should be recovered at service
 * start: the car shut down mid-projection, the off sequence (18 -> pause -> 0) never ran, and the
 * compositor woke up in projection mode with nobody drawing — a black cluster. A live projection
 * in THIS process ([mode] != OFF) owns the compositor and must not be powered down under it. The
 * surviving marker is proof that BYDMate powered the compositor, so recovery remains our
 * responsibility even if auto-container is now disabled or its default changed between versions.
 */
fun shouldRecoverCompositor(markerSet: Boolean, mode: ClusterMode): Boolean =
    markerSet && mode == ClusterMode.OFF

/** Teardown follows ownership evidence, not the current setting value. The user can disable the
 * automatic compositor toggle while projection is already active; a surviving marker still means
 * BYDMate must send the matching power-down. */
fun shouldPowerDownCompositor(markerSet: Boolean): Boolean = markerSet

/** Runtime display-loss guard used by DisplayListener. Unrelated display hotplug events must not
 * tear down a healthy cluster projection. */
fun isActiveProjectionDisplayRemoved(
    mode: ClusterMode,
    monitoredDisplayId: Int,
    removedDisplayId: Int,
): Boolean = mode == ClusterMode.FULLSCREEN &&
    monitoredDisplayId >= 0 && monitoredDisplayId == removedDisplayId

/** Merge the legacy single daemon-display marker with the crash-safe multi-display set. Invalid
 * preference entries are ignored so one damaged value cannot block all orphan cleanup. */
internal fun persistedVirtualDisplayIds(lastId: Int, storedIds: Set<String>): Set<Int> = buildSet {
    storedIds.mapNotNullTo(this) { it.toIntOrNull()?.takeIf { id -> id >= 0 } }
    lastId.takeIf { it >= 0 }?.let(::add)
}

/** Direct-task crash recovery fires only when a marker survives AND no projection is live. */
fun shouldRecoverDirectTask(markerDisplayId: Int, mode: ClusterMode): Boolean =
    markerDisplayId != -1 && mode == ClusterMode.OFF

/**
 * The display's logical density is trustworthy as the native base only when no direct-mode
 * density override of ours can be active: no live member AND no surviving crash marker.
 * A surviving marker means a prior override may still be applied to the display —
 * absorbing it would compound the scale (320 -> 230 -> 161 -> ...).
 */
fun shouldAbsorbDisplayDensity(liveDirectDisplayId: Int, markerDisplayId: Int, metricsDpi: Int): Boolean =
    liveDirectDisplayId == -1 && markerDisplayId == -1 && metricsDpi > 0

/**
 * The direct-mode crash marker may be cleared only when the density reset was CONFIRMED and
 * the stranded task was verifiably reclaimed — or is gone (nothing to reclaim). A false from
 * mode/move keeps the marker so the next service start retries.
 */
fun shouldClearDirectMarker(resetOk: Boolean, taskFound: Boolean, modeOk: Boolean, moveOk: Boolean): Boolean =
    resetOk && (!taskFound || (modeOk && moveOk))
