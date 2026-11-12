package com.aios.flitr.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aios.flitr.ui.theme.AccentPalette
import com.aios.flitr.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.flitrDataStore: DataStore<Preferences> by preferencesDataStore(name = "flitr_settings")

/** 全部用户可配置项。默认值即 PRD 推荐值。 */
data class FlitrPrefs(
    val themeMode: ThemeMode = ThemeMode.Light,
    val accent: AccentPalette = AccentPalette.ElectricBlue,
    val followSystemDark: Boolean = true,

    // 引擎开关
    val splashEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val popupEnabled: Boolean = true,
    val paused: Boolean = false,

    // 白名单 / 托管应用
    val whitelist: Set<String> = DEFAULT_WHITELIST,
    val videoPackages: Set<String> = setOf("com.phoenix.read"),

    /**
     * 插屏弹窗通道只在这几个应用里生效。
     *
     * 必须按包名 opt-in: 「广告」两个字在任意 App 里都可能出现（通话记录的骚扰标记、
     * 正文里的「免广告」、自己 App 的活动弹窗）, 不做包名白名单的话误点风险无法收敛。
     * 默认为空 —— 这个功能宁可默认不做, 也不能误点。
     */
    val popupPackages: Set<String> = DEFAULT_POPUP_PACKAGES,

    // 拟人化参数
    val reactionMinMs: Int = 380,
    val reactionMaxMs: Int = 820,
    val swipeDurationMinMs: Int = 185,
    val swipeDurationMaxMs: Int = 310,
    val touchCooldownMs: Int = 3000,
    val humanizeGesture: Boolean = true,

    // 场景细化
    val debounceMs: Int = 450,
    val autoContinueWatching: Boolean = true,
    val pillEnabled: Boolean = true,
    val ocrEnabled: Boolean = true,
    val persistentNotification: Boolean = true,
) {
    companion object {
        /**
         * 插屏弹窗通道默认只在已验证的应用上生效。
         *
         * 为什么必须按包名 opt-in: 「广告」两个字在任意 App 里都可能出现 ——
         * 通话记录的骚扰标记叫「广告推销」, 正文里会有「免广告」,
         * 自己 App 的活动弹窗更没有标识。不做包名白名单, 误点风险无法收敛。
         * 新增应用前必须先抓真实样本确认节点结构。
         */
        val DEFAULT_POPUP_PACKAGES: Set<String> = setOf(
            "com.taou.maimai",      // 脉脉 · 同事圈 (真机样本已验证)
            "ctrip.android.view",   // 携程 (真机样本已验证)
        )

        /** 默认完全不介入的应用: 系统界面、桌面、电话、支付收银台 */
        val DEFAULT_WHITELIST: Set<String> = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.oppo.launcher",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher",
            "com.eg.android.AlipayGphone",
            "com.tencent.mm",
            "com.unionpay",
            "com.android.vending",
            "com.aios.flitr",
            "com.aios.flitr.debug",
            // 拨号/联系人: ColorOS 的拨号盘就是 com.android.contacts,
            // 通话记录里有「广告推销」「骚扰电话」这类标记, 与广告角标仅一字之差, 极易误判
            "com.android.contacts",
            "com.android.contactspicker",
            "com.android.providers.contacts",
            // X(Twitter): 时间线里「广告/推广」字样遍布正文与角标, 且每条推文右上角都有 ⋮ 按钮,
            // 实测会误判成「广告 + 关闭按钮」而点开菜单。该应用不在支持范围内, 直接排除。
            "com.twitter.android",
        )
    }
}

class SettingsRepository(private val context: Context) {

    val prefs: Flow<FlitrPrefs> = context.flitrDataStore.data.map { p ->
        FlitrPrefs(
            themeMode = p[K_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.Light,
            accent = p[K_ACCENT]?.let { runCatching { AccentPalette.valueOf(it) }.getOrNull() }
                ?: AccentPalette.ElectricBlue,
            followSystemDark = p[K_FOLLOW_SYSTEM] ?: true,
            splashEnabled = p[K_SPLASH] ?: true,
            videoEnabled = p[K_VIDEO] ?: true,
            popupEnabled = p[K_POPUP] ?: true,
            popupPackages = p[K_POPUP_PKGS] ?: FlitrPrefs.DEFAULT_POPUP_PACKAGES,
            paused = p[K_PAUSED] ?: false,
            whitelist = p[K_WHITELIST] ?: FlitrPrefs.DEFAULT_WHITELIST,
            videoPackages = p[K_VIDEO_PKGS] ?: setOf("com.phoenix.read"),
            reactionMinMs = p[K_REACT_MIN] ?: 380,
            reactionMaxMs = p[K_REACT_MAX] ?: 820,
            swipeDurationMinMs = p[K_SWIPE_MIN] ?: 185,
            swipeDurationMaxMs = p[K_SWIPE_MAX] ?: 310,
            touchCooldownMs = p[K_TOUCH_CD] ?: 3000,
            humanizeGesture = p[K_HUMANIZE] ?: true,
            debounceMs = p[K_DEBOUNCE] ?: 450,
            autoContinueWatching = p[K_AUTO_CONTINUE] ?: true,
            pillEnabled = p[K_PILL] ?: true,
            ocrEnabled = p[K_OCR] ?: true,
            persistentNotification = p[K_NOTIFICATION] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = put { it[K_THEME_MODE] = mode.name }
    suspend fun setAccent(accent: AccentPalette) = put { it[K_ACCENT] = accent.name }
    suspend fun setFollowSystemDark(v: Boolean) = put { it[K_FOLLOW_SYSTEM] = v }
    suspend fun setSplashEnabled(v: Boolean) = put { it[K_SPLASH] = v }
    suspend fun setVideoEnabled(v: Boolean) = put { it[K_VIDEO] = v }
    suspend fun setPopupEnabled(v: Boolean) = put { it[K_POPUP] = v }
    suspend fun setPaused(v: Boolean) = put { it[K_PAUSED] = v }
    suspend fun setWhitelist(v: Set<String>) = put { it[K_WHITELIST] = v }
    suspend fun setVideoPackages(v: Set<String>) = put { it[K_VIDEO_PKGS] = v }
    suspend fun setPopupPackages(v: Set<String>) = put { it[K_POPUP_PKGS] = v }
    suspend fun setReactionRange(min: Int, max: Int) = put {
        it[K_REACT_MIN] = min
        it[K_REACT_MAX] = max
    }
    suspend fun setSwipeDurationRange(min: Int, max: Int) = put {
        it[K_SWIPE_MIN] = min
        it[K_SWIPE_MAX] = max
    }
    suspend fun setTouchCooldown(v: Int) = put { it[K_TOUCH_CD] = v }
    suspend fun setHumanizeGesture(v: Boolean) = put { it[K_HUMANIZE] = v }
    suspend fun setDebounce(v: Int) = put { it[K_DEBOUNCE] = v }
    suspend fun setAutoContinueWatching(v: Boolean) = put { it[K_AUTO_CONTINUE] = v }
    suspend fun setPillEnabled(v: Boolean) = put { it[K_PILL] = v }
    suspend fun setOcrEnabled(v: Boolean) = put { it[K_OCR] = v }
    suspend fun setPersistentNotification(v: Boolean) = put { it[K_NOTIFICATION] = v }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.flitrDataStore.edit { block(it) }
    }

    private companion object {
        val K_THEME_MODE = stringPreferencesKey("theme_mode")
        val K_ACCENT = stringPreferencesKey("accent")
        val K_FOLLOW_SYSTEM = booleanPreferencesKey("follow_system_dark")
        val K_SPLASH = booleanPreferencesKey("splash_enabled")
        val K_VIDEO = booleanPreferencesKey("video_enabled")
        val K_POPUP = booleanPreferencesKey("popup_enabled")
        val K_PAUSED = booleanPreferencesKey("paused")
        val K_WHITELIST = stringSetPreferencesKey("whitelist")
        val K_VIDEO_PKGS = stringSetPreferencesKey("video_packages")
        val K_POPUP_PKGS = stringSetPreferencesKey("popup_packages")
        val K_REACT_MIN = intPreferencesKey("reaction_min")
        val K_REACT_MAX = intPreferencesKey("reaction_max")
        val K_SWIPE_MIN = intPreferencesKey("swipe_min")
        val K_SWIPE_MAX = intPreferencesKey("swipe_max")
        val K_TOUCH_CD = intPreferencesKey("touch_cooldown")
        val K_HUMANIZE = booleanPreferencesKey("humanize")
        val K_DEBOUNCE = intPreferencesKey("debounce")
        val K_AUTO_CONTINUE = booleanPreferencesKey("auto_continue")
        val K_PILL = booleanPreferencesKey("pill")
        val K_OCR = booleanPreferencesKey("ocr")
        val K_NOTIFICATION = booleanPreferencesKey("notification")
    }
}
