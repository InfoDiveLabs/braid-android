package com.infodive.braid.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max

/**
 * Throughput per lane over the last minute: thin lines over a faint fill, one
 * recessive gridline at the scale's top, and a touch-and-drag crosshair that
 * reports the exact second through [onScrub], so the values are never read off
 * colour alone.
 */
class ThroughputChart(context: Context) : View(context) {
    class Series(val color: Int, val values: LongArray)

    var series: List<Series> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Seconds ago under the finger, or null when not touching. */
    var onScrub: ((Int?) -> Unit)? = null

    private var scrub: Int? = null
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { strokeWidth = density; color = context.getColor(R.color.divider) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        fontFeatureSettings = "tnum"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (150 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val samples = series.firstOrNull()?.values?.size ?: return
        val top = label.textSize + 6 * density
        val bottom = height - label.textSize - 8 * density
        val left = 0f
        val right = width.toFloat()
        val peak = series.maxOf { s -> s.values.maxOrNull() ?: 0 }
        val scale = niceCeiling(max(peak, 64 * 1024L))

        canvas.drawLine(left, top, right, top, grid)
        canvas.drawLine(left, bottom, right, bottom, grid)
        canvas.drawText(rate(scale), left, top - 6 * density, label)
        canvas.drawText("60 s ago", left, height.toFloat() - 2 * density, label)
        val now = "now"
        canvas.drawText(now, right - label.measureText(now), height.toFloat() - 2 * density, label)

        val step = (right - left) / (samples - 1)
        fun y(v: Long) = bottom - (v.toFloat() / scale) * (bottom - top)
        for (s in series) {
            val path = Path()
            s.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(left, y(v)) else path.lineTo(left + i * step, y(v)) }
            val area = Path(path).apply {
                lineTo(right, bottom)
                lineTo(left, bottom)
                close()
            }
            fill.color = s.color
            fill.alpha = 38
            canvas.drawPath(area, fill)
            line.color = s.color
            canvas.drawPath(path, line)
        }

        scrub?.let { ago ->
            val index = samples - 1 - ago
            val x = left + index * step
            canvas.drawLine(x, top, x, bottom, grid)
            for (s in series) {
                dot.color = s.color
                canvas.drawCircle(x, y(s.values[index]), 6 * density, ring)
                canvas.drawCircle(x, y(s.values[index]), 4 * density, dot)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val samples = series.firstOrNull()?.values?.size ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val index = ((event.x / width) * (samples - 1)).toInt().coerceIn(0, samples - 1)
                scrub = samples - 1 - index
            }
            else -> scrub = null
        }
        onScrub?.invoke(scrub)
        invalidate()
        return true
    }

    companion object {
        /** 1, 2 or 5 times a power of ten, so the gridline reads as a round number. */
        /** A power of two in KiB, so the gridline reads as a round binary figure: 256 KiB/s, 1 MiB/s, 4 MiB/s. */
        fun niceCeiling(value: Long): Long {
            var ceiling = 64L * 1024
            while (ceiling < value) ceiling *= 2
            return ceiling
        }

        fun rate(bytesPerSecond: Long): String = when {
            bytesPerSecond >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB/s", bytesPerSecond / (1024.0 * 1024))
            else -> String.format(Locale.US, "%d KiB/s", bytesPerSecond / 1024)
        }
    }
}
