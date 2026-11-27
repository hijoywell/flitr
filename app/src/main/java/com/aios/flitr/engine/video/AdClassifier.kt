package com.aios.flitr.engine.video

import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode
import com.aios.flitr.engine.rules.VideoScenarioRule

/** 把 JSON 里的字符串正则编译成可复用对象, 避免每次事件都重新编译。 */
class CompiledVideoRule(raw: VideoScenarioRule) {
    val packageName: String = raw.packageName
    val appName: String = raw.appName
    val debounceMs: Long = raw.debounceMs.toLong()
    val reactionMinMs: Long = raw.reactionDelayRangeMs.getOrNull(0)?.toLong() ?: 380L
    val reactionMaxMs: Long = raw.reactionDelayRangeMs.getOrNull(1)?.toLong() ?: 820L
    val ocrFallbackEnabled: Boolean = raw.ocrFallbackEnabled
    val maxWaitSeconds: Int = raw.countdownRules.maxWaitSeconds

    val adTextPatterns: List<Regex> = raw.adIndicators.anyTextRegex.mapNotNull(::safeRegex)
    val adAllPatterns: List<Regex> = raw.adIndicators.allTextRegex.mapNotNull(::safeRegex)
    val adViewIdSuffixes: List<String> = raw.adIndicators.anyViewIdSuffix
    val dramaAllPatterns: List<Regex> = raw.normalDramaSafeguards.allTextRegex.mapNotNull(::safeRegex)
    val panelOpenPatterns: List<Regex> = raw.normalDramaSafeguards.excludeWhenPanelOpenRegex.mapNotNull(::safeRegex)
    val completedPatterns: List<Regex> = raw.countdownRules.completedTextRegex.mapNotNull(::safeRegex)
    val keepWatchingPatterns: List<Regex> = raw.countdownRules.keepWatchingDialogBtnRegex.mapNotNull(::safeRegex)
    val closeViewIdSuffixes: List<String> = raw.countdownRules.modalCloseViewIdSuffix
    val countdownPattern: Regex? = safeRegex(raw.countdownRules.extractRegex)

    /** 该规则是否配置充分到可以工作; 配置错误时宁可不动也不误触。 */
    val isUsable: Boolean
        get() = adTextPatterns.isNotEmpty() || adViewIdSuffixes.isNotEmpty() || adAllPatterns.isNotEmpty()

    private companion object {
        fun safeRegex(source: String): Regex? = runCatching { Regex(source) }.getOrNull()
    }
}

/**
 * 单帧的广告判定结果。这是一个**纯函数产物**, 不含时间上下文,
 * 时间相关的决策全部交给 [VideoAdStateMachine]。
 */
data class AdAssessment(
    val isAd: Boolean,
    val adHit: TextNode?,
    val isDramaProtected: Boolean,
    val panelOpen: Boolean,
    val countdownSeconds: Int?,
    val countdownNode: TextNode?,
    val isCompleted: Boolean,
    val keepWatchingNode: TextNode?,
    val closeNode: TextNode?,
    val confidence: Float,
) {
    val hasCountdown: Boolean get() = countdownSeconds != null
}

/**
 * 广告页面判定器。
 *
 * 核心约束: **宁可漏判, 不可误判**。误划正片的代价远高于漏划一条广告,
 * 因此正片特征与用户操作特征都会直接压掉广告判定。
 */
object AdClassifier {

    private val CLOSE_TEXT = Regex("^(×|✕|X|关闭|关闭广告)$")
    private val COUNTDOWN_HINT = Regex("(?:后可?(?:领取|解锁|跳过|划走|继续)|解锁|倒计时|奖励)")

    /**
     * 转化类 CTA 黑名单: **任何情况下都不点**。
     *
     * 事故来源: 红果拼多多信息流广告底部有「去领取」按钮, 规则把它当成了中途挽留弹窗的
     * 「继续观看」按钮, 于是 Flitr 主动点了它 —— 直接进入商品下单页。这违反了
     * 「宁可漏关, 不可误关」的底线: 漏划一条广告只是多看几秒, 误点一次会真的下单。
     *
     * 这条黑名单独立于 JSON 规则, 即使以后有人往 keepWatchingDialogBtnRegex 里加词,
     * 命中这里的节点也不会被点击。
     */
    private val CTA_DENY = Regex("(领取|抢购|购买|下单|下载|安装|去看看|查看详情|了解详情|领券|打开|进入|去逛逛|立即购买)")

    fun assess(snapshot: ScreenSnapshot, rule: CompiledVideoRule): AdAssessment {
        val adByText = snapshot.anyMatch(rule.adTextPatterns)
        val adByAll = if (rule.adAllPatterns.isNotEmpty() && snapshot.allMatch(rule.adAllPatterns)) {
            // 组合特征命中时, 用一个节点代表"命中位置", 便于日志展示
            snapshot.anyMatch(rule.adAllPatterns) ?: snapshot.nodes.firstOrNull()
        } else {
            null
        }
        val adByViewId = snapshot.anyViewIdEndsWith(rule.adViewIdSuffixes)
        val countdownPattern = rule.countdownPattern
        val countdownHit: Pair<TextNode, Int?>? = countdownPattern?.let { pattern ->
            snapshot.nodes.firstOrNull { pattern.containsMatchIn(it.normalized) }?.let { node ->
                val seconds = pattern.find(node.normalized)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.takeIf { it in 0..rule.maxWaitSeconds }
                node to seconds
            }
        }
        val countdownNode = countdownHit?.first
        val countdownSeconds = countdownHit?.second

        val completedNode = snapshot.anyMatch(rule.completedPatterns)
        val keepWatchingRaw = snapshot.anyMatch(rule.keepWatchingPatterns)
        // 兜底: 挽留按钮里混进转化 CTA 时一律放弃点击(见 CTA_DENY 的说明)
        val keepWatching = keepWatchingRaw?.takeIf { !CTA_DENY.containsMatchIn(it.normalized) }
        val closeByViewId = snapshot.anyViewIdEndsWith(rule.closeViewIdSuffixes)
        val closeByText = snapshot.nodes.firstOrNull { CLOSE_TEXT.matches(it.normalized) }
        // 关闭按钮必须是"小按钮"。控件 id 命中但面积接近整屏时(某些 SDK 把 close 挂在
        // 全屏容器上), 点它的中心等于点广告主体, 会直接打开落地页 —— 一律放弃。
        val closeNode = (closeByViewId ?: closeByText)?.takeIf { node ->
            val screenArea = snapshot.screenArea
            screenArea <= 0L || node.bounds.area <= screenArea * MAX_CLOSE_AREA_RATIO
        }

        val panelOpen = rule.panelOpenPatterns.isNotEmpty() &&
            rule.panelOpenPatterns.any { p -> snapshot.nodes.any { p.containsMatchIn(it.normalized) } }

        // 倒计时文本本身也算广告信号, 但要带上下文提示词, 避免把正片里的"剩余 12 秒"当成广告
        val countdownIsAd = countdownNode != null &&
            COUNTDOWN_HINT.containsMatchIn(countdownNode.normalized)

        /**
         * 强广告信号: 控件 id、倒计时、完成文案。
         * 只有"广告"两个字属于弱信号 —— 正片页里完全可能因为角标、评论、推荐位出现这两个字,
         * 仅凭弱信号就划走正片是不可接受的错误, 所以正片保护会压掉它。
         * 反过来, 解锁类激励广告即使浮在正片之上, 也一定带倒计时或 SDK 控件 id, 不会漏。
         *
         * 组合特征([adByAll])**不再算强信号**: 规则里「上滑」+「短剧」这种组合特征放宽之后,
         * 正片页也完全可能同时出现(红果正片页带「（番茄）微短剧备字…」备案号, 本身就含"短剧")。
         * 让它压制正片保护, 就等于允许"备案号 + 任意上滑提示"把正片划掉。宁可漏, 不可误。
         */
        val strongAdSignal = adByViewId != null || countdownIsAd || completedNode != null

        val dramaProtected = rule.dramaAllPatterns.isNotEmpty() &&
            snapshot.allMatch(rule.dramaAllPatterns) &&
            !strongAdSignal

        val isAd = !dramaProtected && !panelOpen && (
            adByText != null || adByAll != null || adByViewId != null ||
                completedNode != null || countdownIsAd
            )

        var confidence = 0f
        if (adByText != null) confidence += 0.45f
        if (adByAll != null) confidence += 0.55f
        if (adByViewId != null) confidence += 0.35f
        if (countdownIsAd) confidence += 0.30f
        if (completedNode != null) confidence += 0.20f
        if (dramaProtected) confidence -= 0.50f
        if (panelOpen) confidence -= 0.30f
        confidence = confidence.coerceIn(0f, 1f)

        return AdAssessment(
            isAd = isAd,
            adHit = adByText ?: adByAll ?: adByViewId,
            isDramaProtected = dramaProtected,
            panelOpen = panelOpen,
            countdownSeconds = if (countdownIsAd) countdownSeconds else null,
            countdownNode = countdownNode,
            isCompleted = completedNode != null,
            keepWatchingNode = keepWatching,
            closeNode = closeNode,
            confidence = confidence,
        )
    }

    /** 关闭按钮允许占屏面积上限。真机上的 × 通常远小于 1%, 这里留足余量。 */
    private const val MAX_CLOSE_AREA_RATIO = 0.15f
}
