package com.aios.flitr.engine

import com.aios.flitr.data.prefs.FlitrPrefs
import com.aios.flitr.engine.rules.SplashRules

/** 一次事件应该走哪条处理通道。 */
sealed interface Route {
    /** 开屏通道: 处于高敏时间窗内 */
    data class Splash(val elapsedMs: Long) : Route

    /** 视频流托管通道 */
    data object Video : Route

    /** 不介入 */
    data object Ignore : Route
}

/**
 * 事件分流与门控。
 *
 * 这是功耗控制的第一道闸门(见 ARCHITECTURE 3.1):
 * - 开屏通道只在应用切换后的**有限时间窗**内开启, 平时完全关闭;
 * - 视频通道只在用户显式托管的目标包名上开启;
 * - 两条通道各自独立节流, 避免视频进度条每秒几十次刷新把节点树遍历打爆。
 */
class EventRouter(
    private val splashRules: SplashRules = SplashRules(),
) {

    private var currentPackage: String? = null
    private var enteredAt: Long = 0L
    private var lastSplashCheckAt: Long = 0L
    private var lastVideoSampleAt: Long = 0L
    private var splashWindowClosed: Boolean = false

    /** 系统级噪声包名: 输入法、状态栏、系统弹窗 */
    private val noisePackages = setOf(
        "com.android.systemui",
        "android",
        "com.android.inputmethod.latin",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.baidu.input",
        "com.google.android.inputmethod.latin",
    )

    val foregroundPackage: String? get() = currentPackage
    val foregroundSince: Long get() = enteredAt

    /** 系统级噪声包名(输入法 / 状态栏 / 系统弹窗), 不应影响前台应用判定。 */
    fun isNoise(pkg: String?): Boolean = pkg != null && pkg in noisePackages

    /**
     * 记录前台变化。
     * @return true 表示确实发生了包名切换(或首次进入)
     */
    fun onForegroundChanged(pkg: String?, now: Long): Boolean {
        if (pkg == null || pkg == currentPackage) return false
        currentPackage = pkg
        enteredAt = now
        splashWindowClosed = false
        lastSplashCheckAt = 0L
        lastVideoSampleAt = 0L
        return true
    }

    /** 开屏窗口是否已经结束(命中一次后提前关闭以省电)。 */
    fun closeSplashWindow() {
        splashWindowClosed = true
    }

    fun route(pkg: String?, now: Long, prefs: FlitrPrefs): Route {
        if (prefs.paused) return Route.Ignore
        if (pkg.isNullOrBlank()) return Route.Ignore
        if (pkg in noisePackages) return Route.Ignore
        if (pkg in prefs.whitelist) return Route.Ignore

        // --- 开屏通道优先 ---
        // 顺序很关键: 开屏窗口只有 6 秒, 而视频托管是常驻的。
        // 早期实现把视频通道放在前面, 结果凡是配置了视频托管的应用(红果/快手)
        // 都永远轮不到开屏检测 —— 等于这些 App 的开屏广告完全没处理。
        // 这里不 return, 让节流期内的事件继续落到视频通道, 两条通道共存。
        if (prefs.splashEnabled && !splashWindowClosed) {
            val elapsed = now - enteredAt
            if (elapsed in 0..splashRules.detectionWindowMs) {
                if (now - lastSplashCheckAt >= splashRules.throttleMs) {
                    lastSplashCheckAt = now
                    return Route.Splash(elapsed)
                }
            } else if (elapsed > splashRules.detectionWindowMs) {
                splashWindowClosed = true
            }
        }

        // --- 视频托管通道 ---
        if (prefs.videoEnabled && pkg in prefs.videoPackages) {
            if (now - lastVideoSampleAt >= VIDEO_SAMPLE_MS) {
                lastVideoSampleAt = now
                return Route.Video
            }
            return Route.Ignore
        }

        return Route.Ignore
    }

    fun reset() {
        currentPackage = null
        enteredAt = 0L
        lastSplashCheckAt = 0L
        lastVideoSampleAt = 0L
        splashWindowClosed = false
    }

    companion object {
        /** 视频通道采样间隔: 兼顾倒计时可读性与节点遍历开销 */
        const val VIDEO_SAMPLE_MS = 400L
    }
}
