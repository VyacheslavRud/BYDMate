package com.bydmate.app.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Non-interactive, automatically removed calibration grid for C03. */
@SuppressLint("ViewConstructor") // programmatic-only view needs immutable scenario metadata
internal class ClusterLabPatternView(
    context: Context,
    private val display: ClusterLabDisplaySnapshot,
    private val expectedProjection: ClusterGeometry,
) : View(context) {
    private val density = resources.displayMetrics.density.coerceAtLeast(1f)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val labelBackground = Paint().apply { color = Color.argb(210, 0, 0, 0) }
    private val dim = Paint().apply { color = Color.argb(96, 0, 0, 0) }
    private val expectedRect = RectF(
        expectedProjection.xOffset.toFloat(),
        expectedProjection.yOffset.toFloat(),
        (expectedProjection.xOffset + expectedProjection.width).toFloat(),
        (expectedProjection.yOffset + expectedProjection.height).toFloat(),
    )

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)

        line.color = Color.rgb(0, 230, 255)
        canvas.drawRect(2f, 2f, width - 2f, height - 2f, line)
        for (part in 1..3) {
            val x = width * part / 4f
            val y = height * part / 4f
            canvas.drawLine(x, 0f, x, height.toFloat(), line)
            canvas.drawLine(0f, y, width.toFloat(), y, line)
        }
        canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), line)
        canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), line)

        line.color = Color.rgb(255, 214, 0)
        line.strokeWidth = 4f * density
        canvas.drawRect(expectedRect, line)
        line.strokeWidth = 2f * density

        val pad = 10f * density
        val lineHeight = 24f * density
        canvas.drawRect(0f, 0f, width.toFloat(), lineHeight * 3f + pad, labelBackground)
        canvas.drawText("BYDMate Instrument Cluster Lab C03", pad, lineHeight, text)
        canvas.drawText(
            "display=${display.id} ${display.name} ${display.widthPx}x${display.heightPx}@${display.densityDpi}",
            pad,
            lineHeight * 2f,
            text,
        )
        canvas.drawText(
            "yellow=expected projection ${expectedProjection.width}x${expectedProjection.height}" +
                "+${expectedProjection.xOffset}+${expectedProjection.yOffset}",
            pad,
            lineHeight * 3f,
            text,
        )

        drawCorner(canvas, "TL", pad, lineHeight * 4f)
        drawCorner(canvas, "TR", width - text.measureText("TR") - pad, lineHeight * 4f)
        drawCorner(canvas, "BL", pad, height - pad)
        drawCorner(canvas, "BR", width - text.measureText("BR") - pad, height - pad)
    }

    private fun drawCorner(canvas: Canvas, value: String, x: Float, baseline: Float) {
        canvas.drawText(value, x, baseline, text)
    }
}
