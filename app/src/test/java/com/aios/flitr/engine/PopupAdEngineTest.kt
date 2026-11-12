package com.aios.flitr.engine

import com.aios.flitr.engine.model.Bounds
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode
import com.aios.flitr.engine.popup.PopupAdEngine
import com.aios.flitr.engine.rules.PopupRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插屏广告关闭引擎的回归用例。
 *
 * 夹具直接来自真机样本（携程首页红包弹窗, 1272×2772）:
 *   - 全屏容器 ViewGroup 也是 clickable —— 误点它会直接打开广告
 *   - 关闭按钮是**没有文字**的 ImageView, 95×95, 位于右上角
 *   - 广告主体带 content-desc="广告素材"
 */
class PopupAdEngineTest {

    private val rules = PopupRules()

    private fun node(
        text: String = "",
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        clickable: Boolean = false,
        depth: Int = 2,
    ) = TextNode(
        text = text,
        bounds = Bounds(left, top, right, bottom),
        clickable = clickable,
        depth = depth,
    )

    private fun snapshot(vararg nodes: TextNode) = ScreenSnapshot(
        packageName = "ctrip.android.view",
        className = "CtripHomeActivity",
        timestampMs = 0L,
        screenWidth = 1272,
        screenHeight = 2772,
        nodes = nodes.toList(),
    )

    // 真机样本的四类节点
    private fun fullScreenContainer() = node(left = 0, top = 143, right = 1272, bottom = 2772, clickable = true, depth = 1)
    private fun closeButton() = node(left = 1082, top = 548, right = 1177, bottom = 643, clickable = true, depth = 2)
    private fun adMaterial() = node("广告素材", left = 96, top = 738, right = 1177, bottom = 2178, depth = 2)
    private fun ctaButton() = node("点击收下", left = 282, top = 2030, right = 987, bottom = 2178, depth = 2)

    /** 模拟 X(Twitter) 信息流: 广告标识与 ⋮ 按钮都埋在很深的层级里 */
    private fun timelineSnapshot() = ScreenSnapshot(
        packageName = "com.twitter.android",
        className = "MainActivity",
        timestampMs = 0L,
        screenWidth = 1272,
        screenHeight = 2772,
        nodes = listOf(
            node("X 为你推荐", left = 0, top = 0, right = 1272, bottom = 300, depth = 1),
            TextNode("广告 · 0:10", Bounds(900, 400, 1020, 460), depth = 9),
            TextNode("", Bounds(1150, 500, 1250, 580), clickable = true, depth = 9),
            TextNode("这条推文提到免广告", Bounds(100, 600, 900, 700), depth = 9),
        ),
    )

    @Test
    fun `携程红包弹窗 应命中右上角关闭按钮`() {
        val engine = PopupAdEngine(rules)
        val verdict = engine.evaluate(
            snapshot(fullScreenContainer(), closeButton(), adMaterial(), ctaButton()),
        )

        assertTrue("应判定为可关闭", verdict.shouldClose)
        val target = verdict.closeTarget!!
        assertEquals("应点击 × 的中心", 1129, target.bounds.centerX)
        assertEquals(595, target.bounds.centerY)
        assertNotNull(verdict.marker)
        assertTrue(verdict.marker!!.normalized.contains("广告"))
    }

    @Test
    fun `绝不能点击全屏容器`() {
        val engine = PopupAdEngine(rules)
        val verdict = engine.evaluate(
            snapshot(fullScreenContainer(), closeButton(), adMaterial(), ctaButton()),
        )
        val target = verdict.closeTarget!!
        assertTrue("目标不应该是全屏容器", target.bounds.area < 200_000)
        assertEquals(1129, target.bounds.centerX)
    }

    @Test
    fun `没有广告标识时 不动作`() {
        val engine = PopupAdEngine(rules)
        // 一个普通对话框: 有关闭按钮但没有广告标识
        val verdict = engine.evaluate(
            snapshot(
                fullScreenContainer(),
                closeButton(),
                node("确定要退出吗", left = 300, top = 1200, right = 900, bottom = 1300),
            ),
        )
        assertFalse("没有广告标识就不该关", verdict.shouldClose)
        assertTrue(verdict.rejected!!.contains("广告标识"))
    }

    @Test
    fun `只有全屏可点击容器时 不动作`() {
        val engine = PopupAdEngine(rules)
        val verdict = engine.evaluate(
            snapshot(fullScreenContainer(), adMaterial()),
        )
        assertFalse("没有小尺寸控件时不能点容器", verdict.shouldClose)
    }

    @Test
    fun `关闭按钮在左下角且无关闭文案 应放弃`() {
        val engine = PopupAdEngine(rules)
        val bottomLeft = node(left = 60, top = 2400, right = 155, bottom = 2495, clickable = true)
        val verdict = engine.evaluate(
            snapshot(fullScreenContainer(), bottomLeft, adMaterial()),
        )
        assertFalse("位置可疑应放弃", verdict.shouldClose)
        assertTrue(verdict.rejected!!.contains("右上角"))
    }

    @Test
    fun `带关闭文案的控件 即使在下方也命中`() {
        val engine = PopupAdEngine(rules)
        val closeText = node("关闭", left = 500, top = 2300, right = 700, bottom = 2380, clickable = true)
        val verdict = engine.evaluate(
            snapshot(fullScreenContainer(), closeText, adMaterial()),
        )
        assertTrue("有明确关闭文案时应当命中", verdict.shouldClose)
        assertEquals("关闭", verdict.closeTarget!!.normalized)
    }

    @Test
    fun `长文本可点击控件 不应被当成关闭按钮`() {
        val engine = PopupAdEngine(rules)
        val longText = node(
            "点击收下这个红包福利",
            left = 1000, top = 500, right = 1200, bottom = 600,
            clickable = true,
        )
        val verdict = engine.evaluate(
            snapshot(fullScreenContainer(), longText, adMaterial()),
        )
        assertFalse("长文本是正文/CTA, 不是关闭按钮", verdict.shouldClose)
    }

    @Test
    fun `页面不可交互时不动作`() {
        val engine = PopupAdEngine(rules)
        val snap = snapshot(fullScreenContainer(), closeButton(), adMaterial())
            .copy(interactive = false)
        assertFalse(engine.evaluate(snap).shouldClose)
    }

    /**
     * 线上事故回归: X(Twitter) 信息流里一条推文含「免广告」, 右上角还有 ⋮ 按钮,
     * 引擎把 ⋮ 当成关闭按钮点下去, 弹出了用户根本没点的菜单。
     * 根因: 广告标识埋在深层节点里, 说明「页面含广告」而非「弹窗是广告」。
     */
    @Test
    fun `信息流里埋的广告标识不会被当成弹窗`() {
        val engine = PopupAdEngine(rules)
        val verdict = engine.evaluate(timelineSnapshot())
        assertFalse("深层广告标识不应触发弹窗关闭", verdict.shouldClose)
    }

    @Test
    fun `长文本广告标识不应命中`() {
        val engine = PopupAdEngine(rules)
        val long = node(
            "我现在觉得 Google 的 200 美金 AI 套餐非常有性价比, YouTube Premium 看油管听播客免广告",
            left = 100, top = 400, right = 1100, bottom = 700, depth = 2,
        )
        val closeBtn = node(left = 1150, top = 500, right = 1250, bottom = 580, clickable = true, depth = 3)
        val verdict = engine.evaluate(snapshot(fullScreenContainer(), closeBtn, long))
        assertFalse("长文本是正文不是角标", verdict.shouldClose)
    }
}
