package org.amnezia.awg.dashboard

/** User-selectable visual themes. */
enum class AppTheme(val key: String, val title: String) {
    PREMIUM_DARK("premium_dark", "Premium Dark"),
    LIGHT("light", "Light"),
    BLUE("blue", "Blue"),
    CYBERPUNK("cyberpunk", "Cyberpunk");

    companion object {
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: PREMIUM_DARK
    }
}
