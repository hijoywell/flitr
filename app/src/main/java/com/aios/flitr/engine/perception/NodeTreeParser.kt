package com.aios.flitr.engine.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.aios.flitr.engine.model.Bounds
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.SnapshotSource
import com.aios.flitr.engine.model.TextNode

/**
 * L1 感知: 无障碍节点树高速解析。
 *
 * 性能约束来自 PRD: 待机 CPU ≈ 0%, 日均耗电 < 0.8%。
 * 因此这里做了三件事:
 * 1. **浅层剪枝**: 限制递归深度与节点总数;
 * 2. **跳过不可见子树**: `isVisibleToUser == false` 或宽高为 0 直接不进;
 * 3. **迭代而非递归**: 避免深树下的栈开销。
 */
class NodeTreeParser(
    private val maxDepth: Int = 24,
    private val maxNodes: Int = 2000,
) {

    private val reuseRect = Rect()

    fun parse(
        root: AccessibilityNodeInfo?,
        packageName: String,
        className: String?,
        screenWidth: Int,
        screenHeight: Int,
        now: Long,
        interactive: Boolean = true,
    ): ScreenSnapshot {
        if (root == null) {
            return ScreenSnapshot(
                packageName = packageName,
                className = className,
                timestampMs = now,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                interactive = interactive,
            )
        }

        val nodes = ArrayList<TextNode>(64)
        // 栈元素: 节点 + 深度
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = stack.removeLast()
            visited++

            node.getBoundsInScreen(reuseRect)
            val bounds = Bounds(reuseRect.left, reuseRect.top, reuseRect.right, reuseRect.bottom)
            val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)

            // 关键: **父节点不可见不代表子树不可见**。
            // Compose、自绘容器、以及不少国产 App 会把外层容器的 isVisibleToUser 置为 false,
            // 而真正带文本的子节点是可见的。早期实现遇到不可见父节点就 `continue` 整棵子树,
            // 结果在红果的广告页上一条文本都拿不到 —— 实测踩过这个坑。
            // 现在只过滤"是否记录本节点", 不影响继续下钻; 规模由 maxNodes 兜底。
            if (visible && !bounds.isEmpty) {
                // text 与 contentDescription 都要读, 不能二选一。
                //
                // 早期这里写的是 `text ?: contentDescription`, 看起来合理, 实际会把
                // 一整类广告漏掉: 红果信息流里的拼多多广告, 商品卡/「商家推荐」/
                // 「@拼多多」/ 推广文案这 4 个关键节点 **text 都是空字符串**,
                // 内容全在 contentDescription 里。`?:` 只对 null 生效, 空字符串会
                // 被当成"有值"接受, contentDescription 于是永远读不到 ——
                // 实测这一页因此被判成 [NORMAL], 广告挂在屏幕上一动不动。
                //
                // 两者都有值时拼接而不是丢弃: 无障碍里 text 与 desc 描述的是
                // 同一控件的不同维度(可见文字 / 辅助说明), 合起来才是完整语义。
                val textPart = node.text?.toString()?.takeIf { it.isNotBlank() }
                val descPart = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                val rawText = when {
                    textPart != null && descPart != null -> {
                        if (textPart.contains(descPart)) textPart else "$textPart $descPart"
                    }
                    else -> textPart ?: descPart
                }
                val viewId = node.viewIdResourceName

                if (rawText != null || viewId != null) {
                    nodes += TextNode(
                        text = rawText.orEmpty(),
                        bounds = bounds,
                        viewId = viewId,
                        clickable = runCatching { node.isClickable }.getOrDefault(false),
                        editable = runCatching { node.isEditable }.getOrDefault(false),
                        depth = depth,
                    )
                }
            }

            if (depth < maxDepth) {
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    stack.addLast(child to depth + 1)
                }
            }
        }

        return ScreenSnapshot(
            packageName = packageName,
            className = className,
            timestampMs = now,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            nodes = nodes,
            source = SnapshotSource.NodeTree,
            interactive = interactive,
        )
    }

    /** 提取页面文本摘要, 用于日志与快照拾取器展示。 */
    fun summarizeTexts(snapshot: ScreenSnapshot, limit: Int = 40): String =
        snapshot.nodes.asSequence()
            .map { it.normalized }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(limit)
            .joinToString(" / ")
}
