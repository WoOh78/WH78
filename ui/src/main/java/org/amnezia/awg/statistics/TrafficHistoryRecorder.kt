package org.amnezia.awg.statistics

import org.amnezia.awg.Application
import org.amnezia.awg.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Records aggregated VPN counters while the application process is alive. */
class TrafficHistoryRecorder(
    private val scope: CoroutineScope,
    private val store: TrafficHistoryStore
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            delay(5000)
            while (isActive) {
                try {
                    val tunnels = Application.getTunnelManager().getTunnels()
                    for (tunnel in tunnels) {
                        if (tunnel.state != Tunnel.State.UP) continue
                        try {
                            val stats = tunnel.getStatisticsAsync()
                            store.record(tunnel.name, stats.totalRx(), stats.totalTx())
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
                delay(60_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
