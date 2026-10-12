package org.amnezia.awg.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.amnezia.awg.statistics.TrafficHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class TrafficBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var data: List<TrafficHistoryStore.DayUsage> = emptyList()
    private var palette = DashboardPalette.forTheme(AppTheme.PREMIUM_DARK)
    private val labelFormat = SimpleDateFormat("dd", Locale.getDefault())

    fun setData(value: List<TrafficHistoryStore.DayUsage>) {
        data = value
        invalidate()
    }

    fun setPalette(value: DashboardPalette) {
        palette = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val maxValue = max(1L, data.maxOf { it.usage.totalBytes })
        val widthPer = width.toFloat() / data.size
        val bottom = height * 0.82f
        val top = height * 0.12f
        val barWidth = widthPer * 0.55f
        paint.textAlign = Paint.Align.CENTER
        data.forEachIndexed { index, day ->
            val x = index * widthPer + widthPer / 2f
            val fraction = day.usage.totalBytes.toFloat() / maxValue.toFloat()
            val barHeight = (bottom - top) * fraction
            paint.color = palette.accent
            canvas.drawRoundRect(RectF(x - barWidth / 2f, bottom - barHeight, x + barWidth / 2f, bottom), barWidth / 3f, barWidth / 3f, paint)
            paint.color = palette.secondaryText
            paint.textSize = 24f
            val millis = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.dateKey)?.time ?: 0L } catch (_: Exception) { 0L }
            canvas.drawText(labelFormat.format(Date(millis)), x, height * 0.96f, paint)
        }
    }
}
