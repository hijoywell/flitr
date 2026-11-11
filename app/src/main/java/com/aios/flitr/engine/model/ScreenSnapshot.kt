package com.aios.flitr.engine.model

/**
 * 屏幕上一个可读文本节点。
 *
 * 只保留自动化决策真正需要的字段: 文本、位置、控件 id、是否可点击。
 * 刻意不持有 [android.view.accessibility.AccessibilityNodeInfo] —— 节点对象会随窗口刷新失效,
 * 且持有它容易造成内存泄漏。需要点击时按 [bounds] 重新定位。
 */
data class TextNode(
    val text: String,
    val bounds: Bounds,
    val viewId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val fromOcr: Boolean = false,
) {
    /** 去掉首尾空白与换行后的文本, 用于匹配。 */
    val normalized: String get() = text.trim().replace('\n', ' ')
}

/** 屏幕矩形。用纯 Kotlin 数据类表达, 便于在无 Android 环境做单元测试。 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

enum class SnapshotSource {
    /** L1: 无障碍节点树 */
    NodeTree,

    /** L1 + L2: 节点树 + 静默截图局部 OCR */
    NodeTreeWithOcr,
}

/**
 * 当前屏幕的语义快照。所有感知层 (节点树 / OCR) 统一产出这个结构,
 * 上层判定逻辑因此不需要关心文本究竟来自哪个通道。
 */
data class ScreenSnapshot(
    val packageName: String,
    val className: String?,
    val timestampMs: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<TextNode> = emptyList(),
    val source: SnapshotSource = SnapshotSource.NodeTree,
    /** 当前窗口是否可交互; 锁屏、系统弹窗等场景为 false */
    val interactive: Boolean = true,
) {
    val screenArea: Long get() = screenWidth.toLong() * screenHeight.toLong()

    /** 控件面积占屏比, 用于防"假跳过按钮"欺诈。 */
    fun areaRatio(bounds: Bounds): Float {
        if (screenArea <= 0L) return 0f
        return (bounds.area.toFloat() / screenArea.toFloat())
    }

    fun anyMatch(patterns: List<Regex>): TextNode? {
        if (patterns.isEmpty()) return null
        for (node in nodes) {
            if (node.normalized.isEmpty()) continue
            if (patterns.any { it.containsMatchIn(node.normalized) }) return node
        }
        return null
    }

    /**
     * 所有 pattern 都必须命中, 但**每个 pattern 只需命中任意一个节点**。
     *
     * 早期实现是把整页文本拼成一个字符串再逐个 containsMatchIn, 这样带 `^...$` 锚点的规则
     * 永远不可能命中(拼接后的串开头结尾不可能是目标文本)。改成逐节点匹配后,
     * 「第 N 集」与「选集」这类跨节点的 AND 组合才真正可用。
     */
    fun allMatch(patterns: List<Regex>): Boolean {
        if (patterns.isEmpty()) return false
        return patterns.all { pattern -> nodes.any { pattern.containsMatchIn(it.normalized) } }
    }

    fun anyViewIdEndsWith(suffixes: List<String>): TextNode? {
        if (suffixes.isEmpty()) return null
        return nodes.firstOrNull { n ->
            val id = n.viewId ?: return@firstOrNull false
            suffixes.any { id.endsWith(it) }
        }
    }

    /**
     * 页面指纹: 用于判断一次自动滑动是否真的翻页了。
     * 只取文本内容, 忽略顺序与空白, 避免进度条秒数变化导致误判为"已翻页"。
     */
    fun fingerprint(excludingSeconds: Boolean = true): String {
        val tokens = nodes.asSequence()
            .map { it.normalized }
            .filter { it.isNotEmpty() }
            .map { if (excludingSeconds) it.replace(COUNTDOWN_TOKEN, "#") else it }
            .sorted()
            .joinToString("|")
        return tokens.hashCode().toString()
    }

    companion object {
        /** 匹配 "15s" / "12秒" / "剩余 8 s" 这类会随时间变化的片段 */
        private val COUNTDOWN_TOKEN = Regex("\\d{1,3}\\s*(?:s|S|秒)")

        fun empty(packageName: String = "", now: Long = 0L): ScreenSnapshot = ScreenSnapshot(
            packageName = packageName,
            className = null,
            timestampMs = now,
            screenWidth = 0,
            screenHeight = 0,
        )
    }
}
