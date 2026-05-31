package com.bydmate.app.cluster

import android.view.KeyEvent

/** Package of the projection target (Yandex Navigator) on the DiLink head unit. */
const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

/** Right steering-wheel star, short-press keycode (validated on-car, Phase 0). Long-press = 352. */
const val RIGHT_STAR_KEYCODE = 351

/**
 * Starting width of the MINI projection card on the cluster (Q3 — empirical).
 * Tune on-car; the unit test pins the derivation, not the "right" pixel value.
 */
const val MINI_WIDTH = 640

/** Cluster projection state. Cycle advances OFF → MINI → FULLSCREEN → OFF (user's flow). */
enum class ClusterMode {
    OFF, MINI, FULLSCREEN;

    fun next(): ClusterMode = when (this) {
        OFF -> MINI
        MINI -> FULLSCREEN
        FULLSCREEN -> OFF
    }
}

/** Where Navi renders on the cluster overlay. VirtualDisplay size == SurfaceView size (1:1). */
data class ClusterGeometry(val width: Int, val height: Int, val xOffset: Int, val yOffset: Int)

/**
 * Geometry for [mode] on a [clusterW] x [clusterH] cluster. OFF → null.
 * FULLSCREEN = whole cluster. MINI = a [MINI_WIDTH]-wide, full-height card centered horizontally,
 * with the width clamped to the cluster.
 */
fun geometryFor(mode: ClusterMode, clusterW: Int, clusterH: Int): ClusterGeometry? = when (mode) {
    ClusterMode.OFF -> null
    ClusterMode.FULLSCREEN -> ClusterGeometry(clusterW, clusterH, 0, 0)
    ClusterMode.MINI -> {
        val w = MINI_WIDTH.coerceAtMost(clusterW)
        ClusterGeometry(width = w, height = clusterH, xOffset = ((clusterW - w) / 2).coerceAtLeast(0), yOffset = 0)
    }
}

/** True iff this key event is a short press of the mapped button (our action; we consume it). */
fun isClusterCycleTrigger(keyCode: Int, action: Int, isLongPress: Boolean, repeatCount: Int): Boolean =
    keyCode == RIGHT_STAR_KEYCODE && action == KeyEvent.ACTION_DOWN && !isLongPress && repeatCount == 0

/** True iff this keycode is the one we bind. Long-press of it is passed through to the native menu. */
fun isMappedButton(keyCode: Int): Boolean = keyCode == RIGHT_STAR_KEYCODE
