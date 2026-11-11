package com.aios.flitr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

/** 等宽数字: 统计数字跳动时不产生横向抖动。 */
const val TABULAR_NUMS = "tnum"

val FlitrTypography: Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
    displayMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
    headlineLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 17.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp, lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp, lineHeight = 13.sp, letterSpacing = 0.3.sp,
    ),
).let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            )
        )
    )
}

private fun FlitrTokens.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryWash,
        onPrimaryContainer = text,
        secondary = textSec,
        onSecondary = card,
        background = page,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = block,
        onSurfaceVariant = textSec,
        surfaceContainer = card,
        surfaceContainerHigh = block,
        surfaceContainerLow = card,
        surfaceContainerLowest = page,
        outline = track,
        outlineVariant = hairline,
        error = danger,
        onError = Color.White,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryWash,
        onPrimaryContainer = text,
        secondary = textSec,
        onSecondary = Color.White,
        background = page,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = block,
        onSurfaceVariant = textSec,
        surfaceContainer = card,
        surfaceContainerHigh = block,
        surfaceContainerLow = card,
        surfaceContainerLowest = page,
        outline = track,
        outlineVariant = hairline,
        error = danger,
        onError = Color.White,
    )
}

@Composable
fun FlitrTheme(
    mode: ThemeMode,
    accent: AccentPalette,
    content: @Composable () -> Unit,
) {
    val tokens = remember(mode, accent) { tokensFor(mode, accent.color) }
    val scheme = remember(tokens) { tokens.toColorScheme() }
    CompositionLocalProvider(LocalFlitrTokens provides tokens) {
        MaterialTheme(
            colorScheme = scheme,
            typography = FlitrTypography,
            content = content,
        )
    }
}

/** 便捷访问器: `FlitrTheme.tokens` */
object FlitrTheme {
    val tokens: FlitrTokens
        @Composable get() = LocalFlitrTokens.current
}
