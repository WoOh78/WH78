package org.amnezia.awg.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import org.amnezia.awg.statistics.TrafficHistoryStore
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Theme-aware daily traffic chart for the WH78 statistics screen. */
class TrafficBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var data: List<TrafficHistoryStore.DayUsage> = emptyList()
    private var palette = DashboardPalette.forTheme(AppTheme.PREMIUM_DARK)
    private val parseFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val weekdayFormat = SimpleDateFormat("EE", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("d", Locale.getDefault())
    private val numberFormat = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.getDefault()))

    fun setData(value: List<TrafficHistoryStore.DayUsage>) {
        data = value
        invalidate()
    }

    fun setPalette(value: DashboardPalette) {
        palette = value
        invalidate()
    }

    fun unitLabel(): String {
        val maxValue = data.maxOfOrNull { it.usage.totalBytes } ?: 0L
        return when {
            maxValue >= GIB -> "Гб"
            maxValue >= MIB -> "Мб"
            maxValue >= KIB -> "Кб"
            else -> "Б"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val isHourly = data.firstOrNull()?.dateKey?.startsWith(HOURLY_PREFIX) == true

        val left = paddingLeft + dp(3f)
        val right = width - paddingRight - dp(3f)
        val top = paddingTop + dp(22f)
        val bottom = height - paddingBottom - dp(24f)
        if (right <= left || bottom <= top) return

        val maxValue = max(1L, data.maxOf { it.usage.totalBytes })
        val showValues = data.size <= 8 && !isHourly
        val valueRoom = if (showValues) dp(14f) else 0f
        val plotTop = top + valueRoom
        val plotHeight = max(dp(8f), bottom - plotTop)
        val widthPer = (right - left) / data.size.toFloat()
        val barWidth = (widthPer * if (data.size <= 7) 0.55f else 0.62f).coerceAtLeast(dp(3f))
            .coerceAtMost(widthPer * 0.76f)

        // Subtle horizontal guides help compare days without turning the chart into a grid.
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = palette.outline
        paint.alpha = 125
        for (line in 1..3) {
            val y = plotTop + plotHeight * line / 4f
            canvas.drawLine(left, y, right, y, paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL

        val unit = when {
            maxValue >= GIB -> GIB.toDouble()
            maxValue >= MIB -> MIB.toDouble()
            maxValue >= KIB -> KIB.toDouble()
            else -> 1.0
        }
        val unitText = unitLabel()
        val gradient = LinearGradient(
            0f, plotTop, 0f, bottom,
            intArrayOf(palette.accent, palette.positive),
            null,
            Shader.TileMode.CLAMP
        )

        data.forEachIndexed { index, day ->
            val centerX = left + widthPer * (index + 0.5f)
            val fraction = day.usage.totalBytes.toFloat() / maxValue.toFloat()
            val barHeight = if (day.usage.totalBytes == 0L) dp(2f) else max(dp(3f), plotHeight * fraction)
            val barTop = bottom - barHeight
            val rect = RectF(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottom)

            paint.style = Paint.Style.FILL
            paint.shader = gradient
            canvas.drawRoundRect(rect, barWidth / 3f, barWidth / 3f, paint)
            paint.shader = null

            if (showValues) {
                paint.color = palette.text
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = sp(9f)
                val raw = day.usage.totalBytes / unit
                canvas.drawText(numberFormat.format(raw), centerX, max(top + sp(9f), barTop - dp(4f)), paint)
            }

            val shouldDrawDay = if (isHourly) {
                index == 0 || index == data.lastIndex || index % 4 == 0
            } else {
                data.size <= 8 || index == 0 || index == data.lastIndex || index % 5 == 0
            }
            if (shouldDrawDay) {
                val millis = try { parseFormat.parse(day.dateKey)?.time ?: 0L } catch (_: Exception) { 0L }
                val label = when {
                    isHourly -> day.dateKey.removePrefix(HOURLY_PREFIX)
                    data.size <= 8 -> weekdayFormat.format(Date(millis)).lowercase(Locale.getDefault())
                    else -> dayFormat.format(Date(millis))
                }
                paint.color = palette.secondaryText
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = sp(10f)
                canvas.drawText(label, centerX, height - paddingBottom - dp(5f), paint)
            }
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    companion object {
        private const val HOURLY_PREFIX = "hour:"
        private const val KIB = 1024L
        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * 1024L * 1024L
    }
}
