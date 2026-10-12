package org.amnezia.awg.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.content.res.ColorStateList
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import org.amnezia.awg.R
import org.amnezia.awg.dashboard.DashboardActivity
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.fragment.TunnelDetailFragment
import org.amnezia.awg.fragment.TunnelEditorFragment

/** CRUD interface for AmneziaWG tunnels, presented as the WoOh78 tunnel-list page. */
class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null
    private lateinit var bottomNav: BottomNavigationView
    private var downX = 0f
    private var downY = 0f

    private fun handleBackPressed() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        if (isTwoPaneLayout && backStackEntries <= 1) {
            if (intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false)) {
                openDashboard()
            } else finish()
            return
        }
        if (backStackEntries >= 1) supportFragmentManager.popBackStack()
        if (backStackEntries == 1) selectedTunnel = null
    }

    override fun onBackStackChanged() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        backPressedCallback?.isEnabled = backStackEntries >= 1 || intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false)
        actionBar?.let { bar ->
            if (intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false)) {
                bar.title = "Список туннелей"
                bar.setDisplayHomeAsUpEnabled(true)
                bar.setHomeActionContentDescription("Вернуться в WoOh78")
            } else {
                val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
                bar.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
            }
        }
        bottomNav.menu.findItem(R.id.nav_tunneling)?.isChecked = backStackEntries == 0
        invalidateOptionsMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        actionBar?.title = "Список туннелей"
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        bottomNav = findViewById(R.id.bottom_navigation)
        applyBottomNavigationTheme()
        bottomNav.menu.findItem(R.id.nav_tunneling)?.isChecked = true
        setupBottomNavigation()
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        onBackStackChanged()
    }

    private fun applyBottomNavigationTheme() {
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0)
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0)
        val secondary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, primary)
        bottomNav.setBackgroundColor(surface)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val colors = intArrayOf(primary, secondary)
        bottomNav.itemIconTintList = ColorStateList(states, colors)
        bottomNav.itemTextColor = ColorStateList(states, colors)
        bottomNav.itemRippleColor = ColorStateList.valueOf(primary and 0x66FFFFFF)
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    openDashboard()
                    true
                }
                R.id.nav_tunneling -> true
                R.id.nav_history -> {
                    openDashboard()
                    false
                }
                R.id.nav_settings -> {
                    // Normal tap stays on the tunnel page. Long tap opens the legacy menu.
                    true
                }
                else -> false
            }
        }
        bottomNav.setOnTouchListener(object : View.OnTouchListener {
            private var longPress = false
            private var downTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val itemWidth = if (v.width > 0) v.width / 4f else 0f
                val inMore = itemWidth > 0f && event.x >= itemWidth * 3f
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downTime = System.currentTimeMillis()
                        longPress = false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (inMore && System.currentTimeMillis() - downTime >= LONG_PRESS_MS) {
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

    private fun showLegacyMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_activity, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openDashboard() {
        startActivity(DashboardActivity.intent(this).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
        if (intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false)) finish()
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
                if (dx > SWIPE_DISTANCE && kotlin.math.abs(dy) < SWIPE_VERTICAL_TOLERANCE) {
                    openDashboard()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // The tunnel-list page no longer exposes the old three-dot menu.
        if (supportFragmentManager.backStackEntryCount == 0 && intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false)) {
            return true
        }
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (intent.getBooleanExtra(EXTRA_FROM_DASHBOARD, false)) openDashboard()
                else onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.menu_action_edit -> {
                supportFragmentManager.commit {
                    replace(R.id.detail_container, TunnelEditorFragment())
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    addToBackStack(null)
                }
                true
            }
            R.id.menu_action_save -> false
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) return false
        val backStackEntries = fragmentManager.backStackEntryCount
        if (newTunnel == null) {
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        if (backStackEntries == 2) {
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            fragmentManager.commit {
                add(R.id.detail_container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }

    companion object {
        private const val LONG_PRESS_MS = 550L
        private const val SWIPE_DISTANCE = 180f
        private const val SWIPE_VERTICAL_TOLERANCE = 120f
        private const val EXTRA_SELECTED_TUNNEL = "selected_tunnel"
        const val EXTRA_FROM_DASHBOARD = "myvpn_from_dashboard"

        fun intentFor(context: Context, tunnelName: String?): Intent =
            Intent(context, MainActivity::class.java).apply {
                if (!tunnelName.isNullOrBlank()) putExtra(EXTRA_SELECTED_TUNNEL, tunnelName)
                putExtra(EXTRA_FROM_DASHBOARD, true)
            }
    }
}
