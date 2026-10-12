package org.amnezia.awg.dashboard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import org.amnezia.awg.R
import org.amnezia.awg.statistics.TrafficHistoryStore
import org.amnezia.awg.util.QuantityFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class TrafficHistoryActivity : AppCompatActivity() {
    private lateinit var store: TrafficHistoryStore
    private lateinit var chart: TrafficBarChartView
    private lateinit var today: TextView
    private lateinit var week: TextView
    private lateinit var month: TextView
    private lateinit var allTime: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traffic_history)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = TrafficHistoryStore(applicationContext)
        chart = findViewById(R.id.traffic_chart)
        today = findViewById(R.id.today_value)
        week = findViewById(R.id.week_value)
        month = findViewById(R.id.month_value)
        allTime = findViewById(R.id.all_time_value)
        lifecycleScope.launch { applyTheme() }
        refresh()
    }

    override fun onResume() { super.onResume(); lifecycleScope.launch { applyTheme() }; refresh() }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }


    private suspend fun applyTheme() {
        val themeKey = org.amnezia.awg.util.UserKnobs.appTheme.first()
        val theme = AppTheme.fromKey(themeKey)
        val p = DashboardPalette.forTheme(theme)
        findViewById<View>(android.R.id.content).setBackgroundColor(p.background)
        findViewById<MaterialCardView>(R.id.history_chart_card)?.setCardBackgroundColor(p.surface)
        findViewById<TextView>(R.id.history_summary_title)?.setTextColor(p.text)
        listOf(R.id.today_value, R.id.week_value, R.id.month_value, R.id.all_time_value).forEach { findViewById<TextView>(it).setTextColor(p.secondaryText) }
        chart.setPalette(p)
    }

    private fun refresh() {
        lifecycleScope.launch(Dispatchers.Default) {
            val t = store.today()
            val w = store.last7Days()
            val m = store.last30Days()
            val a = store.allTime()
            val days = store.daily(7)
            withContext(Dispatchers.Main) {
                today.text = usageText(t)
                week.text = usageText(w)
                month.text = usageText(m)
                allTime.text = usageText(a)
                chart.setData(days)
            }
        }
    }

    private fun usageText(usage: TrafficHistoryStore.Usage): String = getString(
        R.string.dashboard_usage_format,
        QuantityFormatter.formatBytes(usage.rxBytes),
        QuantityFormatter.formatBytes(usage.txBytes),
        QuantityFormatter.formatBytes(usage.totalBytes)
    )
}
