package org.amnezia.awg.dashboard

import android.content.Context

/** Metadata stored separately from the real AmneziaWG .conf file. */
class ProfileMetaStore(context: Context) {
    data class Meta(
        val country: String? = null,
        val city: String? = null,
        val expiresAt: Long? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(name: String): Meta {
        val prefix = key(name)
        return Meta(
            country = prefs.getString(prefix + COUNTRY, null),
            city = prefs.getString(prefix + CITY, null),
            expiresAt = prefs.getLong(prefix + EXPIRES_AT, -1L).takeIf { it > 0 },
            createdAt = prefs.getLong(prefix + CREATED_AT, System.currentTimeMillis())
        )
    }

    fun set(name: String, meta: Meta) {
        val prefix = key(name)
        prefs.edit()
            .apply {
                if (meta.country == null) remove(prefix + COUNTRY) else putString(prefix + COUNTRY, meta.country)
                if (meta.city == null) remove(prefix + CITY) else putString(prefix + CITY, meta.city)
                if (meta.expiresAt == null) remove(prefix + EXPIRES_AT) else putLong(prefix + EXPIRES_AT, meta.expiresAt)
                putLong(prefix + CREATED_AT, meta.createdAt)
            }
            .apply()
    }

    fun setExpiry(name: String, expiresAt: Long?) {
        val current = get(name)
        set(name, current.copy(expiresAt = expiresAt))
    }

    fun delete(name: String) {
        val prefix = key(name)
        prefs.edit().apply {
            remove(prefix + COUNTRY)
            remove(prefix + CITY)
            remove(prefix + EXPIRES_AT)
            remove(prefix + CREATED_AT)
        }.apply()
    }

    private fun key(name: String) = "profile_$name."

    companion object {
        private const val FILE_NAME = "profile_meta"
        private const val COUNTRY = "country"
        private const val CITY = "city"
        private const val EXPIRES_AT = "expires_at"
        private const val CREATED_AT = "created_at"
    }
}
