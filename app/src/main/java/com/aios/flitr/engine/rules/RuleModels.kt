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
    val popupRules: PopupRules = PopupRules(),
)

/**
 * 插屏 / 浮层广告的关闭规则。
 *
 * 与开屏的最大区别: 插屏的关闭按钮通常是个没有文字的 `×` 图标,
 * 而「找 × 就点」非常危险 —— 正常对话框、内容浮层也用 ×。
 * 因此判定必须**同时**满足两个条件:
 *   1. 页面上存在广告标识（如 `广告素材`、`广告` 角标）
 *   2. 存在一个尺寸很小、位于右上角、可点击的关闭控件
 */
@Serializable
data class PopupRules(
    /** 广告标识: 匹配 text 或 contentDescription, 且文本长度不得超过 maxMarkerTextLength */
    val adMarkers: List<String> = listOf("广告", "广告素材", "广告图片", "Sponsored", "推广"),
    /** 广告标识的最大深度: 超过说明广告埋在列表/正文里, 而不是独立的插屏弹窗 */
    val maxMarkerDepth: Int = 3,
    /** 广告标识文本的最大长度: 真正的角标是短语, 长文本是正文里的「免广告」 */
    val maxMarkerTextLength: Int = 12,
    /** 关闭控件的文本特征 */
    val closeTexts: List<String> = listOf("×", "✕", "X", "关闭", "关闭广告", "close"),
    /** 关闭控件允许的最大屏占比, 超过说明是容器而非按钮 */
    val maxCloseAreaRatio: Float = 0.03f,
    /** 关闭控件中心点必须位于画布的这个横向比例之外（即右侧） */
    val minCloseCenterXRatio: Float = 0.55f,
    /** 关闭控件中心点必须位于画布的这个纵向比例之内（即上部） */
    val maxCloseCenterYRatio: Float = 0.50f,
    /** 点击后多久内不再对同一页面重复点击 */
    val cooldownMs: Long = 2500L,
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
