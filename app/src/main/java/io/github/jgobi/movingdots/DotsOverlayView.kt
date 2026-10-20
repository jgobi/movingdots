package io.github.jgobi.movingdots

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a static ring of dots in the screen margins, leaving the centre free for content.
 *
 * The pattern is only redrawn when size or settings change. Motion is applied by setting
 * translationX/Y, which is a RenderNode property update: the display list isn't re-recorded,
 * the RenderThread just recomposites.
 */
class DotsOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private var radius = 4f * density
    private var perSide = 8
    private var drawOutline = true
    private var topBottom = false
    private val inset = INSET_DP * density

    fun applyPrefs(p: Prefs) {
        radius = p.dotSizeDp * density
        perSide = p.dotsPerSide
        topBottom = p.topBottomDots
        val alpha = (p.opacity.coerceIn(0, 100) * 255 / 100)
        drawOutline = p.colorPreset == 0
        fill.color = when (p.colorPreset) {
            1 -> Color.WHITE
            2 -> Color.BLACK
            3 -> Color.rgb(0x3D, 0x8B, 0xFF)
            else -> Color.rgb(0x20, 0x20, 0x20)
        }
        outline.color = Color.WHITE
        fill.alpha = alpha
        outline.alpha = alpha
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // A frame of evenly spaced dots: [perSide] dots along the long edge (corners included), and
        // the short edge gets as many as fit at the same spacing.
        val left = inset
        val top = inset
        val iw = w - 2 * inset
        val ih = h - 2 * inset
        if (iw <= 0f || ih <= 0f) return
        val spacing = max(iw, ih) / (max(2, perSide) - 1)
        val nx = max(2, (iw / spacing).roundToInt() + 1)
        val ny = max(2, (ih / spacing).roundToInt() + 1)
        // Left and right columns, corners included.
        for (j in 0 until ny) {
            val y = top + ih * j / (ny - 1)
            dot(canvas, left, y)
            dot(canvas, left + iw, y)
        }
        // Top and bottom rows between the corners, if enabled.
        if (topBottom) for (i in 1 until nx - 1) {
            val x = left + iw * i / (nx - 1)
            dot(canvas, x, top)
            dot(canvas, x, top + ih)
        }
    }

    private fun dot(c: Canvas, x: Float, y: Float) {
        c.drawCircle(x, y, radius, fill)
        if (drawOutline) c.drawCircle(x, y, radius, outline)
    }

    companion object {
        /** Distance of the dot frame from the screen edges. */
        const val INSET_DP = 28f
    }
}
