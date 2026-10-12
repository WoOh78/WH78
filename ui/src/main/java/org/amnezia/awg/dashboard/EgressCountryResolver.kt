package org.amnezia.awg.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resolves the country of the actual public egress IP. */
object EgressCountryResolver {
    private const val TIMEOUT_MS = 5_000
    private const val IP_API_URL =
        "http://ip-api.com/json/?fields=status,country,countryCode"

    suspend fun resolve(context: Context): String? {
        val vpnNetwork = findVpnNetwork(context)
        return requestIpApi(vpnNetwork)
    }

    private fun requestIpApi(vpnNetwork: Network?): String? {
        val connection = try {
            val url = URL(IP_API_URL)
            ((vpnNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "MyVPN/1.0")
            }
        } catch (_: Throwable) {
            return null
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            if (!json.optString("status").equals("success", ignoreCase = true)) return null
            json.optString("countryCode")
                .uppercase()
                .takeIf { it.matches(Regex("[A-Z]{2}")) }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun findVpnNetwork(context: Context): Network? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Throwable) {
            null
        }
    }
}
