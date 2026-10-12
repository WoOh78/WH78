package org.amnezia.awg

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import org.amnezia.awg.dashboard.DashboardActivity
import org.amnezia.awg.activity.TunnelToggleActivity
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.util.applicationScope
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {
    private val onStateChangedCallback = OnStateChangedCallback()
    private val onTunnelChangedCallback = OnTunnelChangedCallback()
    private var iconOff: Icon? = null
    private var iconOn: Icon? = null
    private var tunnel: ObservableTunnel? = null

    override fun onBind(intent: Intent): IBinder? {
        var ret: IBinder? = null
        try { ret = super.onBind(intent) } catch (e: Throwable) { Log.d(TAG, "Failed to bind to TileService", e) }
        return ret
    }

    override fun onClick() {
        when (val currentTunnel = tunnel) {
            null -> {
                val intent = Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                    startActivityAndCollapse(intent)
                }
            }
            else -> unlockAndRun {
                applicationScope.launch {
                    try { currentTunnel.setStateAsync(Tunnel.State.TOGGLE); updateTile() }
                    catch (_: Throwable) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !Settings.canDrawOverlays(this@QuickTileService)) {
                            val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivityAndCollapse(PendingIntent.getActivity(this@QuickTileService, 0, permissionIntent, PendingIntent.FLAG_IMMUTABLE))
                            return@launch
                        }
                        val toggleIntent = Intent(this@QuickTileService, TunnelToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(toggleIntent)
                    }
                }
            }
        }
    }

    private fun refreshIconsForTheme() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val iconRes = if (night) R.drawable.wh78_qs_mono_dark_b18 else R.drawable.wh78_qs_mono_light_b18
        val icon = Icon.createWithResource(this, iconRes)
        iconOn = icon
        iconOff = icon
    }

    override fun onCreate() { super.onCreate(); isAdded = true; refreshIconsForTheme() }
    override fun onDestroy() { super.onDestroy(); isAdded = false }
    override fun onStartListening() { refreshIconsForTheme(); Application.getTunnelManager().addOnPropertyChangedCallback(onTunnelChangedCallback); tunnel?.addOnPropertyChangedCallback(onStateChangedCallback); updateTile() }
    override fun onStopListening() { tunnel?.removeOnPropertyChangedCallback(onStateChangedCallback); Application.getTunnelManager().removeOnPropertyChangedCallback(onTunnelChangedCallback) }
    override fun onTileAdded() { isAdded = true }
    override fun onTileRemoved() { isAdded = false }

    private fun updateTile() {
        val newTunnel = Application.getTunnelManager().lastUsedTunnel
        if (newTunnel != tunnel) { tunnel?.removeOnPropertyChangedCallback(onStateChangedCallback); tunnel = newTunnel; tunnel?.addOnPropertyChangedCallback(onStateChangedCallback) }
        val tile = qsTile ?: return
        when (val currentTunnel = tunnel) {
            null -> { tile.label = getString(R.string.app_name); tile.state = Tile.STATE_INACTIVE; tile.icon = iconOff }
            else -> { tile.label = currentTunnel.name; tile.state = if (currentTunnel.state == Tunnel.State.UP) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; tile.icon = if (currentTunnel.state == Tunnel.State.UP) iconOn else iconOff }
        }
        tile.updateTile()
    }

    private inner class OnStateChangedCallback : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable, propertyId: Int) { if (sender != tunnel) { sender.removeOnPropertyChangedCallback(this); return }; if (propertyId != 0 && propertyId != BR.state) return; updateTile() }
    }
    private inner class OnTunnelChangedCallback : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable, propertyId: Int) { if (propertyId != 0 && propertyId != BR.lastUsedTunnel) return; updateTile() }
    }

    companion object { private const val TAG = "AmneziaWG/QuickTileService"; var isAdded: Boolean = false; private set }
}
