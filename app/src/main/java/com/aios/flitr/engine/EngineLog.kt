package com.aios.flitr.engine

import android.util.Log

/**
 * 引擎诊断日志。
 *
 * 存在的理由很实际: 广告页面往往只出现几百毫秒, 事后靠截图和 UI 层级工具根本抓不住
 * (`uiautomator dump` 在视频页会直接报 "could not get idle state")。
 * 让引擎在**决策的瞬间**把看到的节点与判定结果写进 logcat, 是唯一可靠的现场还原方式。
 *
 * 只输出判定所需的最小信息(文本 + 是否命中), 不输出整棵树, 避免日志本身成为性能负担。
 */
object EngineLog {

    private const val TAG = "FlitrEngine"

    /** 日志节流: 同一页面指纹在 [SNAPSHOT_THROTTLE_MS] 内只打印一次 */
    private var lastSnapshotFingerprint: String? = null
    private var lastSnapshotAt: Long = 0L

    fun d(message: String) {
        // 用 INFO 而不是 DEBUG: ColorOS 等国产 ROM 会过滤掉第三方应用的 DEBUG 级日志,
        // 用 Log.d 会出现"代码里明明打了日志, logcat 里一条都没有"的假象。
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    /** 页面结构变化时打印一次摘要, 用于事后校准规则。 */
    fun snapshotChanged(
        fingerprint: String,
        now: Long,
        summary: String,
        force: Boolean = false,
    ) {
        if (!force) {
            if (fingerprint == lastSnapshotFingerprint) return
            if (now - lastSnapshotAt < SNAPSHOT_THROTTLE_MS) return
        }
        lastSnapshotFingerprint = fingerprint
        lastSnapshotAt = now
        Log.d(TAG, "PAGE ${fingerprint.take(8)} | $summary")
    }

    fun resetSnapshotThrottle() {
        lastSnapshotFingerprint = null
        lastSnapshotAt = 0L
    }

    private var eventCount: Int = 0
    private var lastRateAt: Long = 0L

    /** 统计无障碍事件速率, 用于验证节流是否真的把风暴压下去了。 */
    fun countEvent(now: Long, detail: String) {
        eventCount++
        if (lastRateAt == 0L) lastRateAt = now
        if (now - lastRateAt >= RATE_WINDOW_MS) {
            val perSec = eventCount * 1000f / (now - lastRateAt)
            Log.i(TAG, "EVENT rate=%.1f/s last=%s".format(perSec, detail))
            eventCount = 0
            lastRateAt = now
        }
    }

    private const val SNAPSHOT_THROTTLE_MS = 1500L
    private const val RATE_WINDOW_MS = 5000L

    // ---------- 翻页探针 ----------
    private var probeWindowStart = 0L
    private var probeCount = 0
    private val probeBuckets = LinkedHashMap<String, Int>()

    /**
     * 记录一条视频页事件的特征, 每 30 秒汇总一次。
     *
     * 目的很具体: 判断「翻页」能不能只靠事件本身识别出来。
     * 如果能, 引擎就可以只在翻页时刻采样, 播放过程中完全静默 —— 功耗归零;
     * 如果不能(翻页和播放抖动的事件特征完全一样), 就必须保留定时采样。
     *
     * 汇总的 key 是 `事件类型 + contentChangeTypes + 类名`, 直方图一眼能看出
     * 哪些是高频噪声、哪些是低频的"结构性变化"。
     */
    fun eventProbe(type: Int, cct: Int, className: String?, sourceHasText: Boolean, now: Long) {
        if (probeWindowStart == 0L) probeWindowStart = now
        probeCount++
        val typeName = when (type) {
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT"
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WIN_STATE"
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
            else -> type.toString()
        }
        val cls = className?.substringAfterLast('.') ?: "-"
        val key = "$typeName/cct=$cct/$cls/txt=$sourceHasText"
        probeBuckets[key] = (probeBuckets[key] ?: 0) + 1

        if (now - probeWindowStart >= 30_000L) {
            val total = probeCount
            val top = probeBuckets.entries
                .sortedByDescending { it.value }
                .take(8)
                .joinToString(" | ") { "${it.key} x${it.value}" }
            Log.i(TAG, "PROBE ${total}events/30s :: $top")
            probeBuckets.clear()
            probeCount = 0
            probeWindowStart = now
        }
    }
}
