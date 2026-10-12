package org.amnezia.awg.dashboard

import android.content.Context

/**
 * Persists the elapsed-realtime moment a tunnel session was first observed as active.
 * The value survives DashboardActivity recreation, but is automatically reset after a
 * device reboot because elapsedRealtime() starts from zero again.
 */
class ConnectionSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun peek(tunnelName: String): Long? {
        val saved = prefs.getLong(key(tunnelName), 0L)
        return saved.takeIf { it > 0L }
    }

    fun getOrCreate(tunnelName: String, nowElapsed: Long): Long {
        val key = key(tunnelName)
        val saved = prefs.getLong(key, 0L)
        if (saved > 0L && saved <= nowElapsed) {
            return saved
        }
        prefs.edit().putLong(key, nowElapsed).apply()
        return nowElapsed
    }

    fun clear(tunnelName: String) {
        prefs.edit().remove(key(tunnelName)).apply()
    }

    private fun key(tunnelName: String) = "connection_start_$tunnelName"

    companion object {
        private const val FILE_NAME = "connection_sessions"
    }
}
