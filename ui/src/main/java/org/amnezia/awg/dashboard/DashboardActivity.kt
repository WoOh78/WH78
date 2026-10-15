package org.amnezia.awg.dashboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.animation.ObjectAnimator
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.EditText
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.Toast
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.app.Dialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.net.VpnService
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.amnezia.awg.Application
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.activity.MainActivity
import org.amnezia.awg.activity.BaseActivity
import org.amnezia.awg.fragment.TunnelListFragment
import org.amnezia.awg.activity.TunnelCreatorActivity
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.fragment.AppListDialogFragment
import org.amnezia.awg.viewmodel.ConfigProxy
import org.amnezia.awg.statistics.TrafficHistoryStore
import org.amnezia.awg.util.TunnelImporter
import org.amnezia.awg.util.QuantityFormatter
import org.amnezia.awg.util.UserKnobs
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class DashboardActivity : BaseActivity() {
    private lateinit var root: View
    private lateinit var statusDot: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var activeTimeMain: TextView
    private lateinit var profileExpiry: TextView
    private lateinit var rxValue: TextView
    private lateinit var txValue: TextView
    private lateinit var totalValue: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var addProfileButton: MaterialButton
    private lateinit var serverCard: MaterialCardView
    private lateinit var connectionCard: MaterialCardView
    private lateinit var trafficCard: MaterialCardView
    private lateinit var serviceSection: TextView
    private lateinit var serviceRow1: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var dashboardPage: View
    private lateinit var tunnelPage: View
    private lateinit var tunnelListContainer: View
    private lateinit var settingsButton: ImageButton
    private var autoConnectAttempted = false

    private enum class Page { DASHBOARD, TUNNELS }
    private var currentPage = Page.DASHBOARD

    private lateinit var historyStore: TrafficHistoryStore
    private lateinit var metaStore: ProfileMetaStore
    private lateinit var connectionSessionStore: ConnectionSessionStore
    private var refreshJob: Job? = null
    private var egressCountryJob: Job? = null
    private var serverLatencyJob: Job? = null
    private var serviceTestJobs = mutableMapOf<String, Job>()
    private val successfulServices = mutableSetOf<String>()
    private var activeTunnel: ObservableTunnel? = null
    private var egressTunnelName: String? = null
    private var egressCountry: String? = null
    private var connectionStartedElapsed: Long? = null
    private var statusDotAnimator: ObjectAnimator? = null
    private var lastTheme = AppTheme.PREMIUM_DARK
    private var tunnelNamesBeforeImport: Set<String> = emptySet()
    private var pendingConnectTunnelName: String? = null
    private var profileExpiryTunnelName: String? = null
    private var profileExpiresAt: Long? = null
    private var profileExpiryJob: Job? = null
    private var disconnectClearJob: Job? = null
    private var downX = 0f
    private var downY = 0f

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tunnelName = pendingConnectTunnelName
        pendingConnectTunnelName = null
        if (result.resultCode != RESULT_OK || tunnelName.isNullOrBlank()) return@registerForActivityResult

        lifecycleScope.launch {
            try {
                val tunnel = Application.getTunnelManager().getTunnels().firstOrNull { it.name == tunnelName }
                if (tunnel != null && tunnel.state != Tunnel.State.UP) {
                    tunnel.setStateAsync(Tunnel.State.UP)
                }
                refreshOnce()
            } catch (e: Throwable) {
                Toast.makeText(
                    this@DashboardActivity,
                    getString(R.string.dashboard_vpn_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val qrImportLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents ?: return@registerForActivityResult
        lifecycleScope.launch {
            TunnelImporter.importTunnel(supportFragmentManager, qrCode) { message ->
                lifecycleScope.launch {
                    val imported = try {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        tunnels.firstOrNull { it.name !in tunnelNamesBeforeImport }
                    } catch (_: Throwable) {
                        null
                    }
                    if (imported != null) {
                        showTunnelPage()
                    } else {
                        Toast.makeText(this@DashboardActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val confImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            TunnelImporter.importTunnel(contentResolver, uri) { message ->
                lifecycleScope.launch {
                    val imported = try {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        tunnels.firstOrNull { it.name !in tunnelNamesBeforeImport }
                    } catch (_: Throwable) {
                        null
                    }
                    if (imported != null) {
                        showTunnelPage()
                    } else {
                        Toast.makeText(this@DashboardActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun launchConfImport() {
        lifecycleScope.launch {
            tunnelNamesBeforeImport = try {
                Application.getTunnelManager().getTunnels().map { it.name }.toSet()
            } catch (_: Throwable) {
                emptySet()
            }
            confImportLauncher.launch("*/*")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_dashboard)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        bindViews()
        setupTunnelPage()
        ServiceLatencyResolver.init(
            R.id.youtube_ping, R.id.instagram_ping,
            R.id.whatsapp_ping, R.id.telegram_ping
        )
        historyStore = TrafficHistoryStore(applicationContext)
        metaStore = ProfileMetaStore(applicationContext)
        connectionSessionStore = ConnectionSessionStore(applicationContext)
        setupActions()
        lifecycleScope.launch {
            applyTheme(AppTheme.fromKey(UserKnobs.appTheme.first()))
            maybeAutoConnectAtStartup()
            refreshLoop()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            applyTheme(AppTheme.fromKey(UserKnobs.appTheme.first()))
            refreshOnce()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // AppCompat changes uiMode when switching between LIGHT and dark themes.
        // The dashboard has its own palette, so re-apply it without recreating the Activity.
        applyPalette(currentPalette())
        applyTunnelPagePalette()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (kotlin.math.abs(dx) > 180f && kotlin.math.abs(dy) < 120f) {
                    if (dx < 0f) {
                        showTunnelPage()
                    } else {
                        showDashboardPage()
                    }
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        egressCountryJob?.cancel()
        serverLatencyJob?.cancel()
        serviceTestJobs.values.forEach { it.cancel() }
        stopStatusDotBlink()
        super.onDestroy()
    }

    private fun bindViews() {
        root = findViewById(R.id.dashboard_root)
        statusDot = findViewById(R.id.status_dot)
        statusTitle = findViewById(R.id.status_title)
        statusSubtitle = findViewById(R.id.status_subtitle)
        activeTimeMain = findViewById(R.id.active_time_main)
        profileExpiry = findViewById(R.id.profile_expiry)
        rxValue = findViewById(R.id.rx_value)
        txValue = findViewById(R.id.tx_value)
        totalValue = findViewById(R.id.total_value)
        connectButton = findViewById(R.id.connect_button)
        addProfileButton = findViewById(R.id.add_profile_button)
        serverCard = findViewById(R.id.server_card)
        connectionCard = findViewById(R.id.connection_card)
        trafficCard = findViewById(R.id.traffic_card)
        serviceSection = findViewById(R.id.section_quick_actions)
        serviceRow1 = findViewById(R.id.quick_services_row1)
        bottomNav = findViewById(R.id.bottom_navigation)
        dashboardPage = findViewById(R.id.dashboard_page)
        tunnelPage = findViewById(R.id.tunnel_page)
        tunnelListContainer = findViewById(R.id.tunnel_list_container)
    }

    private fun setupActions() {
        findViewById<View>(R.id.feedback_button).setOnClickListener {
            showFeedbackMenu()
        }
        findViewById<View>(R.id.share_button).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${getString(R.string.dashboard_share_text)}\n\n$GITHUB_URL")
            }
            startActivity(Intent.createChooser(share, getString(R.string.dashboard_share_title)))
        }
        findViewById<TextView>(R.id.brand_title).text = "WH78"
        setupSettingsButton()
        findViewById<View>(R.id.quick_youtube_card).setOnClickListener { runServiceTest(ServiceLatencyResolver.YOUTUBE) }
        findViewById<View>(R.id.quick_instagram_card).setOnClickListener { runServiceTest(ServiceLatencyResolver.INSTAGRAM) }
        findViewById<View>(R.id.quick_whatsapp_card).setOnClickListener { runServiceTest(ServiceLatencyResolver.WHATSAPP) }
        findViewById<View>(R.id.quick_telegram_card).setOnClickListener { runServiceTest(ServiceLatencyResolver.TELEGRAM) }
        serverCard.setOnClickListener { runServerLatencyTest(force = true) }
        trafficCard.isClickable = true
        trafficCard.isFocusable = true
        trafficCard.contentDescription = "Трафик. Нажмите, чтобы открыть историю"
        trafficCard.setOnClickListener { showTrafficHistoryDialog() }
        connectButton.setOnClickListener {
            val tunnel = activeTunnel
            if (tunnel == null) {
                showAddProfileMenu()
            } else if (tunnel.state == Tunnel.State.UP) {
                lifecycleScope.launch {
                    try {
                        tunnel.setStateAsync(Tunnel.State.DOWN)
                        refreshOnce()
                    } catch (e: Throwable) {
                        Toast.makeText(this@DashboardActivity, getString(R.string.dashboard_vpn_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                connectTunnelWithPermission(tunnel)
            }
        }
        addProfileButton.setOnClickListener { showAddProfileMenu() }
        findViewById<TextView>(R.id.brand_title).setOnClickListener { cycleTheme() }
        findViewById<TextView>(R.id.brand_build).text = BuildConfig.MYVPN_BUILD
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showDashboardPage(); true }
                R.id.nav_apps -> { openApplicationsSettings(); false }
                R.id.nav_add -> { showAddProfileMenu(); false }
                R.id.nav_settings -> { showTunnelPage(); true }
                else -> false
            }
        }
        bottomNav.setOnTouchListener(object : View.OnTouchListener {
            private var downTime = 0L
            private var longPress = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val itemWidth = if (v.width > 0) v.width / 4f else 0f
                val inMore = itemWidth > 0f && event.x >= itemWidth * 3f
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downTime = System.currentTimeMillis()
                        longPress = false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (inMore && System.currentTimeMillis() - downTime >= 550L) {
                            longPress = true
                            showLegacyMenu(v)
                            return true
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> longPress = false
                }
                return longPress
            }
        })
    }


    private fun setupSettingsButton() {
        val share = findViewById<View>(R.id.share_button)
        val parent = share.parent as? ViewGroup ?: return
        settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_wh78_settings)
            background = null
            contentDescription = "Настройки"
            setPadding(dp(7), dp(7), dp(7), dp(7))
            imageTintList = ColorStateList.valueOf(currentPalette().text)
            minimumWidth = 0
            minimumHeight = 0
            setOnClickListener { showWh78Settings() }
        }
        val index = (0 until parent.childCount).firstOrNull { parent.getChildAt(it) === share }
            ?.plus(1) ?: parent.childCount
        val lp = ViewGroup.MarginLayoutParams(dp(40), dp(40)).apply {
            leftMargin = dp(4)
        }
        parent.addView(settingsButton, index.coerceAtMost(parent.childCount), lp)
    }

    private fun showWh78Settings() {
        Wh78SettingsDialog(
            activity = this,
            palette = Wh78SettingsDialog.Palette(
                background = currentPalette().background,
                surface = currentPalette().surface,
                text = currentPalette().text,
                secondaryText = currentPalette().secondaryText,
                accent = currentPalette().accent,
                accentText = currentPalette().accentText,
                outline = currentPalette().outline
            ),
            getActiveTunnel = { activeTunnel },
            onSplitTunneling = { tunnel -> showAppPicker(tunnel) },
            onThemeChanged = { theme ->
                applyTheme(theme)
                val p = currentPalette()
                Wh78SettingsDialog.Palette(
                    background = p.background,
                    surface = p.surface,
                    text = p.text,
                    secondaryText = p.secondaryText,
                    accent = p.accent,
                    accentText = p.accentText,
                    outline = p.outline
                )
            },
            onChanged = { refreshOnce() }
        ).show()
    }

    private fun maybeAutoConnectAtStartup() {
        if (autoConnectAttempted || !Wh78SettingsDialog.isAutoConnectEnabled(this)) return
        autoConnectAttempted = true
        lifecycleScope.launch {
            delay(250)
            try {
                val tunnels = Application.getTunnelManager().getTunnels()
                val tunnel = tunnels.firstOrNull { it.state == Tunnel.State.UP }
                    ?: Application.getTunnelManager().lastUsedTunnel
                    ?: tunnels.firstOrNull()
                if (tunnel != null && tunnel.state != Tunnel.State.UP) {
                    connectTunnelWithPermission(tunnel)
                }
            } catch (_: Throwable) {
                // Startup auto-connect is best-effort; manual connection remains available.
            }
        }
    }

    private fun showLegacyMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_activity, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_settings -> {
                    startActivity(Intent(this, org.amnezia.awg.activity.SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setupTunnelPage() {
        if (supportFragmentManager.findFragmentByTag(TUNNEL_LIST_TAG) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tunnel_list_container, TunnelListFragment(), TUNNEL_LIST_TAG)
                .commitNow()
        }
        showDashboardPage(animate = false)
    }

    private fun showDashboardPage(animate: Boolean = true) {
        switchPage(Page.DASHBOARD, animate)
    }

    private fun showTunnelPage(animate: Boolean = true) {
        switchPage(Page.TUNNELS, animate)
    }

    private fun switchPage(target: Page, animate: Boolean) {
        if (!::dashboardPage.isInitialized || !::tunnelPage.isInitialized) return

        if (target == currentPage) {
            bottomNav.menu.findItem(
                if (target == Page.DASHBOARD) R.id.nav_home else R.id.nav_settings
            )?.isChecked = true
            applyTunnelPagePalette()
            return
        }

        val outgoing = if (target == Page.TUNNELS) dashboardPage else tunnelPage
        val incoming = if (target == Page.TUNNELS) tunnelPage else dashboardPage
        val direction = if (target == Page.TUNNELS) 1f else -1f

        bottomNav.menu.findItem(
            if (target == Page.DASHBOARD) R.id.nav_home else R.id.nav_settings
        )?.isChecked = true

        if (!animate) {
            outgoing.animate().cancel()
            incoming.animate().cancel()
            outgoing.alpha = 1f
            outgoing.translationX = 0f
            incoming.alpha = 1f
            incoming.translationX = 0f
            outgoing.visibility = View.GONE
            incoming.visibility = View.VISIBLE
            currentPage = target
            applyTunnelPagePalette()
            return
        }

        val width = (root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        outgoing.animate().cancel()
        incoming.animate().cancel()

        incoming.visibility = View.VISIBLE
        incoming.alpha = 1f
        incoming.translationX = direction * width
        outgoing.alpha = 1f
        outgoing.translationX = 0f

        val interpolator = DecelerateInterpolator(1.5f)
        incoming.animate()
            .translationX(0f)
            .setDuration(280L)
            .setInterpolator(interpolator)
            .start()

        outgoing.animate()
            .translationX(-direction * width)
            .setDuration(280L)
            .setInterpolator(interpolator)
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
                outgoing.alpha = 1f
            }
            .start()

        currentPage = target
        applyTunnelPagePalette()
    }

    fun applyTunnelPagePalette() {
        if (!::tunnelPage.isInitialized) return
        val p = currentPalette()
        tunnelPage.setBackgroundColor(p.background)
        tunnelListContainer.setBackgroundColor(p.background)
        tunnelPage.findViewById<TextView>(R.id.tunnel_page_title)?.apply {
            setTextColor(p.text)
        }
        val fragment = supportFragmentManager.findFragmentByTag(TUNNEL_LIST_TAG)
        val view = fragment?.view ?: return
        view.setBackgroundColor(p.background)
        val list = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tunnel_list)
        list.setBackgroundColor(p.background)
        list.post {
            for (i in 0 until list.childCount) {
                val item = list.getChildAt(i)
                item.findViewById<TextView>(R.id.tunnel_name)?.setTextColor(p.text)
                item.findViewById<TextView>(R.id.tunnel_status)?.setTextColor(p.positive)
                item.findViewById<org.amnezia.awg.widget.ToggleSwitch>(R.id.tunnel_switch)?.apply {
                    val switchStates = arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    )
                    val inactiveThumb = if (lastTheme == AppTheme.LIGHT) {
                        Color.rgb(150, 158, 164)
                    } else {
                        p.secondaryText
                    }
                    thumbTintList = android.content.res.ColorStateList(
                        switchStates,
                        intArrayOf(p.accent, inactiveThumb)
                    )
                    trackTintList = android.content.res.ColorStateList(
                        switchStates,
                        intArrayOf(
                            Color.argb(85, Color.red(p.accent), Color.green(p.accent), Color.blue(p.accent)),
                            p.surface2
                        )
                    )
                }
            }
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?): Boolean {
        return true
    }

    private fun connectTunnelWithPermission(tunnel: ObservableTunnel) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            // Android requires explicit user consent the first time this app creates a VPN.
            // Launch the system confirmation directly from WoOh78 instead of sending the user
            // through the original AmneziaWG tunnel screen.
            pendingConnectTunnelName = tunnel.name
            vpnPermissionLauncher.launch(prepareIntent)
            return
        }

        lifecycleScope.launch {
            try {
                tunnel.setStateAsync(Tunnel.State.UP)
                refreshOnce()
            } catch (e: Throwable) {
                Toast.makeText(
                    this@DashboardActivity,
                    getString(R.string.dashboard_vpn_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun refreshLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (true) {
                refreshOnce()
                delay(1000)
            }
        }
    }

    private fun refreshOnce() {
        lifecycleScope.launch {
            try {
                val tunnels = Application.getTunnelManager().getTunnels()
                val chosen = tunnels.firstOrNull { it.state == Tunnel.State.UP }
                    ?: Application.getTunnelManager().lastUsedTunnel
                    ?: tunnels.firstOrNull()
                activeTunnel = chosen
                render(chosen)
            } catch (_: Throwable) {
                render(null)
            }
        }
    }

    private fun stopDiagnostics() {
        serverLatencyJob?.cancel()
        serverLatencyJob = null
        serviceTestJobs.values.forEach { it.cancel() }
        serviceTestJobs.clear()
        findViewById<TextView>(R.id.server_ping).text = "Пинг до сервера: —"
        findViewById<TextView>(R.id.youtube_ping).text = "—"
        findViewById<TextView>(R.id.instagram_ping).text = "—"
        findViewById<TextView>(R.id.whatsapp_ping).text = "—"
        findViewById<TextView>(R.id.telegram_ping).text = "—"
    }

    private fun startServerLatencyDetection(tunnel: ObservableTunnel) {
        if (serverLatencyJob?.isActive == true) return
        serverLatencyJob = lifecycleScope.launch {
            while (isActive) {
                runServerLatencyTest(force = false, tunnel = tunnel)
                delay(SERVER_PING_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun runServerLatencyTest(force: Boolean, tunnel: ObservableTunnel? = activeTunnel) {
        if (tunnel == null || tunnel.state != Tunnel.State.UP ||
            tunnel.connectionStatus != ObservableTunnel.ConnectionStatus.CONNECTED) {
            if (force) Toast.makeText(this, "Сначала подключите VPN", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            findViewById<TextView>(R.id.server_ping).text = "Пинг до сервера: проверка…"
            val latency = withContext(Dispatchers.IO) { ServerLatencyResolver.resolve(tunnel) }
            if (activeTunnel?.name == tunnel.name && tunnel.state == Tunnel.State.UP) {
                findViewById<TextView>(R.id.server_ping).text =
                    latency?.let { "Пинг до сервера: ${it} мс" } ?: "Пинг до сервера: —"
            }
        }
    }

    private fun runServiceTest(service: ServiceLatencyResolver.Service) {
        val tunnel = activeTunnel
        if (tunnel == null || tunnel.state != Tunnel.State.UP ||
            tunnel.connectionStatus != ObservableTunnel.ConnectionStatus.CONNECTED) {
            Toast.makeText(this, "Сначала подключите VPN", Toast.LENGTH_SHORT).show()
            return
        }
        serviceTestJobs[service.id]?.cancel()
        successfulServices.remove(service.id)
        setServiceCardBackground(service.id, false)
        val pingView = findViewById<TextView>(service.pingViewId)
        pingView.text = "Проверка…"
        serviceTestJobs[service.id] = lifecycleScope.launch {
            val latency = withContext(Dispatchers.IO) { ServiceLatencyResolver.resolve(this@DashboardActivity, service) }
            if (activeTunnel?.name == tunnel.name && tunnel.state == Tunnel.State.UP) {
                val success = latency != null
                if (success) successfulServices.add(service.id) else successfulServices.remove(service.id)
                pingView.text = latency?.let { "${it} мс" } ?: "Нет связи"
                setServiceCardBackground(service.id, success)
            }
        }
    }

    private fun stopEgressDetection() {
        egressCountryJob?.cancel()
        egressCountryJob = null
        egressTunnelName = null
        egressCountry = null
    }

    private fun startEgressDetection(tunnelName: String) {
        if (egressTunnelName != tunnelName) {
            egressCountryJob?.cancel()
            egressCountryJob = null
            egressTunnelName = tunnelName
            egressCountry = null
            findViewById<TextView>(R.id.server_country).text = "Точка выхода: определяем…"
        }
        if (egressCountryJob?.isActive == true) return

        egressCountryJob = lifecycleScope.launch {
            while (isActive) {
                val detected = withContext(Dispatchers.IO) { EgressCountryResolver.resolve(this@DashboardActivity) }
                if (!detected.isNullOrBlank()) {
                    egressCountry = detected
                    if (activeTunnel?.name == tunnelName) {
                        findViewById<TextView>(R.id.server_country).text = formatCountry(detected)
                    }
                }
                delay(EGRESS_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun render(tunnel: ObservableTunnel?) {
        if (tunnel == null) {
            stopEgressDetection()
            stopDiagnostics()
            connectionStartedElapsed = null
            stopProfileExpiryRefresh()
            profileExpiry.visibility = View.GONE
            findViewById<View>(R.id.profile_expiry_bar).visibility = View.GONE
            statusDot.text = "●"
            statusTitle.text = getString(R.string.dashboard_no_profile)
            statusSubtitle.text = "Импортируйте QR-код или .conf"
            findViewById<TextView>(R.id.dashboard_title).text = "—"
            profileExpiry.visibility = View.GONE
            findViewById<TextView>(R.id.server_country).text = "Точка выхода: —"
            activeTimeMain.text = "—"
            rxValue.text = "—"
            txValue.text = "—"
            totalValue.text = "—"
            connectButton.text = "Добавить профиль"
            connectButton.isEnabled = true
            addProfileButton.visibility = View.GONE
            connectButton.layoutParams = connectButton.layoutParams.apply { height = dp(58) }
            setDiagnosticsVisibility(false)
            successfulServices.clear()
            resetServiceCardBackgrounds()
            return
        }

        addProfileButton.visibility = View.GONE
        connectButton.layoutParams = connectButton.layoutParams.apply { height = dp(48) }
        setDiagnosticsVisibility(tunnel.state == Tunnel.State.UP)
        val config = try { tunnel.getConfigAsync() } catch (_: Throwable) { null }
        findViewById<TextView>(R.id.dashboard_title).text = tunnel.name

        // Show the last known subscription state immediately, even while the VPN is disconnected.
        profileExpiryTunnelName = tunnel.name
        profileExpiresAt = metaStore.get(tunnel.name).expiresAt
        renderProfileExpiry()

        if (tunnel.state == Tunnel.State.UP &&
                tunnel.connectionStatus == ObservableTunnel.ConnectionStatus.CONNECTED
        ) {
            startProfileExpiryRefresh(tunnel.name)
        } else {
            stopProfileExpiryRefresh()
            profileExpiryTunnelName = tunnel.name
            profileExpiresAt = metaStore.get(tunnel.name).expiresAt
            renderProfileExpiry()
        }
        if (tunnel.connectionStatus == ObservableTunnel.ConnectionStatus.CONNECTED && tunnel.state == Tunnel.State.UP) {
            startEgressDetection(tunnel.name)
            startServerLatencyDetection(tunnel)
            findViewById<TextView>(R.id.server_country).text = egressCountry?.let(::formatCountry) ?: "Точка выхода: определяем…"
        } else {
            stopEgressDetection()
            stopDiagnostics()
            successfulServices.clear()
            resetServiceCardBackgrounds()
            findViewById<TextView>(R.id.server_country).text = "Точка выхода: —"
        }

        when {
            tunnel.state != Tunnel.State.UP -> {
                scheduleConnectionSessionClear(tunnel.name)
                connectionStartedElapsed = connectionSessionStore.peek(tunnel.name)
                stopStatusDotBlink()
                statusDot.setTextColor(currentPalette().negative)
                statusDot.alpha = 1f
                statusDot.text = "●"
                statusTitle.text = getString(R.string.dashboard_disconnected)
                statusSubtitle.text = getString(R.string.dashboard_disconnected_hint)
                activeTimeMain.text = "—"
                connectButton.text = getString(R.string.dashboard_connect)
                connectButton.isEnabled = true
                showStats(tunnel)
            }
            tunnel.connectionStatus != ObservableTunnel.ConnectionStatus.CONNECTED -> {
                disconnectClearJob?.cancel()
                disconnectClearJob = null
                ensureConnectionSession(tunnel.name)
                stopStatusDotBlink()
                statusDot.setTextColor(Color.rgb(255, 181, 72))
                statusDot.alpha = 1f
                statusDot.text = "●"
                statusTitle.text = getString(R.string.dashboard_connecting)
                statusSubtitle.text = getString(R.string.dashboard_connecting_hint)
                activeTimeMain.text = formatElapsed(connectionStartedElapsed!!)
                connectButton.text = getString(R.string.dashboard_cancel)
                connectButton.isEnabled = true
                showStats(tunnel)
            }
            else -> {
                disconnectClearJob?.cancel()
                disconnectClearJob = null
                ensureConnectionSession(tunnel.name)
                statusDot.setTextColor(currentPalette().positive)
                statusDot.text = "●"
                startStatusDotBlink()
                statusTitle.text = getString(R.string.dashboard_connected)
                statusSubtitle.text = getString(R.string.dashboard_connected_hint)
                activeTimeMain.text = formatElapsed(connectionStartedElapsed!!)
                connectButton.text = getString(R.string.dashboard_disconnect)
                connectButton.isEnabled = true
                showStats(tunnel)
            }
        }
    }

    private fun startProfileExpiryRefresh(tunnelName: String) {
        if (profileExpiryJob?.isActive == true &&
            profileExpiryTunnelName == tunnelName
        ) {
            return
        }

        stopProfileExpiryRefresh()

        profileExpiryTunnelName = tunnelName
        profileExpiresAt = metaStore.get(tunnelName).expiresAt
        renderProfileExpiry()

        profileExpiryJob = lifecycleScope.launch {
            while (isActive) {
                val info = ProfileApi.getProfileInfo()

                if (isActive && profileExpiryTunnelName == tunnelName && info != null) {
                    profileExpiresAt = info.expiresAt
                    metaStore.setExpiry(tunnelName, info.expiresAt)
                    renderProfileExpiry()
                }

                delay(15 * 60 * 1000L)
            }
        }
    }

    private fun stopProfileExpiryRefresh() {
        profileExpiryJob?.cancel()
        profileExpiryJob = null
        profileExpiryTunnelName = null
        profileExpiresAt = null
    }

    private fun renderProfileExpiry() {
        val bar = findViewById<View>(R.id.profile_expiry_bar)
        if (activeTunnel == null) {
            bar.visibility = View.GONE
            profileExpiry.visibility = View.GONE
            return
        }
        val daysText = findViewById<TextView>(R.id.profile_expiry_days)
        val dateText = findViewById<TextView>(R.id.profile_expiry_date)
        val fill = findViewById<View>(R.id.profile_expiry_fill)
        val track = findViewById<View>(R.id.profile_expiry_track)
        val p = currentPalette()

        bar.visibility = View.VISIBLE
        profileExpiry.visibility = View.GONE

        val expiresAt = profileExpiresAt
        val gold = Color.rgb(218, 172, 70)
        val now = System.currentTimeMillis()

        if (expiresAt == null) {
            daysText.text = "Без срока"
            dateText.text = "∞"
            daysText.setTextColor(gold)
            dateText.setTextColor(gold)
            track.background = roundedColor(Color.argb(55, 218, 172, 70))
            fill.background = roundedColor(gold)
            setExpiryBarProgress(fill, track, 1f)
            return
        }

        // API returns expires_at as Unix seconds; the rest of Android date/time APIs use milliseconds.
        // Normalize here so the expiry bar, remaining time and formatted date all use one unit.
        val expiresAtMs = if (expiresAt < 100_000_000_000L) expiresAt * 1000L else expiresAt
        val remainingMs = expiresAtMs - now
        if (remainingMs <= 0L) {
            daysText.text = "Истёк"
            dateText.text = "до ${formatExpiryDate(expiresAtMs)}"
            daysText.setTextColor(p.negative)
            dateText.setTextColor(p.secondaryText)
            track.background = roundedColor(Color.argb(55, Color.red(p.negative), Color.green(p.negative), Color.blue(p.negative)))
            fill.background = roundedColor(p.negative)
            setExpiryBarProgress(fill, track, 0f)
            return
        }

        val remainingSeconds = remainingMs / 1000L
        val remainingHours = remainingSeconds / 3600L
        val remainingDays = remainingHours / 24L

        // The API currently returns only expires_at, without the original
        // profile lifetime. Do not calculate progress from the moment the
        // app first observed the profile: that makes a profile with 2 days
        // left look almost full. Instead use a stable visual scale where
        // 180 days remaining corresponds to a full bar.
        val progress = (remainingMs.toDouble() / (180L * 24L * 60L * 60L * 1000L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)

        val remainingLabel = if (remainingDays >= 1L) "$remainingDays дн." else "${maxOf(1L, remainingHours)} ч."
        val progressColor = when {
            progress < 0.15f -> Color.rgb(210, 48, 58)       // критически мало срока — красный
            progress < 0.30f -> Color.rgb(190, 132, 30)      // скоро закончится — тёмно-жёлтый
            else -> p.accent
        }
        daysText.text = "Осталось $remainingLabel"
        dateText.text = "до ${formatExpiryDate(expiresAtMs)}"
        daysText.setTextColor(progressColor)
        dateText.setTextColor(p.secondaryText)
        track.background = roundedColor(Color.argb(48, Color.red(progressColor), Color.green(progressColor), Color.blue(progressColor)))
        fill.background = roundedColor(progressColor)
        setExpiryBarProgress(fill, track, progress)
    }

    private fun Long?.orDefault(defaultValue: Long): Long = this ?: defaultValue

    private fun formatExpiryDate(timestamp: Long): String =
        SimpleDateFormat("d MMM", Locale("ru")).format(Date(timestamp))

    private fun roundedColor(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(color)
        }

    private fun setExpiryBarProgress(fill: View, track: View, fraction: Float) {
        track.post {
            val width = track.width
            if (width <= 0) return@post
            fill.layoutParams = fill.layoutParams.apply {
                this.width = (width * fraction).toInt().coerceAtLeast(if (fraction > 0f) dp(6) else 0)
                this.height = dp(6)
            }
            fill.requestLayout()
        }
    }

    private fun setDiagnosticsVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        trafficCard.visibility = visibility
        serviceSection.visibility = visibility
        serviceRow1.visibility = visibility
    }

    private fun startStatusDotBlink() {
        if (statusDotAnimator?.isRunning == true) return
        statusDotAnimator?.cancel()
        statusDotAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.35f).apply {
            duration = 1100L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopStatusDotBlink() {
        statusDotAnimator?.cancel()
        statusDotAnimator = null
        statusDot.alpha = 1f
    }

    private suspend fun showStats(tunnel: ObservableTunnel) {
        try {
            val statistics = tunnel.getStatisticsAsync()
            val rx = statistics.totalRx()
            val tx = statistics.totalTx()
            rxValue.text = formatTraffic(rx)
            txValue.text = formatTraffic(tx)
            totalValue.text = formatTraffic(rx + tx)
            historyStore.record(tunnel.name, rx, tx)
        } catch (_: Throwable) {
            rxValue.text = "0 Б"
            txValue.text = "0 Б"
            totalValue.text = "0 Б"
        }
    }

    private fun openConnectionSettings() {
        lifecycleScope.launch {
            val tunnel = activeTunnel ?: try {
                val tunnels = Application.getTunnelManager().getTunnels()
                tunnels.firstOrNull()
            } catch (_: Throwable) { null }
            startActivity(MainActivity.intentFor(this@DashboardActivity, tunnel?.name).apply { putExtra(MainActivity.EXTRA_FROM_DASHBOARD, true) })
        }
    }

    private fun showTrafficHistoryDialog() {
        val p = currentPalette()
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(p.surface)
            strokeWidth = dp(1)
            strokeColor = p.outline
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        card.addView(content, ViewGroup.LayoutParams(-1, -2))

        val title = TextView(this).apply {
            text = "История трафика"
            textSize = 24f
            setTextColor(p.text)
            setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        }
        content.addView(title, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        val periods = listOf(
            "Сегодня" to historyStore.today(),
            "Последние 7 дней" to historyStore.last7Days(),
            "Этот месяц" to historyStore.thisMonth(),
            "Всё время" to historyStore.allTime()
        )

        periods.forEachIndexed { index, (label, usage) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
            }
            val labelView = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(p.text)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
            val details = TextView(this).apply {
                text = "↓ ${formatTraffic(usage.rxBytes)}   ↑ ${formatTraffic(usage.txBytes)}\nВсего: ${formatTraffic(usage.totalBytes)}"
                textSize = 13f
                setTextColor(p.secondaryText)
                gravity = Gravity.END
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(details)
            content.addView(row)
            if (index < periods.lastIndex) {
                val divider = View(this).apply { setBackgroundColor(p.outline) }
                content.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }

        lateinit var dialog: Dialog
        val close = MaterialButton(this).apply {
            text = "Закрыть"
            textSize = 14f
            setTextColor(p.accent)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, LinearLayout.LayoutParams(-1, dp(48)))

        dialog = Dialog(this)
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f }
            setLayout((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)), -2)
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)), -2)
    }

    private fun showAppPicker(tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            try {
                val config = tunnel.getConfigAsync()
                var initiallyExcluded = true
                var selected = ArrayList<String?>(config.`interface`.getExcludedApplications())
                if (selected.isEmpty()) {
                    val includedApps = config.`interface`.getIncludedApplications()
                    if (includedApps.isNotEmpty()) {
                        initiallyExcluded = false
                        selected = ArrayList(includedApps)
                    }
                }

                supportFragmentManager.setFragmentResultListener(
                    AppListDialogFragment.REQUEST_SELECTION,
                    this@DashboardActivity
                ) { _, result ->
                    val apps = result.getStringArray(AppListDialogFragment.KEY_SELECTED_APPS)?.toList() ?: emptyList()
                    val useExcluded = result.getBoolean(AppListDialogFragment.KEY_IS_EXCLUDED)

                    lifecycleScope.launch {
                        try {
                            val proxy = ConfigProxy(config)
                            proxy.`interface`.excludedApplications.clear()
                            proxy.`interface`.includedApplications.clear()
                            if (useExcluded) {
                                proxy.`interface`.excludedApplications.addAll(apps)
                            } else {
                                proxy.`interface`.includedApplications.addAll(apps)
                            }
                            tunnel.setConfigAsync(proxy.resolve())
                            refreshOnce()
                            Toast.makeText(
                                this@DashboardActivity,
                                R.string.dashboard_tunneling_saved,
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Throwable) {
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(R.string.dashboard_tunneling_error, e.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                AppListDialogFragment
                    .newInstance(selected, initiallyExcluded)
                    .show(supportFragmentManager, "MYVPN_APP_PICKER")
            } catch (e: Throwable) {
                Toast.makeText(
                    this@DashboardActivity,
                    getString(R.string.dashboard_tunneling_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openApplicationsSettings() {
        val tunnel = activeTunnel
        if (tunnel == null) {
            Toast.makeText(this, "Сначала добавьте VPN-профиль", Toast.LENGTH_SHORT).show()
            showAddProfileMenu()
            return
        }
        showAppPicker(tunnel)
    }

    private fun showFeedbackMenu() {
        val p = currentPalette()
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(p.surface)
            strokeWidth = dp(1)
            strokeColor = p.outline
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        card.addView(content, ViewGroup.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "Обратная связь"
            textSize = 22f
            setTextColor(p.text)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, -2))

        lateinit var dialog: Dialog
        val telegramButton = MaterialButton(this).apply {
            text = "Telegram — написать боту"
            textSize = 15f
            setTextColor(p.accentText)
            backgroundTintList = ColorStateList.valueOf(p.accent)
            cornerRadius = dp(26)
            insetTop = 0
            insetBottom = 0
            setIconResource(R.drawable.ic_feedback_telegram)
            iconTint = ColorStateList.valueOf(p.accentText)
            iconSize = dp(24)
            iconPadding = dp(10)
            setOnClickListener {
                dialog.dismiss()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_TELEGRAM_URL)))
                } catch (_: Exception) {
                    Toast.makeText(this@DashboardActivity, getString(R.string.dashboard_feedback_unavailable), Toast.LENGTH_SHORT).show()
                }
            }
        }
        content.addView(telegramButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })

        val emailButton = MaterialButton(this).apply {
            text = "Написать по почте"
            textSize = 15f
            setTextColor(p.accentText)
            backgroundTintList = ColorStateList.valueOf(p.accent)
            cornerRadius = dp(26)
            insetTop = 0
            insetBottom = 0
            setIconResource(R.drawable.ic_feedback_email)
            iconTint = ColorStateList.valueOf(p.accentText)
            iconSize = dp(24)
            iconPadding = dp(10)
            setOnClickListener {
                dialog.dismiss()
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:wooh@tutamail.com")))
                } catch (_: Exception) {
                    Toast.makeText(this@DashboardActivity, "Не удалось открыть почтовое приложение", Toast.LENGTH_SHORT).show()
                }
            }
        }
        content.addView(emailButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })

        val close = MaterialButton(this).apply {
            text = "Закрыть"
            textSize = 14f
            setTextColor(p.accent)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, LinearLayout.LayoutParams(-1, dp(48)))

        dialog = Dialog(this)
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)), -2)
    }

    private fun showAddProfileMenu() {
        val p = currentPalette()
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(p.surface)
            strokeWidth = dp(1)
            strokeColor = p.outline
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        card.addView(content, ViewGroup.LayoutParams(-1, -2))

        val title = TextView(this).apply {
            text = "Добавить профиль"
            textSize = 22f
            setTextColor(p.text)
        }
        content.addView(title, LinearLayout.LayoutParams(-1, -2))

        lateinit var dialog: Dialog

        val fileButton = MaterialButton(this).apply {
            text = "Добавить .conf файл"
            textSize = 15f
            setTextColor(p.accentText)
            backgroundTintList = android.content.res.ColorStateList.valueOf(p.accent)
            cornerRadius = dp(26)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                dialog.dismiss()
                launchConfImport()
            }
        }
        content.addView(fileButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })

        val qrButton = MaterialButton(this).apply {
            text = "Добавить через QR-код"
            textSize = 15f
            setTextColor(p.accentText)
            backgroundTintList = android.content.res.ColorStateList.valueOf(p.accent)
            cornerRadius = dp(26)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    tunnelNamesBeforeImport = try {
                        Application.getTunnelManager().getTunnels().map { it.name }.toSet()
                    } catch (_: Throwable) {
                        emptySet()
                    }
                    qrImportLauncher.launch(
                        ScanOptions()
                            .setOrientationLocked(false)
                            .setBeepEnabled(false)
                            .setPrompt(getString(R.string.qr_code_hint))
                    )
                }
            }
        }
        content.addView(qrButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })

        val close = MaterialButton(this).apply {
            text = "Закрыть"
            textSize = 14f
            setTextColor(p.accent)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, LinearLayout.LayoutParams(-1, dp(48)))

        dialog = Dialog(this)
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280)), -2)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private suspend fun applyTheme(theme: AppTheme) {
        if (theme == lastTheme) {
            applyPalette(DashboardPalette.forTheme(theme))
            return
        }
        lastTheme = theme
        applyPalette(DashboardPalette.forTheme(theme))
    }

    private fun cycleTheme() {
        lifecycleScope.launch {
            val current = AppTheme.fromKey(UserKnobs.appTheme.first())
            val themes = AppTheme.entries
            val next = themes[(themes.indexOf(current) + 1) % themes.size]
            UserKnobs.setAppTheme(next.key)
            applyTheme(next)
        }
    }

    private fun currentPalette(): DashboardPalette = DashboardPalette.forTheme(lastTheme)

    private fun resetServiceCardBackgrounds() {
        listOf(
            ServiceLatencyResolver.YOUTUBE.id, ServiceLatencyResolver.INSTAGRAM.id,
            ServiceLatencyResolver.WHATSAPP.id, ServiceLatencyResolver.TELEGRAM.id
        ).forEach { setServiceCardBackground(it, false) }
    }

    private fun setServiceCardBackground(serviceId: String, success: Boolean) {
        val cardId = when (serviceId) {
            "youtube" -> R.id.quick_youtube_card
            "instagram" -> R.id.quick_instagram_card
            "whatsapp" -> R.id.quick_whatsapp_card
            "telegram" -> R.id.quick_telegram_card
            else -> return
        }
        val card = findViewById<MaterialCardView>(cardId)
        val p = currentPalette()
        val isDarkTheme = lastTheme != AppTheme.LIGHT
        if (success && isDarkTheme) {
            card.setCardBackgroundColor(p.surface2)
            card.strokeWidth = dp(2)
            card.strokeColor = Color.argb(190, Color.red(p.positive), Color.green(p.positive), Color.blue(p.positive))
        } else if (success) {
            card.setCardBackgroundColor(Color.rgb(232, 247, 237))
            card.strokeWidth = dp(1)
            card.strokeColor = p.outline
        } else {
            card.setCardBackgroundColor(p.surface2)
            card.strokeWidth = dp(1)
            card.strokeColor = p.outline
        }
    }

    private fun applyPalette(p: DashboardPalette) {
        root.setBackgroundColor(p.background)
        window.statusBarColor = p.background
        window.navigationBarColor = p.surface
        val lightSystemBars = lastTheme == AppTheme.LIGHT
        window.decorView.systemUiVisibility = if (lightSystemBars) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            0
        }
        serverCard.setCardBackgroundColor(p.surface)
        serverCard.strokeWidth = 0
        connectionCard.setCardBackgroundColor(p.surface)
        connectionCard.strokeWidth = 0
        trafficCard.setCardBackgroundColor(p.surface)
        trafficCard.strokeWidth = 0
        listOf(
            ServiceLatencyResolver.YOUTUBE, ServiceLatencyResolver.INSTAGRAM,
            ServiceLatencyResolver.WHATSAPP, ServiceLatencyResolver.TELEGRAM
        ).forEach { service ->
            setServiceCardBackground(service.id, successfulServices.contains(service.id))
        }
        bottomNav.setBackgroundColor(p.surface)
        val navStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        // Keep the light theme navigation appearance from the stable builds:
        // soft light indicator + dark active label/icon. Dark themes keep the accent.
        val navActiveColor = if (lastTheme == AppTheme.LIGHT) p.text else p.accent
        val navActiveIndicator = if (lastTheme == AppTheme.LIGHT) {
            Color.rgb(220, 228, 244)
        } else {
            p.surface2
        }
        val navColors = intArrayOf(navActiveColor, p.secondaryText)
        bottomNav.itemIconTintList = android.content.res.ColorStateList(navStates, navColors)
        bottomNav.itemTextColor = android.content.res.ColorStateList(navStates, navColors)
        bottomNav.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(navActiveIndicator)
        bottomNav.itemRippleColor = android.content.res.ColorStateList.valueOf(
            Color.argb(40, Color.red(p.accent), Color.green(p.accent), Color.blue(p.accent))
        )
        connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(p.accent)
        connectButton.setTextColor(p.accentText)
        findViewById<View>(R.id.feedback_button).let { (it as TextView).setTextColor(p.text) }
        findViewById<View>(R.id.share_button).let { (it as TextView).setTextColor(p.text) }
        if (::settingsButton.isInitialized) {
            settingsButton.imageTintList = ColorStateList.valueOf(p.text)
        }
        val textIds = intArrayOf(R.id.brand_title, R.id.status_title, R.id.dashboard_title, R.id.active_time_main,
            R.id.rx_value, R.id.tx_value, R.id.total_value, R.id.section_traffic)
        textIds.forEach { findViewById<TextView>(it).setTextColor(p.text) }
        findViewById<TextView>(R.id.brand_build).setTextColor(p.accent)
        val secondaryIds = intArrayOf(R.id.brand_subtitle, R.id.status_subtitle, R.id.rx_label,
            R.id.tx_label, R.id.total_label)
        secondaryIds.forEach { findViewById<TextView>(it).setTextColor(p.secondaryText) }
        listOf(R.id.youtube_button, R.id.instagram_button, R.id.whatsapp_button, R.id.telegram_button).forEach { viewId ->
            findViewById<TextView>(viewId).setTextColor(p.text)
        }
        findViewById<TextView>(R.id.youtube_ping).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.instagram_ping).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.whatsapp_ping).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.telegram_ping).setTextColor(p.secondaryText)
        listOf(R.id.feedback_button, R.id.share_button).forEach { viewId ->
            findViewById<TextView>(viewId).compoundDrawableTintList = android.content.res.ColorStateList.valueOf(p.text)
        }
        listOf(R.id.rx_label, R.id.tx_label, R.id.total_label).forEach { viewId ->
            findViewById<TextView>(viewId).compoundDrawableTintList = android.content.res.ColorStateList.valueOf(p.accent)
            findViewById<TextView>(viewId).setTextColor(p.secondaryText)
        }
        findViewById<TextView>(R.id.server_country).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.server_ping).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.profile_label).setTextColor(p.secondaryText)
        findViewById<TextView>(R.id.active_time_label).setTextColor(p.secondaryText)
        profileExpiry.setTextColor(p.secondaryText)
        statusDot.setTextColor(if (activeTunnel?.connectionStatus == ObservableTunnel.ConnectionStatus.CONNECTED) p.positive else p.negative)
        findViewById<TextView>(R.id.dashboard_title).setTextColor(p.accent)
        applyTunnelPagePalette()
        renderProfileExpiry()
        findViewById<TextView>(R.id.section_quick_actions).let {
            it.setTextColor(p.text)
            it.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(p.accent)
        }
    }

    private fun formatTraffic(bytes: Long): String {
        val value = bytes.toDouble()
        val unit = when {
            bytes >= 1024L * 1024L * 1024L -> "ГБ"
            bytes >= 1024L * 1024L -> "МБ"
            bytes >= 1024L -> "КБ"
            else -> "Б"
        }
        val divisor = when (unit) {
            "ГБ" -> 1024.0 * 1024.0 * 1024.0
            "МБ" -> 1024.0 * 1024.0
            "КБ" -> 1024.0
            else -> 1.0
        }
        return String.format(Locale("ru"), "%.1f %s", value / divisor, unit)
    }

    private fun formatCountry(country: String?): CharSequence {
        if (country.isNullOrBlank()) return "Точка выхода: —"
        val normalized = country.trim().lowercase(Locale.ROOT)
        val (flag, name) = when (normalized) {
            "россия", "russia", "ru" -> "🇷🇺" to "Россия"
            "польша", "poland", "pl" -> "🇵🇱" to "Польша"
            "швеция", "sweden", "se" -> "🇸🇪" to "Швеция"
            "финляндия", "finland", "fi" -> "🇫🇮" to "Финляндия"
            "германия", "germany", "de" -> "🇩🇪" to "Германия"
            "нидерланды", "netherlands", "nl" -> "🇳🇱" to "Нидерланды"
            "франция", "france", "fr" -> "🇫🇷" to "Франция"
            "великобритания", "united kingdom", "uk", "gb" -> "🇬🇧" to "Великобритания"
            "сша", "united states", "usa", "us" -> "🇺🇸" to "США"
            "чехия", "czechia", "czech republic", "cz" -> "🇨🇿" to "Чехия"
            "эстония", "estonia", "ee" -> "🇪🇪" to "Эстония"
            "латвия", "latvia", "lv" -> "🇱🇻" to "Латвия"
            "литва", "lithuania", "lt" -> "🇱🇹" to "Литва"
            else -> "🌍" to country.trim()
        }
        val text = SpannableStringBuilder("Точка выхода: $flag $name")
        val start = "Точка выхода: ".length
        text.setSpan(ForegroundColorSpan(currentPalette().accent), start, text.length, 0)
        text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, 0)
        return text
    }

    private fun scheduleConnectionSessionClear(tunnelName: String) {
        if (disconnectClearJob?.isActive == true) return
        disconnectClearJob = lifecycleScope.launch {
            delay(2000L)
            try {
                val current = Application.getTunnelManager().getTunnels().firstOrNull { it.name == tunnelName }
                if (current == null || current.state != Tunnel.State.UP) {
                    connectionSessionStore.clear(tunnelName)
                    connectionStartedElapsed = null
                }
            } catch (_: Throwable) {
                // Keep the existing session if the tunnel state cannot be read.
            }
        }
    }

    private fun ensureConnectionSession(tunnelName: String) {
        if (connectionStartedElapsed == null) {
            connectionStartedElapsed = connectionSessionStore.getOrCreate(
                tunnelName,
                SystemClock.elapsedRealtime()
            )
        }
    }

    private fun formatElapsed(startElapsed: Long): String {
        val elapsed = max(0L, SystemClock.elapsedRealtime() - startElapsed) / 1000
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun formatRemaining(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> resources.getQuantityString(R.plurals.dashboard_days_left, days.toInt(), days, hours)
            hours > 0 -> resources.getString(R.string.dashboard_hours_left, hours, minutes)
            else -> resources.getString(R.string.dashboard_minutes_left, minutes)
        }
    }

    private fun formatDate(timestamp: Long): String = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(timestamp))

    companion object {
        private const val TUNNEL_LIST_TAG = "MYVPN_TUNNEL_LIST"
        private const val FEEDBACK_TELEGRAM_URL = "http://t.me/WH78_official_bot"
        private const val GITHUB_URL = "https://github.com/WoOh78/WH78"
        private const val EGRESS_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        private const val SERVER_PING_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        fun intent(context: Context) = Intent(context, DashboardActivity::class.java)
    }
}
