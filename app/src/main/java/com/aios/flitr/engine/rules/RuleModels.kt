package com.aios.flitr.engine.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 规则包根节点。对应 docs/ARCHITECTURE.md 3.5 的 JSON DSL。
 *
 * 设计取向: 规则只描述「如何识别」与「识别到之后做什么」,
 * 不描述跨时间的流程控制 —— 流程控制归状态机, 这样规则可以安全地热替换。
 */
@Serializable
data class RuleBundle(
    val schemaVersion: Int = 1,
    val videoScenarios: List<VideoScenarioRule> = emptyList(),
    val splashRules: SplashRules = SplashRules(),
)

@Serializable
data class VideoScenarioRule(
    val packageName: String,
    val appName: String = packageName,
    val enabled: Boolean = true,
    /** 疑似广告后的稳定帧观察期, 防止倒计时组件异步渲染导致误判 */
    val debounceMs: Int = 450,
    /** 拟人反应延迟区间 [min, max] */
    val reactionDelayRangeMs: List<Int> = listOf(380, 820),
    val ocrFallbackEnabled: Boolean = true,
    val adIndicators: AdIndicators = AdIndicators(),
    val normalDramaSafeguards: DramaSafeguards = DramaSafeguards(),
    val countdownRules: CountdownRules = CountdownRules(),
)

@Serializable
data class AdIndicators(
    /** 任一命中即视为广告信号 */
    val anyTextRegex: List<String> = emptyList(),
    /** 全部命中才视为广告信号。用于表达「返回 + 上滑继续观看短剧」这类组合特征 */
    val allTextRegex: List<String> = emptyList(),
    val anyViewIdSuffix: List<String> = emptyList(),
)

@Serializable
data class DramaSafeguards(
    /** 全部命中才视为正片 */
    val allTextRegex: List<String> = emptyList(),
    /** 命中则说明用户正在选集/评论, 不介入 */
    val excludeWhenPanelOpenRegex: List<String> = emptyList(),
)

@Serializable
data class CountdownRules(
    @SerialName("extractRegex")
    val extractRegex: String = DEFAULT_COUNTDOWN_REGEX,
    val completedTextRegex: List<String> = emptyList(),
    val keepWatchingDialogBtnRegex: List<String> = emptyList(),
    val modalCloseViewIdSuffix: List<String> = emptyList(),
    val maxWaitSeconds: Int = 40,
) {
    companion object {
        const val DEFAULT_COUNTDOWN_REGEX = "(?:还剩|观看|广告)?\\s*(\\d{1,3})\\s*(?:s|S|秒)"
    }
}

@Serializable
data class SplashRules(
    val globalSdkSkipViewIds: List<String> = emptyList(),
    val heuristicSkipRegex: String = DEFAULT_SKIP_REGEX,
    /** 跳过按钮允许的最大屏占比, 超过视为全屏诱导层 */
    val maxNodeScreenAreaRatio: Float = 0.12f,
    /** 开屏高敏检测时间窗 */
    val detectionWindowMs: Long = 6000L,
    /** 检测节流间隔 */
    val throttleMs: Long = 120L,
) {
    companion object {
        /**
         * 开屏跳过按钮的启发式匹配。
         *
         * 真机上跳过按钮的文案形态比想象中杂: 「跳过」「跳过 3」「3s跳过」「5秒 | 跳过」
         * 「跳过广告 5s」都出现过。因此允许倒计时出现在任意一侧, 并容忍常见的分隔符。
         * 仍然强制整串匹配, 避免把正文里的"跳过"两个字当成按钮。
         */
        const val DEFAULT_SKIP_REGEX =
            "^(?:\\d{1,2}\\s*[sS秒]?\\s*[|｜·\\-]?\\s*)?(?:跳过|跳过广告|Skip)(?:\\s*[|｜·\\-]?\\s*\\d{1,2}\\s*[sS秒]?)?$"
    }
}
