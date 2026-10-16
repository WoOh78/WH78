package org.amnezia.awg.dashboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

object Wh78Updater {
    private const val API_URL = "https://api.github.com/repos/WoOh78/WH78/releases/latest"
    private const val STATUS_ACTION = "ru.wooh.wh78.UPDATE_STATUS"

    data class Release(val tag: String, val name: String, val apkUrl: String)

    data class UpdateStatus(val message: String, val release: Release? = null)

    /** Checks GitHub quietly so the settings page can show a persistent update banner. */
    fun checkStatus(onResult: (UpdateStatus) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val status = try {
                val latest = withContext(Dispatchers.IO) { getLatestRelease() }
                if (latest == null) {
                    UpdateStatus("Не удалось найти опубликованный релиз")
                } else {
                    val currentNumber = parseBuildNumber(BuildConfig.MYVPN_BUILD)
                    val latestNumber = parseBuildNumber(latest.tag)
                    when {
                        currentNumber == null || latestNumber == null ->
                            UpdateStatus("Не удалось определить номер релиза")
                        latestNumber > currentNumber ->
                            UpdateStatus("Доступно обновление ${latest.tag}", latest)
                        else -> UpdateStatus("Установлена последняя версия ${BuildConfig.MYVPN_BUILD}")
                    }
                }
            } catch (_: Throwable) {
                UpdateStatus("Не удалось проверить обновления")
            }
            onResult(status)
        }
    }

    fun checkAndOffer(activity: DashboardActivity, palette: Wh78SettingsDialog.Palette, onMessage: (String) -> Unit) {
        checkStatus { status ->
            val release = status.release
            if (release == null) onMessage(status.message)
            else offerUpdate(activity, palette, release, onMessage)
        }
    }

    fun offerUpdate(
        activity: DashboardActivity,
        palette: Wh78SettingsDialog.Palette,
        release: Release,
        onMessage: (String) -> Unit
    ) {
        MaterialAlertDialogBuilder(Wh78DialogTheme.wrap(activity))
            .setTitle("Доступно обновление")
            .setMessage("${release.name}\nТекущая версия: ${BuildConfig.MYVPN_BUILD}\nНовая версия: ${release.tag}")
            .setNegativeButton("Позже", null)
            .setPositiveButton("Скачать и установить") { _, _ ->
                downloadAndInstall(activity, release.apkUrl, onMessage)
            }
            .show()
    }

    private fun getLatestRelease(): Release? {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "WH78/${BuildConfig.MYVPN_BUILD}")
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val item = assets.getJSONObject(i)
                val name = item.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = item.optString("browser_download_url")
                    if (apkUrl.isNotBlank()) break
                }
            }
            val url = apkUrl ?: return null
            Release(json.optString("tag_name"), json.optString("name", json.optString("tag_name")), url)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseBuildNumber(value: String): Int? {
        val m = Regex("(?i)b(\\d+)").find(value) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun downloadAndInstall(activity: DashboardActivity, url: String, onMessage: (String) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val target = File(activity.cacheDir, "wh78-update.apk")
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    conn.setRequestProperty("User-Agent", "WH78/${BuildConfig.MYVPN_BUILD}")
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    conn.disconnect()
                    target
                }
                installApk(activity, apk)
            } catch (e: Throwable) {
                onMessage("Не удалось установить обновление: ${e.message ?: "ошибка"}")
            }
        }
    }

    private fun installApk(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            FileInputStream(apk).use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, Wh78InstallReceiver::class.java).setAction(STATUS_ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        } finally {
            session.close()
        }
        Toast.makeText(context, "Запущена установка WH78", Toast.LENGTH_LONG).show()
    }
}

class Wh78InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "WH78 обновлён"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Android ожидает подтверждение установки"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Установка заблокирована. Разрешите WH78 устанавливать неизвестные приложения."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK несовместим с установленным WH78${message?.let { ": $it" } ?: ""}"
            else -> "Не удалось установить обновление${message?.let { ": $it" } ?: " (код $status)"}"
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
        }
    }
}
