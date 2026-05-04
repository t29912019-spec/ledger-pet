package com.example.ledger.ui

import android.content.Context
import android.graphics.Color

object ThemeManager {

    enum class Theme(val label: String) {
        NATURE("自然绿"),
        CYBERPUNK("赛博朋克"),
        ANCIENT("古代水墨"),
        COZY("温馨暖橙")
    }

    data class ThemeColors(
        val statusBarColor: Int,
        val background: Int,
        val surface: Int,
        val primary: Int,
        val onPrimary: Int,
        val onSurface: Int,
        val textSecondary: Int,
        val expense: Int,
        val income: Int,
        val divider: Int
    )

    private fun c(hex: String) = Color.parseColor(hex)

    // 自然绿 — 蓝绿自然风格（默认）
    private val natureColors = ThemeColors(
        statusBarColor = c("#00796B"),
        background = c("#F0F7F4"),
        surface = c("#FFFFFF"),
        primary = c("#00897B"),
        onPrimary = c("#FFFFFF"),
        onSurface = c("#263238"),
        textSecondary = c("#78909C"),
        expense = c("#EF5350"),
        income = c("#26A69A"),
        divider = c("#E0E8E4")
    )

    // 赛博朋克 — 暗黑霓虹风格
    private val cyberpunkColors = ThemeColors(
        statusBarColor = c("#0D0D0D"),
        background = c("#0A0A0A"),
        surface = c("#1A1A2E"),
        primary = c("#BB86FC"),
        onPrimary = c("#FFFFFF"),
        onSurface = c("#E0E0FF"),
        textSecondary = c("#8888AA"),
        expense = c("#FF5FAF"),
        income = c("#00E6FF"),
        divider = c("#2A2A4A")
    )

    // 古代水墨 — 宣纸水墨风格
    private val ancientColors = ThemeColors(
        statusBarColor = c("#C4A882"),
        background = c("#F5F0E1"),
        surface = c("#FDF9F0"),
        primary = c("#8B6914"),
        onPrimary = c("#FFFFFF"),
        onSurface = c("#3A2A1D"),
        textSecondary = c("#8B7355"),
        expense = c("#C04040"),
        income = c("#2E7D32"),
        divider = c("#D4C5A0")
    )

    // 温馨暖橙 — 温暖柔和风格
    private val cozyColors = ThemeColors(
        statusBarColor = c("#E8956C"),
        background = c("#FFF5EE"),
        surface = c("#FFFFFF"),
        primary = c("#FF8C5A"),
        onPrimary = c("#FFFFFF"),
        onSurface = c("#5C4A3F"),
        textSecondary = c("#A09080"),
        expense = c("#F08080"),
        income = c("#66BB6A"),
        divider = c("#F0E0D0")
    )

    private const val PREFS_NAME = "app_theme_prefs"
    private const val KEY_THEME = "app_theme"

    fun setTheme(context: Context, theme: Theme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme.name).apply()
    }

    fun getCurrentTheme(context: Context): Theme {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, Theme.NATURE.name) ?: Theme.NATURE.name
        return try { Theme.valueOf(name) } catch (_: Exception) { Theme.NATURE }
    }

    fun getThemeLabel(theme: Theme): String = theme.label

    fun getColors(context: Context): ThemeColors {
        return when (getCurrentTheme(context)) {
            Theme.NATURE -> natureColors
            Theme.CYBERPUNK -> cyberpunkColors
            Theme.ANCIENT -> ancientColors
            Theme.COZY -> cozyColors
        }
    }
}
