package org.amnezia.awg.statistics

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max

/**
 * Small dependency-free traffic history store for the first application iteration.
 * It stores daily deltas instead of every packet/sample and keeps an all-time counter.
 */
class TrafficHistoryStore(context: Context) {
    data class Usage(val rxBytes: Long, val txBytes: Long) {
        val totalBytes: Long get() = rxBytes + txBytes
    }

    data class DayUsage(val dateKey: String, val usage: Usage)

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun record(tunnelName: String, rxBytes: Long, txBytes: Long, timestamp: Long = System.currentTimeMillis()) {
        if (rxBytes < 0 || txBytes < 0) return
        synchronized(lock) {
            val last = readLastCounters()
            val previous = last[tunnelName]
            val deltaRx = if (previous == null || rxBytes < previous.rx) 0L else rxBytes - previous.rx
            val deltaTx = if (previous == null || txBytes < previous.tx) 0L else txBytes - previous.tx
            last[tunnelName] = Counter(rxBytes, txBytes)

            val days = readDays()
            val key = dateKey(timestamp)
            val current = days[key] ?: Usage(0, 0)
            days[key] = Usage(current.rxBytes + deltaRx, current.txBytes + deltaTx)

            val total = readTotal()
            val totalRx = safeAdd(total.rxBytes, deltaRx)
            val totalTx = safeAdd(total.txBytes, deltaTx)
            prune(days, timestamp)

            prefs.edit()
                .putString(KEY_LAST_COUNTERS, writeLastCounters(last))
                .putString(KEY_DAILY, writeDays(days))
                .putLong(KEY_TOTAL_RX, totalRx)
                .putLong(KEY_TOTAL_TX, totalTx)
                .apply()
        }
    }

    fun today(now: Long = System.currentTimeMillis()): Usage = sinceDays(1, now)

    fun last7Days(now: Long = System.currentTimeMillis()): Usage = sinceDays(7, now)

    fun last30Days(now: Long = System.currentTimeMillis()): Usage = sinceDays(30, now)

    fun thisMonth(now: Long = System.currentTimeMillis()): Usage {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val stored = synchronized(lock) { readDays() }
        var rx = 0L
        var tx = 0L
        stored.forEach { (key, usage) ->
            try {
                val date = dateFormat.parse(key) ?: return@forEach
                val dayCalendar = Calendar.getInstance().apply { timeInMillis = date.time }
                if (dayCalendar.get(Calendar.YEAR) == year && dayCalendar.get(Calendar.MONTH) == month) {
                    rx = safeAdd(rx, usage.rxBytes)
                    tx = safeAdd(tx, usage.txBytes)
                }
            } catch (_: Exception) {
            }
        }
        return Usage(rx, tx)
    }

    fun allTime(): Usage = synchronized(lock) { readTotal() }

    fun daily(daysBack: Int = 7, now: Long = System.currentTimeMillis()): List<DayUsage> {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val result = ArrayList<DayUsage>(daysBack)
        val stored = synchronized(lock) { readDays() }
        repeat(daysBack) {
            val key = dateKey(calendar.timeInMillis)
            result += DayUsage(key, stored[key] ?: Usage(0, 0))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return result.asReversed()
    }

    private fun sinceDays(days: Int, now: Long): Usage {
        val cutoff = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(days - 1))
        }.timeInMillis
        val stored = synchronized(lock) { readDays() }
        var rx = 0L
        var tx = 0L
        stored.forEach { (key, usage) ->
            try {
                val date = dateFormat.parse(key)?.time ?: return@forEach
                if (date >= cutoff) {
                    rx = safeAdd(rx, usage.rxBytes)
                    tx = safeAdd(tx, usage.txBytes)
                }
            } catch (_: Exception) {
            }
        }
        return Usage(rx, tx)
    }

    private fun prune(days: MutableMap<String, Usage>, now: Long) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -366)
        }
        val cutoff = calendar.timeInMillis
        days.keys.removeAll { key ->
            try {
                (dateFormat.parse(key)?.time ?: Long.MAX_VALUE) < cutoff
            } catch (_: Exception) {
                true
            }
        }
    }

    private fun dateKey(timestamp: Long): String = dateFormat.format(Date(timestamp))

    private data class Counter(val rx: Long, val tx: Long)

    private fun readTotal() = Usage(prefs.getLong(KEY_TOTAL_RX, 0), prefs.getLong(KEY_TOTAL_TX, 0))

    private fun readDays(): MutableMap<String, Usage> {
        val source = prefs.getString(KEY_DAILY, null) ?: return LinkedHashMap()
        val result = LinkedHashMap<String, Usage>()
        try {
            val json = JSONObject(source)
            json.keys().forEach { key ->
                val item = json.optJSONObject(key) ?: return@forEach
                result[key] = Usage(item.optLong("rx", 0), item.optLong("tx", 0))
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun writeDays(days: Map<String, Usage>): String {
        val json = JSONObject()
        days.forEach { (key, usage) ->
            json.put(key, JSONObject().put("rx", usage.rxBytes).put("tx", usage.txBytes))
        }
        return json.toString()
    }

    private fun readLastCounters(): MutableMap<String, Counter> {
        val source = prefs.getString(KEY_LAST_COUNTERS, null) ?: return LinkedHashMap()
        val result = LinkedHashMap<String, Counter>()
        try {
            val json = JSONObject(source)
            json.keys().forEach { key ->
                val item = json.optJSONObject(key) ?: return@forEach
                result[key] = Counter(item.optLong("rx", 0), item.optLong("tx", 0))
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun writeLastCounters(counters: Map<String, Counter>): String {
        val json = JSONObject()
        counters.forEach { (key, value) ->
            json.put(key, JSONObject().put("rx", value.rx).put("tx", value.tx))
        }
        return json.toString()
    }

    private fun safeAdd(a: Long, b: Long): Long = if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else max(0L, a + b)

    companion object {
        private const val FILE_NAME = "traffic_history"
        private const val KEY_DAILY = "daily"
        private const val KEY_TOTAL_RX = "total_rx"
        private const val KEY_TOTAL_TX = "total_tx"
        private const val KEY_LAST_COUNTERS = "last_counters"
    }
}
