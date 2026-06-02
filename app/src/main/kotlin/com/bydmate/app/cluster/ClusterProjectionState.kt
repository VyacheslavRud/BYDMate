package com.bydmate.app.cluster

import android.view.KeyEvent

/** Package of the projection target (Yandex Navigator) on the DiLink head unit. */
const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

/** Right steering-wheel star, short-press keycode (validated on-car, Phase 0). */
const val RIGHT_STAR_KEYCODE = 351

/** Right steering-wheel star, long-press (удержание) keycode (validated on-car, Phase 0). */
const val RIGHT_STAR_LONG_KEYCODE = 352

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

/**
 * True iff this keycode is one we bind to the right star — short [RIGHT_STAR_KEYCODE] or its
 * separate long-press keycode [RIGHT_STAR_LONG_KEYCODE]. Both are passed through to the native
 * action-selection menu (the short press is consumed only when it is a cycle trigger). Including
 * 352 here is what keeps the long-press menu intact even when the diagnostic consume-toggle is on:
 * otherwise 352 would fall through to the consume fallback and be swallowed.
 */
fun isMappedButton(keyCode: Int): Boolean =
    keyCode == RIGHT_STAR_KEYCODE || keyCode == RIGHT_STAR_LONG_KEYCODE
