package org.amnezia.awg.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/** Real HTTPS latency tests. Requests follow the device's active network, including the VPN. */
object ServiceLatencyResolver {
    data class Service(
        val id: String,
        val name: String,
        val url: String,
        val pingViewId: Int
    )

    lateinit var YOUTUBE: Service
    lateinit var INSTAGRAM: Service
    lateinit var WHATSAPP: Service
    lateinit var TELEGRAM: Service

    fun init(
        youtubePingViewId: Int,
        instagramPingViewId: Int,
        whatsappPingViewId: Int,
        telegramPingViewId: Int
    ) {
        YOUTUBE = Service("youtube", "YouTube", "https://www.youtube.com/generate_204", youtubePingViewId)
        INSTAGRAM = Service("instagram", "Instagram", "https://www.instagram.com/robots.txt", instagramPingViewId)
        WHATSAPP = Service("whatsapp", "WhatsApp", "https://www.whatsapp.com/robots.txt", whatsappPingViewId)
        TELEGRAM = Service("telegram", "Telegram", "https://telegram.org/", telegramPingViewId)
    }

    fun resolve(context: Context, service: Service): Long? {
        val vpnNetwork = findVpnNetwork(context)
        val connection = ((vpnNetwork?.openConnection(URL(service.url)) ?: URL(service.url).openConnection()) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "MyVPN/1.0")
        }
        val started = System.nanoTime()
        return try {
            val code = connection.responseCode
            if (code !in 200..399) return null
            maxOf(1L, (System.nanoTime() - started) / 1_000_000L)
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
