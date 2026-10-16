package org.amnezia.awg.dashboard

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.statistics.TrafficHistoryStore
import org.amnezia.awg.util.UserKnobs
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrafficHistoryActivity : AppCompatActivity() {
    private enum class Period(val title: String, val chartTitle: String) {
        TODAY("Сегодня", "Трафик по часам за сегодня"),
        WEEK("Последние 7 дней", "Трафик за последние 7 дней"),
        MONTH("Этот месяц", "Трафик по дням этого месяца"),
        ALL("Всё время", "Последние 30 дней")
    }

    private lateinit var store: TrafficHistoryStore
    private lateinit var chart: TrafficBarChartView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var periodTitle: TextView
    private lateinit var periodTotal: TextView
    private lateinit var rxValue: TextView
    private lateinit var txValue: TextView
    private lateinit var chartTitle: TextView
    private lateinit var chartSubtitle: TextView
    private lateinit var today: RollupViews
    private lateinit var week: RollupViews
    private lateinit var month: RollupViews
    private lateinit var allTime: RollupViews
    private lateinit var periodTabs: List<TextView>

    private var selectedPeriod = Period.WEEK
    private var currentPalette = DashboardPalette.forTheme(AppTheme.PREMIUM_DARK)
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traffic_history)
        val root = findViewById<View>(R.id.traffic_history_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = TrafficHistoryStore(applicationContext)
        chart = findViewById(R.id.traffic_chart)
        periodTitle = findViewById(R.id.selected_period_title)
        periodTotal = findViewById(R.id.selected_total_value)
        rxValue = findViewById(R.id.selected_rx_value)
        txValue = findViewById(R.id.selected_tx_value)
        chartTitle = findViewById(R.id.history_chart_title)
        chartSubtitle = findViewById(R.id.history_chart_subtitle)
        today = RollupViews(
            findViewById(R.id.today_rx_value), findViewById(R.id.today_tx_value),
            findViewById(R.id.today_total_value)
        )
        week = RollupViews(
            findViewById(R.id.week_rx_value), findViewById(R.id.week_tx_value),
            findViewById(R.id.week_total_value)
        )
        month = RollupViews(
            findViewById(R.id.month_rx_value), findViewById(R.id.month_tx_value),
            findViewById(R.id.month_total_value)
        )
        allTime = RollupViews(
            findViewById(R.id.all_time_rx_value), findViewById(R.id.all_time_tx_value),
            findViewById(R.id.all_time_total_value)
        )

        periodTabs = listOf(
            findViewById(R.id.tab_today),
            findViewById(R.id.tab_week),
            findViewById(R.id.tab_month),
            findViewById(R.id.tab_all_time)
        )
        periodTabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                val next = Period.values()[index]
                if (selectedPeriod != next) {
                    selectedPeriod = next
                    renderPeriodTabs()
                    refresh()
                }
            }
        }

        renderPeriodTabs()
        lifecycleScope.launch { applyTheme() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { applyTheme() }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private suspend fun applyTheme() {
        val themeKey = UserKnobs.appTheme.first()
        currentPalette = DashboardPalette.forTheme(AppTheme.fromKey(themeKey))
        val p = currentPalette

        findViewById<View>(android.R.id.content).setBackgroundColor(p.background)
        toolbar.setBackgroundColor(p.background)
        toolbar.setTitleTextColor(p.text)
        toolbar.navigationIcon?.setTint(p.text)
        toolbar.title = "Статистика"

        listOf(
            R.id.period_selector_card,
            R.id.history_hero_card,
            R.id.history_chart_card,
            R.id.history_rollup_card
        ).forEach { id ->
            findViewById<MaterialCardView>(id).apply {
                setCardBackgroundColor(p.surface)
                strokeWidth = dp(1)
                strokeColor = p.outline
            }
        }

        findViewById<TextView>(R.id.selected_period_title).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.selected_total_value).setTextColor(p.text)
        findViewById<TextView>(R.id.selected_rx_label).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.selected_tx_label).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.selected_rx_value).setTextColor(p.positive)
        findViewById<TextView>(R.id.selected_tx_value).setTextColor(UPLOAD_COLOR)
        findViewById<TextView>(R.id.history_chart_title).setTextColor(p.text)
        findViewById<TextView>(R.id.history_chart_subtitle).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.history_summary_title).setTextColor(p.text)
        listOf(R.id.rollup_header_period, R.id.rollup_header_rx, R.id.rollup_header_tx, R.id.rollup_header_total)
            .forEach { findViewById<TextView>(it).setTextColor(p.secondaryText) }
        findViewById<TextView>(R.id.rollup_header_rx).compoundDrawableTintList = ColorStateList.valueOf(p.positive)
        findViewById<TextView>(R.id.rollup_header_tx).compoundDrawableTintList = ColorStateList.valueOf(UPLOAD_COLOR)

        listOf(R.id.today_label, R.id.week_label, R.id.month_label, R.id.all_time_label).forEach {
            findViewById<TextView>(it).setTextColor(p.text)
        }
        listOf(today, week, month, allTime).forEach { row ->
            row.rx.setTextColor(p.positive)
            row.tx.setTextColor(UPLOAD_COLOR)
            row.total.setTextColor(p.secondaryText)
            row.rx.compoundDrawableTintList = ColorStateList.valueOf(p.positive)
            row.tx.compoundDrawableTintList = ColorStateList.valueOf(UPLOAD_COLOR)
        }
        findViewById<TextView>(R.id.selected_rx_label).compoundDrawableTintList = ColorStateList.valueOf(p.positive)
        findViewById<TextView>(R.id.selected_tx_label).compoundDrawableTintList = ColorStateList.valueOf(UPLOAD_COLOR)
        listOf(R.id.rollup_divider_1, R.id.rollup_divider_2, R.id.rollup_divider_3).forEach {
            findViewById<View>(it).setBackgroundColor(p.outline)
        }
        chart.setPalette(p)
        renderPeriodTabs()
    }

    private fun renderPeriodTabs() {
        if (!::periodTabs.isInitialized) return
        val p = currentPalette
        periodTabs.forEachIndexed { index, tab ->
            val selected = Period.values()[index] == selectedPeriod
            tab.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) p.accent else p.surface2)
                if (selected) setStroke(dp(1), p.accent)
            }
            tab.setTextColor(if (selected) p.accentText else p.secondaryText)
            tab.alpha = if (selected) 1f else 0.92f
            tab.isSelected = selected
        }
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
                val todayUsage = store.today()
                val weekUsage = store.last7Days()
                val monthUsage = store.thisMonth()
                val allUsage = store.allTime()
                val daily = when (selectedPeriod) {
                    Period.TODAY -> store.hourly24()
                    Period.WEEK -> store.daily(7)
                    Period.MONTH -> store.daily(31).filter { it.dateKey.startsWith(currentMonth) }
                    Period.ALL -> store.daily(30)
                }
                Snapshot(todayUsage, weekUsage, monthUsage, allUsage, daily)
            }

            periodTitle.text = selectedPeriod.title
            val selected = when (selectedPeriod) {
                Period.TODAY -> snapshot.today
                Period.WEEK -> snapshot.week
                Period.MONTH -> snapshot.month
                Period.ALL -> snapshot.allTime
            }
            periodTotal.text = formatTrafficBytes(selected.totalBytes)
            rxValue.text = formatTrafficBytes(selected.rxBytes)
            txValue.text = formatTrafficBytes(selected.txBytes)
            chartTitle.text = selectedPeriod.chartTitle
            updateRollup(today, snapshot.today)
            updateRollup(week, snapshot.week)
            updateRollup(month, snapshot.month)
            updateRollup(allTime, snapshot.allTime)
            chart.setData(snapshot.days)
            chartSubtitle.text = if (selectedPeriod == Period.TODAY) {
                "Трафик за час · ${chart.unitLabel()}"
            } else {
                "Общий трафик за день · ${chart.unitLabel()}"
            }
        }
    }

    private fun updateRollup(views: RollupViews, usage: TrafficHistoryStore.Usage) {
        views.rx.text = formatTrafficBytes(usage.rxBytes)
        views.tx.text = formatTrafficBytes(usage.txBytes)
        views.total.text = formatTrafficBytes(usage.totalBytes)
    }

    private fun formatTrafficBytes(bytes: Long): String {
        val units = arrayOf("Б", "Кб", "Мб", "Гб", "Тб", "Пб")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val number = if (unitIndex == 0) {
            value.toLong().toString()
        } else {
            DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(value)
        }
        return "$number ${units[unitIndex]}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class RollupViews(
        val rx: TextView,
        val tx: TextView,
        val total: TextView
    )

    private data class Snapshot(
        val today: TrafficHistoryStore.Usage,
        val week: TrafficHistoryStore.Usage,
        val month: TrafficHistoryStore.Usage,
        val allTime: TrafficHistoryStore.Usage,
        val days: List<TrafficHistoryStore.DayUsage>
    )

    companion object {
        private val UPLOAD_COLOR = android.graphics.Color.rgb(245, 180, 54)
    }
}
