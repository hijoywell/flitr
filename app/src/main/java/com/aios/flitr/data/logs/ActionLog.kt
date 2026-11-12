package com.aios.flitr.data.logs

import kotlinx.serialization.Serializable

/** 触发类型。对应 UI 上的动作标签 Chip。 */
enum class LogKind(val label: String) {
    SplashSkip("开屏跳过"),
    PopupClose("关闭弹窗广告"),
    VideoNoCountdownSwipe("无倒计时秒划"),
    VideoCountdownSwipe("等倒计时后划走"),
    VideoClose("关闭广告弹窗"),
    ContinueWatching("点击继续观看"),
    Guard("正片保护"),
    Manual("人工介入"),
    Error("异常"),
}

@Serializable
data class ActionLog(
    val id: String,
    val timestampMs: Long,
    val packageName: String,
    val appName: String,
    val kind: String,
    val summary: String,
    val detail: String = "",
    /** 从事件到执行动作的耗时, 用于验证"开屏响应 < 200ms"指标 */
    val elapsedMs: Long = 0L,
    /** 用户标记的误触 */
    val falsePositive: Boolean = false,
) {
    val kindEnum: LogKind
        get() = runCatching { LogKind.valueOf(kind) }.getOrDefault(LogKind.Error)
}
