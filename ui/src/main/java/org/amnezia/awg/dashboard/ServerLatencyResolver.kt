package org.amnezia.awg.dashboard

import org.amnezia.awg.model.ObservableTunnel
import java.net.InetAddress
import java.net.Socket
import kotlin.math.max

/** Measures reachability of the configured VPN endpoint. */
object ServerLatencyResolver {
    private const val TIMEOUT_MS = 2_000

    suspend fun resolve(tunnel: ObservableTunnel): Long? {
        return try {
            val config = tunnel.getConfigAsync()
            val endpoint = config.getPeers().firstOrNull()?.getEndpoint()?.orElse(null) ?: return null
            val host = endpoint.getHost()
            val port = endpoint.getPort()
            val address = InetAddress.getByName(host)

            val started = System.nanoTime()
            if (address.isReachable(TIMEOUT_MS)) {
                return elapsedMs(started)
            }

            // Some VPN endpoints do not answer ICMP. A TCP connect is a fallback
            // reachability measurement to the same host/port.
            val tcpStarted = System.nanoTime()
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(address, port), TIMEOUT_MS)
            }
            elapsedMs(tcpStarted)
        } catch (_: Throwable) {
            null
        }
    }

    private fun elapsedMs(startedNanos: Long): Long =
        max(1L, (System.nanoTime() - startedNanos) / 1_000_000L)
}
