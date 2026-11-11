package com.aios.flitr.engine.splash

import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode
import com.aios.flitr.engine.rules.SplashRules

/**
 * 开屏广告秒跳引擎。
 *
 * 三级匹配 + 双重防误触:
 * 1. 内置广告 SDK 控件 id (最准, 无歧义)
 * 2. 启发式语义 "跳过" (覆盖面广, 需要额外校验)
 * 3. 面积占比校验 (挡掉全屏诱导层与假按钮)
 *
 * 时间窗约束不在这里实现 —— 由 [com.aios.flitr.engine.EventRouter] 门控,
 * 这样引擎本身是无状态纯函数, 便于用真实样本回放测试。
 */
class SplashSkipEngine(private val rules: SplashRules) {

    data class Verdict(
        val target: TextNode? = null,
        val reason: String = "",
        val rejected: String? = null,
    ) {
        val shouldClick: Boolean get() = target != null
    }

    private val heuristic: Regex? = runCatching { Regex(rules.heuristicSkipRegex) }.getOrNull()

    fun evaluate(snapshot: ScreenSnapshot): Verdict {
        if (!snapshot.interactive) return Verdict(rejected = "页面不可交互")

        // 优先级 1: 广告 SDK 控件 id
        val sdkHit = snapshot.anyViewIdEndsWith(rules.globalSdkSkipViewIds)
        if (sdkHit != null) {
            return validate(sdkHit, snapshot, "命中广告 SDK 跳过控件")
        }

        // 优先级 2: 启发式语义
        val pattern = heuristic ?: return Verdict(rejected = "启发式正则非法")
        val candidates = snapshot.nodes.filter { node ->
            !node.fromOcr && !node.editable && pattern.matches(node.normalized)
        }
        if (candidates.isEmpty()) return Verdict()

        // 多个候选时优先面积最小的那个 —— 真跳过按钮通常是小控件
        val smallest = candidates.minByOrNull { it.bounds.area }
            ?: return Verdict(rejected = "候选为空")
        return validate(smallest, snapshot, "启发式命中「${smallest.normalized}」")
    }

    /**
     * 防假按钮欺诈:
     * - 面积超过屏占比阈值 → 判定为全屏诱导层, 拒绝点击;
     * - 文本过长 → 多半是正文里的"跳过"字样, 拒绝;
     * - 输入框 → 拒绝。
     */
    private fun validate(node: TextNode, snapshot: ScreenSnapshot, reason: String): Verdict {
        if (node.editable) return Verdict(rejected = "目标是输入框, 拒绝")
        if (node.normalized.length > MAX_TEXT_LENGTH) {
            return Verdict(rejected = "文本过长 (${node.normalized.length}), 疑似正文")
        }
        val ratio = snapshot.areaRatio(node.bounds)
        if (ratio > rules.maxNodeScreenAreaRatio) {
            return Verdict(
                rejected = "控件占屏 ${"%.1f".format(ratio * 100)}% 超过阈值 " +
                    "${"%.0f".format(rules.maxNodeScreenAreaRatio * 100)}%, 疑似诱导层",
            )
        }
        return Verdict(target = node, reason = "$reason (占屏 ${"%.1f".format(ratio * 100)}%)")
    }

    companion object {
        /** 超过这个长度基本不可能是按钮文案 */
        private const val MAX_TEXT_LENGTH = 12
    }
}
