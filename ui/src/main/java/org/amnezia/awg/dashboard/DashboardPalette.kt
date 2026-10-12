package org.amnezia.awg.dashboard

import android.graphics.Color

data class DashboardPalette(
    val background: Int,
    val surface: Int,
    val surface2: Int,
    val text: Int,
    val secondaryText: Int,
    val accent: Int,
    val accentText: Int,
    val outline: Int,
    val positive: Int,
    val negative: Int
) {
    companion object {
        fun forTheme(theme: AppTheme): DashboardPalette = when (theme) {
            AppTheme.PREMIUM_DARK -> DashboardPalette(
                Color.rgb(10, 13, 16), Color.rgb(20, 25, 29), Color.rgb(28, 35, 40),
                Color.rgb(244, 248, 246), Color.rgb(156, 171, 165), Color.rgb(77, 226, 160),
                Color.rgb(7, 31, 22), Color.rgb(52, 70, 64), Color.rgb(77, 226, 160), Color.rgb(255, 92, 92)
            )
            AppTheme.LIGHT -> DashboardPalette(
                Color.rgb(246, 248, 249), Color.WHITE, Color.rgb(236, 240, 243),
                Color.rgb(21, 28, 33), Color.rgb(96, 109, 117), Color.rgb(24, 142, 91),
                Color.WHITE, Color.rgb(207, 214, 218), Color.rgb(24, 142, 91), Color.rgb(214, 65, 65)
            )
            AppTheme.BLUE -> DashboardPalette(
                Color.rgb(8, 15, 28), Color.rgb(15, 27, 47), Color.rgb(21, 39, 64),
                Color.rgb(239, 246, 255), Color.rgb(151, 174, 203), Color.rgb(89, 171, 255),
                Color.rgb(7, 28, 49), Color.rgb(51, 78, 111), Color.rgb(75, 190, 255), Color.rgb(255, 103, 110)
            )
            AppTheme.CYBERPUNK -> DashboardPalette(
                Color.rgb(13, 7, 18), Color.rgb(28, 13, 38), Color.rgb(42, 18, 53),
                Color.rgb(249, 239, 255), Color.rgb(177, 146, 191), Color.rgb(255, 66, 199),
                Color.rgb(48, 9, 35), Color.rgb(90, 44, 98), Color.rgb(74, 244, 192), Color.rgb(255, 84, 125)
            )
        }
    }
}
