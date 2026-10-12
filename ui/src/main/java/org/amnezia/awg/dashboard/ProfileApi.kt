package org.amnezia.awg.dashboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ProfileInfo(
    val name: String?,
    val expiresAt: Long?
)

object ProfileApi {

    private const val API_URL = "http://172.16.17.1:8765/v1/me"

    suspend fun getProfileInfo(): ProfileInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false

            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext null
                }

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val json = JSONObject(body)

                ProfileInfo(
                    name = if (json.isNull("name")) null else json.optString("name"),
                    expiresAt = if (json.isNull("expires_at")) {
                        null
                    } else {
                        json.optLong("expires_at")
                    }
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
