package com.bassplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.pow

/**
 * A Bass-AnalyzerV2.html vizualizátorának 1:1 portja:
 *  - 80 sáv
 *  - logIndex = floor((i / 80)^2.1 * (bufferLength * 0.45)) + 1
 *  - norm = clamp((db + 90) / 75)
 *  - barHeight = norm * (height - 30)
 *  - gradient: rgba(29,185,84,0.2) -> #1db954 (0.5) -> #1ed760
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 80
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val gap = 2f * density
    private val radius = 1.5f * density
    private val bottomPad = 6f * density

    private val barRect = RectF()
    private val barPath = Path()
    private val cornerRadii = FloatArray(8)

    private var spectrum = FloatArray(0)
    private var gradient: LinearGradient? = null
    private var gradientHeight = 0

    fun updateSpectrum(values: FloatArray) {
        spectrum = values
        invalidate()
    }

    fun reset() {
        spectrum = FloatArray(0)
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        gradient = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (gradient == null || gradientHeight != this.height) {
            gradient = LinearGradient(
                0f, h, 0f, 0f,
                intArrayOf(
                    Color.argb(51, 29, 185, 84),
                    Color.rgb(29, 185, 84),
                    Color.rgb(30, 215, 96)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            gradientHeight = this.height
        }

        paint.shader = gradient

        // HTML: barWidth = width / numBars - 2
        val barWidth = w / BAR_COUNT - gap
        if (barWidth <= 0f) return

        val usableHeight = (h - bottomPad).coerceAtLeast(1f)
        val data = spectrum
        if (data.isEmpty()) return

        cornerRadii[0] = radius
        cornerRadii[1] = radius
        cornerRadii[2] = radius
        cornerRadii[3] = radius
        cornerRadii[4] = 0f
        cornerRadii[5] = 0f
        cornerRadii[6] = 0f
        cornerRadii[7] = 0f

        for (i in 0 until BAR_COUNT) {
            val fraction = i.toFloat() / BAR_COUNT
            val logIndex = (fraction.pow(2.1f) * (data.size * 0.45f)).toInt() + 1
            val db = data[logIndex.coerceIn(0, data.size - 1)]
            val norm = ((db + 90f) / 75f).coerceIn(0f, 1f)
            val barHeight = norm * usableHeight
            if (barHeight < 1f) continue

            val x = i * (barWidth + gap)
            val y = h - barHeight
            barRect.set(x, y, x + barWidth, h)
            barPath.reset()
            barPath.addRoundRect(barRect, cornerRadii, Path.Direction.CW)
            canvas.drawPath(barPath, paint)
        }
    }
}
