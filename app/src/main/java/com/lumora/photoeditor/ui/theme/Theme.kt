package com.lumora.photoeditor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lumora design system: a premium photography-first dark interface.
 * Restrained warm-amber accent, neutral panels, hairline dividers.
 */
@Immutable
data class LumoraColors(
    val background: Color,
    val panel: Color,
    val panelElevated: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentDim: Color,
    val danger: Color,
    val ok: Color,
    val scrim: Color,
)

private val DarkLumora = LumoraColors(
    background = Color(0xFF0B0C0E),
    panel = Color(0xFF141518),
    panelElevated = Color(0xFF1C1E22),
    divider = Color(0xFF26282E),
    textPrimary = Color(0xFFF2F3F5),
    textSecondary = Color(0xFFA9ADB6),
    textTertiary = Color(0xFF6B7078),
    accent = Color(0xFFE8B34B),
    accentDim = Color(0xFF3A2F18),
    danger = Color(0xFFE5484D),
    ok = Color(0xFF46A758),
    scrim = Color(0x99000000),
)

private val LightLumora = LumoraColors(
    background = Color(0xFFF6F6F4),
    panel = Color(0xFFFFFFFF),
    panelElevated = Color(0xFFF0F0EE),
    divider = Color(0xFFE2E2DE),
    textPrimary = Color(0xFF17181A),
    textSecondary = Color(0xFF5B5E66),
    textTertiary = Color(0xFF9A9DA5),
    accent = Color(0xFFB07E1F),
    accentDim = Color(0xFFF3E7C8),
    danger = Color(0xFFD13438),
    ok = Color(0xFF2F7D3B),
    scrim = Color(0x66000000),
)

val LocalLumoraColors = staticCompositionLocalOf { DarkLumora }

@Immutable
data class LumoraType(
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val controlLabel: TextStyle,
    val controlValue: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val toolLabel: TextStyle,
)

private val LumoraTypeScale = LumoraType(
    screenTitle = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, letterSpacing = 0.1.sp),
    sectionTitle = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 1.2.sp),
    controlLabel = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, letterSpacing = 0.1.sp),
    controlValue = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, letterSpacing = 0.2.sp,
        fontFeatureSettings = "tnum"),
    body = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, letterSpacing = 0.1.sp),
    caption = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, letterSpacing = 0.2.sp),
    toolLabel = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp, letterSpacing = 0.4.sp),
)

val LocalLumoraType = staticCompositionLocalOf { LumoraTypeScale }

object LumoraDimens {
    val panelPadding = 16.dp
    val controlSpacing = 10.dp
    val cornerS = 8.dp
    val cornerM = 12.dp
    val cornerL = 16.dp
    val touchTarget = 48.dp
    val sliderHeight = 28.dp
    val divider = 1.dp
    val toolbarHeight = 56.dp
    val bottomBarHeight = 76.dp
}

@Composable
fun LumoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkLumora else LightLumora
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            background = colors.background,
            surface = colors.panel,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            background = colors.background,
            surface = colors.panel,
            onBackground = colors.textPrimary,
            onSurface = colors.textPrimary,
        )
    }
    CompositionLocalProvider(
        LocalLumoraColors provides colors,
        LocalLumoraType provides LumoraTypeScale,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Convenient accessors inside LumoraTheme. */
object Lumora {
    val colors: LumoraColors @Composable get() = LocalLumoraColors.current
    val type: LumoraType @Composable get() = LocalLumoraType.current
}
