package com.aios.flitr.engine

import com.aios.flitr.engine.model.Bounds
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode
import com.aios.flitr.engine.rules.AdIndicators
import com.aios.flitr.engine.rules.DramaSafeguards
import com.aios.flitr.engine.rules.VideoScenarioRule
import com.aios.flitr.engine.video.AdClassifier
import com.aios.flitr.engine.video.CompiledVideoRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信息流广告特征回归。
 *
 * 对应真机样本: 红果短剧把广告整屏画在 TextureView 里,
 * 页面上唯一可靠的判据是底部那行「上滑继续…短剧」跳转提示,
 * 而它在不同广告位上措辞不一致 —— 有的写「上滑继续看短剧」,
 * 有的写「上滑继续观看短剧」。规则必须两种都吃。
 */
class FeedAdMarkerTest {

    private fun node(
        text: String,
        left: Int = 100,
        top: Int = 2400,
        right: Int = 900,
        bottom: Int = 2480,
        viewId: String? = null,
    ) = TextNode(text = text, bounds = Bounds(left, top, right, bottom), viewId = viewId)

    private fun snapshot(vararg nodes: TextNode): ScreenSnapshot = ScreenSnapshot(
        packageName = "com.phoenix.read",
        className = "ShortSeriesActivity",
        timestampMs = 0L,
        screenWidth = 1272,
        screenHeight = 2772,
        nodes = nodes.toList(),
    )

    private val rule = CompiledVideoRule(
        VideoScenarioRule(
            packageName = "com.phoenix.read",
            adIndicators = AdIndicators(anyTextRegex = listOf("上滑继续(?:观看|看)短剧")),
            normalDramaSafeguards = DramaSafeguards(
                allTextRegex = listOf("第\\s*\\d+\\s*集", "选集"),
            ),
        )
    )

    // ---------- 提示语两种措辞都要命中 ----------

    @Test
    fun `短措辞_上滑继续看短剧_判定为广告`() {
        val verdict = AdClassifier.assess(snapshot(node("上滑继续看短剧")), rule)
        assertTrue("「上滑继续看短剧」应判定为广告", verdict.isAd)
        assertTrue(verdict.confidence > 0f)
    }

    @Test
    fun `长措辞_上滑继续观看短剧_判定为广告`() {
        val verdict = AdClassifier.assess(snapshot(node("上滑继续观看短剧")), rule)
        assertTrue("「上滑继续观看短剧」应判定为广告", verdict.isAd)
    }

    @Test
    fun `正片页不带提示语时不算广告`() {
        val verdict = AdClassifier.assess(
            snapshot(node("第85集"), node("选集 · 已完结 · 全109集"), node("全屏观看")),
            rule,
        )
        assertFalse("正片页不应判定为广告", verdict.isAd)
        assertTrue("应命中正片保护", verdict.isDramaProtected)
    }

    /**
     * 提示语是弱信号, 正片保护优先。
     *
     * 这条是「宁可漏关, 不可误关」的边界: 万一某个正片页也渲染出了这行提示,
     * 我们选择不动。实测正片页不会出现该提示, 所以代价为零。
     */
    @Test
    fun `正片特征齐全时压掉提示语`() {
        val verdict = AdClassifier.assess(
            snapshot(node("第85集"), node("选集"), node("上滑继续看短剧")),
            rule,
        )
        assertFalse("正片保护应压掉弱信号", verdict.isAd)
    }

    // ---------- 无文本页面的指纹不能坍缩 ----------

    /**
     * 红果广告页与正片页的节点树都没有文本, 且共用同一批 viewId。
     * 指纹若按文本计算会双双坍缩成 "0", 导致
     * VideoAdStateMachine.verifyPendingSwipe 误判"滑动未生效"而补偿重划, 误杀正片。
     */
    @Test
    fun `无文本时广告页与正片页指纹必须不同`() {
        val player = "com.phoenix.read:id/ttlive_player_render_view"
        val adPage = snapshot(
            node("", 0, 0, 1272, 2576, player),
            node("", 349, 1775, 923, 1930),
            node("", 430, 1810, 514, 1894),
        )
        val dramaPage = snapshot(
            node("", 0, 0, 1272, 2576, player),
            node("", 0, 143, 1272, 297),
        )
        assertNotEquals(
            "无文本页面不能坍缩成同一个指纹",
            adPage.fingerprint(),
            dramaPage.fingerprint(),
        )
    }

    @Test
    fun `无文本页面指纹不等于字符串0`() {
        val page = snapshot(node("", 0, 0, 1272, 2576, "com.phoenix.read:id/fwp"))
        assertNotEquals("无文本页面指纹不应是 \"0\"", "0", page.fingerprint())
    }

    @Test
    fun `同一页面重复采样指纹稳定`() {
        val a = snapshot(node("", 0, 0, 1272, 2576, "id/fwp"), node("", 0, 143, 1272, 297))
        val b = snapshot(node("", 0, 143, 1272, 297), node("", 0, 0, 1272, 2576, "id/fwp"))
        assertEquals("节点顺序不应影响指纹", a.fingerprint(), b.fingerprint())
    }

    @Test
    fun `有文本时仍按文本计算指纹`() {
        val a = snapshot(node("上滑继续看短剧"))
        val b = snapshot(node("上滑继续看短剧"))
        assertEquals(a.fingerprint(), b.fingerprint())
        assertNotEquals(a.fingerprint(), snapshot(node("第85集")).fingerprint())
    }
}
