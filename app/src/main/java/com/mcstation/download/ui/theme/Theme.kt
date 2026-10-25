package com.mcstation.download.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mcstation.download.data.cache.DisplayPrefs

// 主色：截图中的紫色调
val Primary = Color(0xFF6750A4)
val PrimaryContainer = Color(0xFFEADDFF)
val SurfaceBg = Color(0xFFF6F4FA)      // 界面整体浅紫灰背景
val CardBorder = Color(0xFF6750A4)
val TextPrimary = Color(0xFF1D1B20)

/**
 * 可选主题色预设（设置页「自定义主题色」）：
 * light = 浅色模式 primary，dark = 深色模式 primary（提亮版），onDark = 深色模式上的文字色（同色系深色调）。
 * swatch 是设置页色块展示用的颜色。
 */
data class ThemeColorOption(
    val id: String,
    val label: String,
    val swatch: Color,
    val light: Color,
    val dark: Color,
    val onDark: Color,
)

/** 默认主题色 id（与 DisplayPrefs.themeColorId == null 等价） */
const val DEFAULT_THEME_COLOR_ID = "purple"

val ThemeColorOptions = listOf(
    ThemeColorOption("purple", "默认紫", Color(0xFF6750A4), Color(0xFF6750A4), Color(0xFFD0BCFF), Color(0xFF381E72)),
    ThemeColorOption("blue", "蓝", Color(0xFF3E6DB5), Color(0xFF3E6DB5), Color(0xFFA8C8F5), Color(0xFF0D2B57)),
    ThemeColorOption("teal", "青", Color(0xFF00897B), Color(0xFF00897B), Color(0xFF6FD8C7), Color(0xFF00332C)),
    ThemeColorOption("green", "绿", Color(0xFF2E7D32), Color(0xFF2E7D32), Color(0xFF7FD97A), Color(0xFF0C3310)),
    ThemeColorOption("orange", "橙", Color(0xFFE65100), Color(0xFFE65100), Color(0xFFFFB77C), Color(0xFF4A2000)),
    ThemeColorOption("red", "红", Color(0xFFC62828), Color(0xFFC62828), Color(0xFFFF9F98), Color(0xFF4A0E0E)),
    ThemeColorOption("pink", "粉", Color(0xFFC2185B), Color(0xFFC2185B), Color(0xFFFFB1C8), Color(0xFF4A0D24)),
)

private fun colorSchemeFor(darkTheme: Boolean) = when {
    darkTheme -> DarkColors
    else -> LightColors
}.let { base ->
    // 默认紫直接用原配色；选了自定义色则只覆盖 primary 一族（容器色用同色系插值生成）
    val opt = DisplayPrefs.themeColorId.value
        ?.let { id -> ThemeColorOptions.firstOrNull { it.id == id } }
        ?: return@let base
    if (darkTheme) base.copy(
        primary = opt.dark,
        onPrimary = opt.onDark,
        primaryContainer = lerp(Color(0xFF1C1B1F), opt.dark, 0.22f),
        onPrimaryContainer = lerp(Color(0xFFEADDFF), opt.dark, 0.15f),
    ) else base.copy(
        primary = opt.light,
        onPrimary = Color.White,
        primaryContainer = lerp(Color.White, opt.light, 0.16f),
        onPrimaryContainer = lerp(Color(0xFF21005D), opt.light, 0.40f),
    )
}

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = Color(0xFF21005D),
    surface = SurfaceBg,
    background = SurfaceBg,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFECE6F4),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    surfaceContainerHigh = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    // 深紫色暗背景（Material3 暗色 surface 色调）
    surface = Color(0xFF141218),
    background = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    surfaceContainerHigh = Color(0xFF2B2930),
)

// 排版基准对齐 Material 3 标准（titleMedium 16sp / bodyMedium 14sp），
// 不要随意调大——全局字号变大会让整个应用显得"胖一圈"（2026-10-24 用户反馈过界面偏大）。
private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp),
)

@Composable
fun MCDownloadStationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorSchemeFor(darkTheme),
        typography = AppTypography,
        content = content,
    )
}
