package org.amnezia.awg.dashboard

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.util.UserKnobs
import org.amnezia.awg.viewmodel.ConfigProxy

class Wh78SettingsDialog(
    private val activity: DashboardActivity,
    private var palette: Palette,
    private val getActiveTunnel: () -> ObservableTunnel?,
    private val onSplitTunneling: (ObservableTunnel) -> Unit,
    private val onThemeChanged: suspend (AppTheme) -> Palette,
    private val onChanged: () -> Unit
) {
    data class Palette(
        val background: Int,
        val surface: Int,
        val text: Int,
        val secondaryText: Int,
        val accent: Int,
        val accentText: Int,
        val outline: Int
    )

    private var dialog: Dialog? = null
    private var settingsScroll: ScrollView? = null
    private var settingsCard: MaterialCardView? = null
    private var themeSummaryView: TextView? = null
    private val paletteViews = mutableListOf<View>()

    fun show() {
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        settingsScroll = scroll
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        scroll.addView(content)

        val title = TextView(activity).apply {
            text = "Настройки"
            textSize = 24f
            tag = TAG_PRIMARY_TEXT
            paletteViews += this
            setTextColor(palette.text)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(title)

        addSection(content, "Подключение")
        val auto = MaterialSwitch(activity).apply {
            text = "Автоподключение при запуске"
            textSize = 15f
            tag = TAG_PRIMARY_TEXT
            paletteViews += this
            setTextColor(palette.text)
            isChecked = isAutoConnectEnabled(activity)
            setOnCheckedChangeListener { _, checked ->
                prefs(activity).edit().putBoolean(KEY_AUTOCONNECT, checked).apply()
            }
        }
        content.addView(auto, rowLp())

        addSection(content, "Сеть")
        addRow(content, "Раздельное туннелирование", "Выбор приложений для VPN или обхода VPN") {
            val tunnel = getActiveTunnel()
            if (tunnel == null) {
                toast("Сначала добавьте профиль")
            } else {
                dismiss()
                onSplitTunneling(tunnel)
            }
        }
        addRow(content, "Собственный DNS", dnsSummary()) {
            editNetworkSettings(dnsOnly = true)
        }
        addRow(content, "MTU", mtuSummary()) {
            editNetworkSettings(dnsOnly = false)
        }

        addSection(content, "Внешний вид")
        addRow(content, "Тема", themeSummary()) {
            chooseTheme()
        }

        addSection(content, "Обновление")
        addRow(content, "Проверить обновления", "GitHub · ${BuildConfig.MYVPN_BUILD}") {
            checkUpdates()
        }

        addSection(content, "О программе")
        addRow(content, "О WH78", "WH78 ${BuildConfig.MYVPN_BUILD}") {
            showAbout()
        }

        val close = TextView(activity).apply {
            text = "Закрыть"
            textSize = 15f
            tag = TAG_PRIMARY_TEXT
            paletteViews += this
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            setPadding(0, dp(5), 0, 0)
            setOnClickListener { dismiss() }
        }
        content.addView(close, LinearLayout.LayoutParams(-1, dp(40)))

        val card = MaterialCardView(activity).apply {
            radius = dp(26).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(palette.surface)
            strokeWidth = dp(1)
            strokeColor = palette.outline
            addView(scroll, ViewGroup.LayoutParams(-1, -2))
        }
        settingsCard = card

        dialog = Dialog(activity).apply {
            setContentView(card)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog?.show()
        dialog?.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(300)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        CoroutineScope(Dispatchers.Main).launch {
            themeSummaryView?.text = prettyThemeName(UserKnobs.appTheme.first())
        }
    }

    private fun addSection(parent: LinearLayout, text: String) {
        val section = TextView(activity).apply {
            this.text = text.uppercase()
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            tag = TAG_SECTION
            paletteViews += this
            setTextColor(palette.accent)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = sectionBackground(palette.accent)
        }
        parent.addView(section, LinearLayout.LayoutParams(-1, dp(28)).apply {
            topMargin = dp(2)
            bottomMargin = dp(4)
        })
    }

    private fun sectionBackground(accent: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

    private fun addRow(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val card = MaterialCardView(activity).apply {
            tag = TAG_ROW_CARD
            paletteViews += this
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(palette.background)
            strokeWidth = dp(1)
            strokeColor = palette.outline
            setOnClickListener { action() }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        box.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            tag = TAG_PRIMARY_TEXT
            paletteViews += this
            setTextColor(palette.text)
        })
        val subtitleView = TextView(activity).apply {
            text = subtitle
            textSize = 12f
            tag = TAG_SECONDARY_TEXT
            paletteViews += this
            setTextColor(palette.secondaryText)
            setPadding(0, dp(1), 0, 0)
        }
        if (title == "Тема") themeSummaryView = subtitleView
        box.addView(subtitleView)
        card.addView(box, ViewGroup.LayoutParams(-1, -2))
        parent.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(5)
        })
    }

    private fun chooseTheme() {
        // Match the WH78 logo tap: cycle to the next theme without closing/recreating this dialog.
        CoroutineScope(Dispatchers.Main).launch {
            val themes = AppTheme.entries
            val current = AppTheme.fromKey(UserKnobs.appTheme.first())
            val currentIndex = themes.indexOf(current).coerceAtLeast(0)
            val next = themes[(currentIndex + 1) % themes.size]
            UserKnobs.setAppTheme(next.key)
            palette = onThemeChanged(next)
            themeSummaryView?.text = prettyThemeName(next.key)
            applyPaletteToDialog()
            onChanged()
        }
    }

    private fun applyPaletteToDialog() {
        settingsScroll?.setBackgroundColor(Color.TRANSPARENT)
        settingsCard?.apply {
            setCardBackgroundColor(palette.surface)
            strokeColor = palette.outline
        }
        paletteViews.forEach { view ->
            when (view.tag as? String) {
                TAG_PRIMARY_TEXT -> (view as? TextView)?.setTextColor(palette.text)
                TAG_SECONDARY_TEXT -> (view as? TextView)?.setTextColor(palette.secondaryText)
                TAG_SECTION -> (view as? TextView)?.apply {
                    setTextColor(palette.accent)
                    background = sectionBackground(palette.accent)
                }
                TAG_ROW_CARD -> (view as? MaterialCardView)?.apply {
                    setCardBackgroundColor(palette.background)
                    strokeColor = palette.outline
                }
            }
        }
    }

    private fun prettyThemeName(key: String): String = when (key.lowercase()) {
        "light" -> "Светлая"
        "premium_dark", "premium-dark" -> "Premium Dark"
        "blue" -> "Blue"
        "cyberpunk" -> "Cyberpunk"
        else -> key.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun editNetworkSettings(dnsOnly: Boolean) {
        val tunnel = getActiveTunnel() ?: run {
            toast("Сначала добавьте профиль")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val config = try { tunnel.getConfigAsync() } catch (_: Throwable) { null }
            if (config == null) {
                toast("Не удалось открыть настройки профиля")
                return@launch
            }
            val proxy = ConfigProxy(config)
            val dialogContext = Wh78DialogTheme.wrap(activity)
            val field = EditText(dialogContext).apply {
                setText(if (dnsOnly) proxy.`interface`.dnsServers else proxy.`interface`.mtu)
                hint = if (dnsOnly) "Например: 1.1.1.1, 8.8.8.8" else "Например: 1280"
                textSize = 16f
                setSingleLine(true)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            MaterialAlertDialogBuilder(dialogContext)
                .setTitle(if (dnsOnly) "Собственный DNS" else "MTU")
                .setMessage(if (dnsOnly) "Серверы через запятую. Пустое значение — использовать DNS из профиля." else "Рекомендуемый диапазон: 1280–1420. Пустое значение — использовать значение из профиля.")
                .setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить") { _, _ ->
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            if (dnsOnly) proxy.`interface`.dnsServers = field.text.toString().trim()
                            else {
                                val mtu = field.text.toString().trim()
                                if (mtu.isNotEmpty()) {
                                    val n = mtu.toIntOrNull() ?: throw IllegalArgumentException("MTU должен быть числом")
                                    if (n !in 576..9000) throw IllegalArgumentException("MTU должен быть от 576 до 9000")
                                }
                                proxy.`interface`.mtu = mtu
                            }
                            tunnel.setConfigAsync(proxy.resolve())
                            onChanged()
                            toast("Настройки сохранены")
                        } catch (e: Throwable) {
                            toast(e.message ?: "Не удалось сохранить")
                        }
                    }
                }
                .show()
        }
    }

    private fun dnsSummary(): String = if (getActiveTunnel() == null) "Нет активного профиля" else "Настроить DNS для профиля"

    private fun mtuSummary(): String = if (getActiveTunnel() == null) "Нет активного профиля" else "Настроить MTU для профиля"

    private fun themeSummary(): String = "Выбрать тему"

    private fun checkUpdates() {
        toast("Проверяем GitHub…")
        Wh78Updater.checkAndOffer(activity, palette) { message -> toast(message) }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(Wh78DialogTheme.wrap(activity))
            .setTitle("О WH78")
            .setMessage(
                "WH78 ${BuildConfig.MYVPN_BUILD}\n\n" +
                    "AmneziaWG ${BuildConfig.AMNEZIAWG_VERSION_NAME}\n" +
                    "VPN client без регистрации.\n\n" +
                    "GitHub\nhttps://github.com/WoOh78/WH78"
            )
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("GitHub") { _, _ ->
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WoOh78/WH78"))) } catch (_: Throwable) { }
            }
            .show()
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    private fun dismiss() { dialog?.dismiss(); dialog = null }

    private fun rowLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) }
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG_PRIMARY_TEXT = "wh78-primary-text"
        private const val TAG_SECONDARY_TEXT = "wh78-secondary-text"
        private const val TAG_ROW_CARD = "wh78-row-card"
        private const val TAG_SECTION = "wh78-section"
        private const val PREFS = "wh78_settings"
        private const val KEY_AUTOCONNECT = "auto_connect"

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun isAutoConnectEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOCONNECT, false)
    }
}
