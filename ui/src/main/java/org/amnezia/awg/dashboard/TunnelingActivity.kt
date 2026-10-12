package org.amnezia.awg.dashboard

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.fragment.AppListDialogFragment
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.viewmodel.ConfigProxy
import kotlinx.coroutines.launch

class TunnelingActivity : AppCompatActivity() {
    private var tunnel: ObservableTunnel? = null
    private lateinit var modeText: TextView
    private lateinit var detailText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunneling)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        modeText = findViewById(R.id.tunneling_mode)
        detailText = findViewById(R.id.tunneling_detail)
        findViewById<MaterialButton>(R.id.configure_apps_button).setOnClickListener { showAppPicker() }
        lifecycleScope.launch { loadTunnel() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private suspend fun loadTunnel() {
        val name = intent.getStringExtra(EXTRA_TUNNEL_NAME)
        tunnel = try {
            Application.getTunnelManager().getTunnels().firstOrNull { it.name == name } ?: Application.getTunnelManager().getTunnels().firstOrNull()
        } catch (_: Throwable) { null }
        updateSummary()
    }

    private fun showAppPicker() {
        val current = tunnel ?: return
        lifecycleScope.launch {
            try {
                val config = current.getConfigAsync()
                val selected: ArrayList<String?>
                val excluded: Boolean
                if (config.`interface`.getExcludedApplications().isNotEmpty()) {
                    selected = ArrayList(config.`interface`.getExcludedApplications())
                    excluded = true
                } else {
                    selected = ArrayList(config.`interface`.getIncludedApplications())
                    excluded = false
                }
                supportFragmentManager.setFragmentResultListener(AppListDialogFragment.REQUEST_SELECTION, this@TunnelingActivity) { _, result ->
                    val apps = result.getStringArray(AppListDialogFragment.KEY_SELECTED_APPS)?.toList() ?: emptyList()
                    val useExcluded = result.getBoolean(AppListDialogFragment.KEY_IS_EXCLUDED)
                    lifecycleScope.launch {
                        try {
                            val proxy = ConfigProxy(config)
                            proxy.`interface`.excludedApplications.clear()
                            proxy.`interface`.includedApplications.clear()
                            if (useExcluded) proxy.`interface`.excludedApplications.addAll(apps)
                            else proxy.`interface`.includedApplications.addAll(apps)
                            current.setConfigAsync(proxy.resolve())
                            updateSummary()
                            Toast.makeText(this@TunnelingActivity, R.string.dashboard_tunneling_saved, Toast.LENGTH_SHORT).show()
                        } catch (e: Throwable) {
                            Toast.makeText(this@TunnelingActivity, getString(R.string.dashboard_tunneling_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                AppListDialogFragment.newInstance(selected, excluded).show(supportFragmentManager, DIALOG_TAG)
            } catch (e: Throwable) {
                Toast.makeText(this@TunnelingActivity, getString(R.string.dashboard_tunneling_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun updateSummary() {
        val current = tunnel ?: run {
            modeText.text = getString(R.string.dashboard_no_profile)
            detailText.text = getString(R.string.dashboard_add_profile_hint)
            return
        }
        try {
            val config = current.getConfigAsync()
            val excluded = config.`interface`.getExcludedApplications()
            val included = config.`interface`.getIncludedApplications()
            when {
                excluded.isNotEmpty() -> {
                    modeText.text = getString(R.string.dashboard_tunneling_exclude_mode)
                    detailText.text = resources.getQuantityString(R.plurals.dashboard_n_apps_excluded, excluded.size, excluded.size)
                }
                included.isNotEmpty() -> {
                    modeText.text = getString(R.string.dashboard_tunneling_include_mode)
                    detailText.text = resources.getQuantityString(R.plurals.dashboard_n_apps_included, included.size, included.size)
                }
                else -> {
                    modeText.text = getString(R.string.dashboard_tunneling_all_mode)
                    detailText.text = getString(R.string.dashboard_tunneling_all_detail)
                }
            }
        } catch (_: Throwable) {
            modeText.text = getString(R.string.dashboard_tunneling_unavailable)
            detailText.text = ""
        }
    }

    companion object {
        const val EXTRA_TUNNEL_NAME = "tunnel_name"
        private const val DIALOG_TAG = "APP_PICKER"
    }
}
