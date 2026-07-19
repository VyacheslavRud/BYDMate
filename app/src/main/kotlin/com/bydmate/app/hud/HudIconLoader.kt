package com.bydmate.app.hud

import android.content.Context
import android.util.Log

/** Maneuver icon PNGs for the HUD frame (f8). Donor assets: 48 files named
 *  0x<gaode-hex>.png, 38x41 px, sent verbatim - the HUD firmware scales them.
 *  All 48 total ~100 KB, kept in memory to avoid asset IO on the push loop. */
object HudIconLoader {
    private const val TAG = "HudIconLoader"
    private const val MAX_ICON_BYTES = 64 * 1024
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Volatile private var icons: Map<Int, ByteArray> = emptyMap()

    @Synchronized
    fun init(context: Context) {
        if (icons.isNotEmpty()) return
        val names = runCatching { context.assets.list("navi").orEmpty().toList() }
            .onFailure { Log.e(TAG, "icon inventory failed: ${it.message}") }
            .getOrDefault(emptyList())
        icons = names
            .filter { it.startsWith("0x") && it.endsWith(".png") }
            .mapNotNull { name ->
                val code = name.removePrefix("0x").removeSuffix(".png").toIntOrNull(16)
                    ?: return@mapNotNull null
                runCatching {
                    val bytes = context.assets.open("navi/$name").use { it.readBytes() }
                    require(bytes.size in PNG_MAGIC.size..MAX_ICON_BYTES) {
                        "invalid size ${bytes.size}"
                    }
                    require(PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }) {
                        "invalid PNG signature"
                    }
                    code to bytes
                }.onFailure {
                    // One damaged optional asset must not discard every other maneuver icon.
                    Log.w(TAG, "skipping $name: ${it.message}")
                }.getOrNull()
            }
            .toMap()
        Log.i(TAG, "loaded ${icons.size} maneuver icons")
    }

    fun iconFor(gaode: Int): ByteArray? = if (gaode <= 0) null else icons[gaode]

    fun loadedCount(): Int = icons.size
}
