package org.amnezia.awg.dashboard

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import org.amnezia.awg.Application
import org.amnezia.awg.R

/**
 * Builds modal UI with the same user-selected WH78 theme as the dashboard.
 * DashboardActivity uses a custom palette instead of relying on Activity theme attrs,
 * so dialogs need an explicit theme wrapper for their views and Material components.
 */
object Wh78DialogTheme {
    fun wrap(context: Context): Context {
        val themeRes = when (Application.get().currentAppThemeKey.lowercase()) {
            "light" -> R.style.MyVpnLegacyLightTheme
            "blue" -> R.style.MyVpnLegacyBlueTheme
            "cyberpunk" -> R.style.MyVpnLegacyCyberpunkTheme
            else -> R.style.MyVpnLegacyPremiumDarkTheme
        }
        return ContextThemeWrapper(context, themeRes)
    }
}
