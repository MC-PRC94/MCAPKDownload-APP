package com.mcstation.download.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 主色：截图中的紫色调
val Primary = Color(0xFF6750A4)
val PrimaryContainer = Color(0xFFEADDFF)
val SurfaceBg = Color(0xFFF6F4FA)      // 界面整体浅紫灰背景
val CardBorder = Color(0xFF6750A4)
val TextPrimary = Color(0xFF1D1B20)

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
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
