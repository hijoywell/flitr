package com.aios.flitr.engine.popup

import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode
import com.aios.flitr.engine.rules.PopupRules

/**
 * 插屏 / 浮层广告的关闭引擎。
 *
 * 设计原则与视频状态机一致: **宁可漏关, 不可误关**。
 *
 * 真机样本（携程首页红包弹窗）暴露了三个必须处理的点:
 * 1. 关闭按钮是个**没有文字的 ImageView**, 只能靠「可点击 + 尺寸小 + 位于右上角」定位;
 * 2. 广告主体带 `content-desc="广告素材"`, 这是可靠的广告标识;
 * 3. **全屏容器本身也是 clickable 的** —— 如果只按「找可点击节点」去点, 会直接点开广告。
 *    所以面积上限（maxCloseAreaRatio）不是优化项, 而是安全底线。
 */
class PopupAdEngine(private val rules: PopupRules) {

    data class Verdict(
        val closeTarget: TextNode? = null,
        val marker: TextNode? = null,
        val reason: String = "",
        val rejected: String? = null,
    ) {
        val shouldClose: Boolean get() = closeTarget != null
    }

    fun evaluate(snapshot: ScreenSnapshot): Verdict {
        if (!snapshot.interactive) return Verdict(rejected = "页面不可交互")
        if (snapshot.screenWidth <= 0 || snapshot.screenHeight <= 0) {
            return Verdict(rejected = "屏幕尺寸未知")
        }

        // --- 1. 必须存在广告标识 ---
        val marker = snapshot.nodes.firstOrNull { node ->
            node.normalized.isNotEmpty() && rules.adMarkers.any { node.normalized.contains(it) }
        } ?: return Verdict(rejected = "未发现广告标识")

        // --- 2. 找候选关闭控件 ---
        val candidates = snapshot.nodes.filter { node ->
            node.clickable &&
                node !== marker &&
                snapshot.areaRatio(node.bounds) in MIN_AREA_RATIO..rules.maxCloseAreaRatio &&
                node.normalized.length <= MAX_CLOSE_TEXT_LENGTH
        }
        if (candidates.isEmpty()) {
            return Verdict(marker = marker, rejected = "广告标识存在, 但未找到小尺寸可点击控件")
        }

        // --- 3. 打分: 关闭文案 > 右上角位置 > 更小面积 ---
        val best = candidates.maxByOrNull { node -> score(node, snapshot) }!!
        val score = score(best, snapshot)

        val hasCloseText = matchesCloseText(best)
        val inTopRight = isTopRight(best, snapshot)

        // 既没有关闭文案、又不在右上角 —— 位置可疑, 放弃
        if (!hasCloseText && !inTopRight) {
            return Verdict(
                marker = marker,
                rejected = "候选控件既非关闭文案也不在右上角 (score=$score), 放弃",
            )
        }

        return Verdict(
            closeTarget = best,
            marker = marker,
            reason = buildString {
                append("广告标识「${marker.normalized}」")
                append(" + 关闭控件 ")
                append("(${best.bounds.centerX},${best.bounds.centerY}) ")
                append("占屏 ${"%.2f".format(snapshot.areaRatio(best.bounds) * 100)}%")
                if (hasCloseText) append(" 文案命中") else append(" 位于右上角")
            },
        )
    }

    private fun score(node: TextNode, snapshot: ScreenSnapshot): Int {
        var s = 0
        if (matchesCloseText(node)) s += 100
        if (isTopRight(node, snapshot)) s += 50
        if (node.normalized.isEmpty()) s += 10          // 无文字的小图标更像 ×
        // 面积越小越像按钮
        s += ((1f - snapshot.areaRatio(node.bounds) / rules.maxCloseAreaRatio) * 20).toInt()
        return s
    }

    private fun matchesCloseText(node: TextNode): Boolean {
        if (node.normalized.isEmpty()) return false
        return rules.closeTexts.any { node.normalized.equals(it, ignoreCase = true) }
    }

    private fun isTopRight(node: TextNode, snapshot: ScreenSnapshot): Boolean {
        val cx = node.bounds.centerX.toFloat() / snapshot.screenWidth
        val cy = node.bounds.centerY.toFloat() / snapshot.screenHeight
        return cx >= rules.minCloseCenterXRatio && cy <= rules.maxCloseCenterYRatio
    }

    companion object {
        /** 低于这个占比的多半是误触目标或装饰点, 不当作关闭按钮 */
        const val MIN_AREA_RATIO = 0.00002f

        /** 关闭按钮文案不会很长, 超过这个长度说明是正文 */
        const val MAX_CLOSE_TEXT_LENGTH = 6
    }
}
