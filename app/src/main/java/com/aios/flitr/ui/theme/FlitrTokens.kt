package com.aios.flitr.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Flitr 设计令牌。
 *
 * 视觉基调参考 FIClash 体系：浅灰底 + 纯白大圆角卡片 + 强调色胶囊 + 面积折线图。
 * 与 Material 3 的关系：M3 负责组件语义（Switch / Slider / SegmentedButton 的默认形态），
 * 本令牌负责 Flitr 自己的层次表达（page / card / block 三级表面）。
 */
@Immutable
data class FlitrTokens(
    /** 页面底色，最底层 */
    val page: Color,
    /** 卡片表面，承载主要信息 */
    val card: Color,
    /** 卡片内部的分块底色 */
    val block: Color,
    val text: Color,
    val textSec: Color,
    val textTer: Color,
    /** 强调色 */
    val primary: Color,
    val onPrimary: Color,
    /** 强调色的浅色填充，用于选中态与徽标底 */
    val primaryWash: Color,
    /** 进度条 / 滑轨底色 */
    val track: Color,
    val okBg: Color,
    val okFg: Color,
    val okDot: Color,
    val warn: Color,
    val danger: Color,
    /** 1px 分隔线 */
    val hairline: Color,
    val shadow: Color,
    val isDark: Boolean,
)

/** 强调色预设。取值直接来自设计稿。 */
enum class AccentPalette(val label: String, val seed: Long) {
    ElectricBlue("电光蓝", 0xFF007AFF),
    NebulaPurple("星云紫", 0xFF6362EA),
    Magenta("霓虹品红", 0xFFD946A6),
    Crimson("绯霞红", 0xFFF43F5E),
    Amber("暖珀橙", 0xFFF59337),
    Jade("翠屏绿", 0xFF10B981);

    val color: Color get() = Color(seed)
}

/** 明暗模式。对应设计稿的「浅色 / 高对比深色 / 纯黑 OLED」。 */
enum class ThemeMode(val label: String) {
    Light("浅色"),
    DarkHighContrast("高对比深色"),
    PureBlackOled("纯黑 OLED"),
}

private fun onColorFor(accent: Color): Color =
    if (accent.luminance() > 0.55f) Color(0xFF1F1204) else Color.White

private fun wash(card: Color, accent: Color): Color = lerp(card, accent, 0.12f)

fun tokensFor(mode: ThemeMode, accent: Color): FlitrTokens = when (mode) {
    ThemeMode.Light -> FlitrTokens(
        page = Color(0xFFF0F2F6),
        card = Color(0xFFFFFFFF),
        block = Color(0xFFF3F5F8),
        text = Color(0xFF111827),
        textSec = Color(0xFF667085),
        textTer = Color(0xFF98A2B3),
        primary = accent,
        onPrimary = onColorFor(accent),
        primaryWash = wash(Color.White, accent),
        track = Color(0xFFD5DAE2),
        okBg = Color(0xFFDCFCE7),
        okFg = Color(0xFF15803D),
        okDot = Color(0xFF22C55E),
        warn = Color(0xFFF59337),
        danger = Color(0xFFDC2626),
        hairline = Color(0x0F000000),
        shadow = Color(0x14000000),
        isDark = false,
    )

    ThemeMode.DarkHighContrast -> FlitrTokens(
        page = Color(0xFF12100E),
        card = Color(0xFF1F1C19),
        block = Color(0xFF2A2521),
        text = Color(0xFFF6F3EF),
        textSec = Color(0xFFA69D94),
        textTer = Color(0xFF787068),
        primary = accent,
        onPrimary = onColorFor(accent),
        primaryWash = wash(Color(0xFF1F1C19), accent),
        track = Color(0xFF3E3832),
        okBg = Color(0xFF173823),
        okFg = Color(0xFF4ADE80),
        okDot = Color(0xFF4ADE80),
        warn = Color(0xFFF5A85C),
        danger = Color(0xFFF87171),
        hairline = Color(0x14FFFFFF),
        shadow = Color(0x66000000),
        isDark = true,
    )

    ThemeMode.PureBlackOled -> FlitrTokens(
        page = Color(0xFF000000),
        card = Color(0xFF0A0A0C),
        block = Color(0xFF16161A),
        text = Color(0xFFF5F5F7),
        textSec = Color(0xFF9A9AA2),
        textTer = Color(0xFF6E6E76),
        primary = accent,
        onPrimary = onColorFor(accent),
        primaryWash = wash(Color(0xFF0A0A0C), accent),
        track = Color(0xFF2A2A2E),
        okBg = Color(0xFF0C2A18),
        okFg = Color(0xFF4ADE80),
        okDot = Color(0xFF4ADE80),
        warn = Color(0xFFF5A85C),
        danger = Color(0xFFF87171),
        hairline = Color(0x1AFFFFFF),
        shadow = Color(0x80000000),
        isDark = true,
    )
}

val LocalFlitrTokens = staticCompositionLocalOf { tokensFor(ThemeMode.Light, AccentPalette.ElectricBlue.color) }
