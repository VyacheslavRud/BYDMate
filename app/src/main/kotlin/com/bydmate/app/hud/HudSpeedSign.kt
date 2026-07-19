package com.bydmate.app.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.Log
import java.io.ByteArrayOutputStream

/** Speed-limit sign PNG for HUD slot f7 (port of donor buildSpeedLimitPng):
 *  96x96, red ring, white core, black bold number. Cached per limit value -
 *  the push loop asks every 300 ms while the limit changes about once a minute. */
object HudSpeedSign {

    private const val TAG = "HudSpeedSign"

    @Volatile private var cachedLimit = -1
    @Volatile private var cachedPng: ByteArray? = null

    @Synchronized
    fun render(limit: Int): ByteArray? {
        if (limit <= 0) return null
        if (limit == cachedLimit) return cachedPng
        val png = runCatching { draw(limit) }
            .onFailure { Log.w(TAG, "speed-sign render failed: ${it.message}") }
            .getOrNull()
            ?: return null
        cachedLimit = limit
        cachedPng = png
        return png
    }

    private fun draw(limit: Int): ByteArray {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val cx = size / 2f
            val cy = size / 2f
            val red = Paint().apply { color = Color.rgb(0xD0, 0, 0); style = Paint.Style.FILL }
            canvas.drawCircle(cx, cy, size * 0.48f, red)
            val white = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
            canvas.drawCircle(cx, cy, size * 0.34f, white)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                textSize = if (limit >= 100) size * 0.36f else size * 0.44f
            }
            val fm = text.fontMetrics
            canvas.drawText(limit.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, text)
            return ByteArrayOutputStream().use { out ->
                check(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Bitmap.compress returned false"
                }
                out.toByteArray()
            }
        } finally {
            // Bitmap pixel memory is native on the DiLink Android build. A new sign can be
            // rendered after every speed-limit change, so relying on a future GC steadily grows
            // the process while driving.
            bmp.recycle()
        }
    }
}
