package org.amnezia.awg.dashboard

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
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
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.util.UserKnobs
import org.amnezia.awg.viewmodel.ConfigProxy

/**
 * WH78 settings page. It is hosted in a full-window dialog to preserve the existing
 * DashboardActivity callbacks while behaving visually like a normal full-screen page.
 */
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
    private var updateStatusView: TextView? = null
    private var updateBanner: MaterialCardView? = null
    private var updateBannerText: TextView? = null
    private var pendingRelease: Wh78Updater.Release? = null

    fun show() {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(20), dp(3))
            setBackgroundColor(palette.background)
        }
        val back = ImageButton(activity).apply {
            setImageResource(org.amnezia.awg.R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(palette.text)
            background = ColorDrawable(Color.TRANSPARENT)
            contentDescription = "Назад"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { dismiss() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(52)))
        toolbar.addView(TextView(activity).apply {
            text = "Настройки"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(palette.background)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(26))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addSection(content, "Подключение")
        addAutoConnectRow(content)

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
        addRow(
            content,
            "Расширенные настройки сети",
            "Собственный DNS и MTU активного профиля"
        ) { showAdvancedNetworkSettings() }

        addSection(content, "Внешний вид")
        addRow(content, "Тема оформления", "Выбрать цветовую схему WH78", showChevron = false) {
            chooseTheme()
        }

        addSection(content, "Обновление")
        addRow(content, "Проверить обновления", "Автоматическая проверка при открытии настроек", showChevron = false) {
            checkUpdates()
        }
        makeUpdateBanner(content)

        addSection(content, "О программе")
        addRow(
            content,
            "Версия приложения",
            "WH78 ${BuildConfig.MYVPN_BUILD} · AmneziaWG ${BuildConfig.AMNEZIAWG_VERSION_NAME}"
        ) { showAbout() }

        updateStatusView = TextView(activity).apply {
            text = "Установлена версия ${BuildConfig.MYVPN_BUILD} · проверяем обновления…"
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(dp(2), dp(16), dp(2), dp(6))
        }
        content.addView(updateStatusView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(page)
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }
        dialog?.show()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(palette.background))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setDimAmount(0f)
            statusBarColor = palette.background
            navigationBarColor = palette.background
            setGravity(Gravity.FILL)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            decorView.systemUiVisibility = if (isLightColor(palette.background)) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                0
            }
        }

        // Check quietly on entry; if a newer b-tag exists, show a banner instead of a surprise dialog.
        refreshUpdateStatus(showFeedback = false)
    }

    private fun addAutoConnectRow(parent: LinearLayout) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        row.addView(TextView(activity).apply {
            text = "Автоподключение при запуске"
            textSize = 15f
            setTextColor(palette.text)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val toggle = MaterialSwitch(activity).apply {
            text = null
            isChecked = isAutoConnectEnabled(activity)
            thumbTintList = ColorStateList(
                states,
                intArrayOf(palette.accent, Color.rgb(205, 214, 210))
            )
            trackTintList = ColorStateList(
                states,
                intArrayOf(palette.accent, palette.outline)
            )
            minimumWidth = dp(54)
            setPadding(dp(4), 0, 0, 0)
            contentDescription = "Автоподключение при запуске"
            setOnCheckedChangeListener { _, checked ->
                prefs(activity).edit().putBoolean(KEY_AUTOCONNECT, checked).apply()
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(62), dp(48)).apply {
            marginStart = dp(18)
        })
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply {
            bottomMargin = dp(7)
        })
    }

    private fun addSection(parent: LinearLayout, text: String) {
        val section = TextView(activity).apply {
            this.text = text.uppercase()
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(palette.accent)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
            background = sectionBackground(palette.accent)
        }
        parent.addView(section, LinearLayout.LayoutParams(-1, dp(29)).apply {
            topMargin = dp(4)
            bottomMargin = dp(5)
        })
    }

    private fun sectionBackground(accent: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(Color.argb(27, Color.red(accent), Color.green(accent), Color.blue(accent)))
    }

    private fun addRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        showChevron: Boolean = true,
        action: () -> Unit
    ) {
        val card = MaterialCardView(activity).apply {
            radius = dp(17).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(palette.surface)
            strokeWidth = dp(1)
            strokeColor = palette.outline
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(8), 0)
        }
        texts.addView(TextView(activity).apply {
            text = title
            textSize = 15.5f
            setTextColor(palette.text)
        })
        texts.addView(TextView(activity).apply {
            text = subtitle
            textSize = 12.5f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (showChevron) {
            row.addView(TextView(activity).apply {
                text = "›"
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(palette.accent)
            }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(row, ViewGroup.LayoutParams(-1, -2))
        parent.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
    }

    private fun makeUpdateBanner(parent: LinearLayout) {
        val card = MaterialCardView(activity).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(palette.surface)
            strokeWidth = dp(1)
            strokeColor = palette.accent
            visibility = View.GONE
            setOnClickListener {
                val release = pendingRelease ?: return@setOnClickListener
                Wh78Updater.offerUpdate(activity, palette, release) { message -> toast(message) }
            }
        }
        updateBannerText = TextView(activity).apply {
            text = "Доступна новая версия"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(updateBannerText, ViewGroup.LayoutParams(-1, -2))
        parent.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        updateBanner = card
    }

    private fun chooseTheme() {
        CoroutineScope(Dispatchers.Main).launch {
            val themes = AppTheme.entries
            val currentKey = UserKnobs.appTheme.first()
            val selected = themes.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
            val labels = themes.map { prettyThemeName(it.key) }.toTypedArray()
            MaterialAlertDialogBuilder(Wh78DialogTheme.wrap(activity))
                .setTitle("Тема оформления")
                .setSingleChoiceItems(labels, selected) { d, which ->
                    val theme = themes[which]
                    CoroutineScope(Dispatchers.Main).launch {
                        UserKnobs.setAppTheme(theme.key)
                        palette = onThemeChanged(theme)
                        onChanged()
                        d.dismiss()
                        dismiss()
                        show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun prettyThemeName(key: String): String = when (key.lowercase()) {
        "light" -> "Светлая"
        "premium_dark", "premium-dark" -> "Premium Dark"
        "blue" -> "Blue"
        "cyberpunk" -> "Cyberpunk"
        else -> key.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    /** The item list is intentionally not combined with setMessage(): AlertDialog can't show both. */
    private fun showAdvancedNetworkSettings() {
        MaterialAlertDialogBuilder(Wh78DialogTheme.wrap(activity))
            .setTitle("Расширенные настройки сети")
            .setItems(arrayOf("Собственный DNS", "MTU")) { _, which ->
                editNetworkSettings(dnsOnly = which == 0)
            }
            .setNegativeButton("Назад", null)
            .show()
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
                .setMessage(
                    if (dnsOnly) "Серверы через запятую. Пустое значение — использовать DNS из профиля."
                    else "Рекомендуемый диапазон: 1280–1420. Пустое значение — использовать значение из профиля."
                )
                .setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить") { _, _ ->
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            if (dnsOnly) {
                                proxy.`interface`.dnsServers = field.text.toString().trim()
                            } else {
                                val mtu = field.text.toString().trim()
                                if (mtu.isNotEmpty()) {
                                    val n = mtu.toIntOrNull()
                                        ?: throw IllegalArgumentException("MTU должен быть числом")
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

    private fun checkUpdates() {
        updateStatusView?.text = "Установлена версия ${BuildConfig.MYVPN_BUILD} · проверяем обновления…"
        Wh78Updater.checkStatus { status ->
            displayUpdateStatus(status)
            if (status.release == null) toast(status.message)
        }
    }

    private fun refreshUpdateStatus(showFeedback: Boolean) {
        Wh78Updater.checkStatus { status ->
            displayUpdateStatus(status)
            if (showFeedback && status.release == null) toast(status.message)
        }
    }

    private fun displayUpdateStatus(status: Wh78Updater.UpdateStatus) {
        pendingRelease = status.release
        val release = status.release
        if (release != null) {
            updateStatusView?.text = "Установлена версия ${BuildConfig.MYVPN_BUILD}"
            updateBannerText?.text = "Доступна новая версия ${release.tag} · нажмите, чтобы установить"
            updateBanner?.visibility = View.VISIBLE
        } else {
            updateBanner?.visibility = View.GONE
            val message = status.message
            updateStatusView?.text = if (message.startsWith("Установлена последняя версия")) {
                "Установлена версия ${BuildConfig.MYVPN_BUILD}"
            } else {
                "Установлена версия ${BuildConfig.MYVPN_BUILD} · ${message.lowercase()}"
            }
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(Wh78DialogTheme.wrap(activity))
            .setTitle("Информация о приложении")
            .setMessage(
                "WH78: ${BuildConfig.MYVPN_BUILD}\n" +
                    "AmneziaWG: ${BuildConfig.AMNEZIAWG_VERSION_NAME}\n\n" +
                    "VPN-клиент без регистрации.\n\n" +
                    "GitHub\nhttps://github.com/WoOh78/WH78"
            )
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Открыть GitHub") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WoOh78/WH78")))
                } catch (_: Throwable) {
                    toast("Не удалось открыть GitHub")
                }
            }
            .show()
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
        updateStatusView = null
        updateBanner = null
        updateBannerText = null
        pendingRelease = null
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun isLightColor(color: Int): Boolean {
        val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return luminance > 186
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "wh78_settings"
        private const val KEY_AUTOCONNECT = "auto_connect"

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun isAutoConnectEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOCONNECT, false)
    }
}
