package com.aios.flitr.engine

import com.aios.flitr.engine.model.Bounds
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode
import com.aios.flitr.engine.rules.AdIndicators
import com.aios.flitr.engine.rules.CountdownRules
import com.aios.flitr.engine.rules.VideoScenarioRule
import com.aios.flitr.engine.video.AdClassifier
import com.aios.flitr.engine.video.CompiledVideoRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「误点广告」回归。
 *
 * 真机事故: 红果拼多多信息流广告底部有「去领取」按钮, 规则把它误当成中途挽留弹窗的
 * 「继续观看」按钮, 于是 Flitr 主动点了它 —— 直接进入商品下单页。
 *
 * 这类错误的代价远高于漏划: 漏划一条广告只是多看几秒, 误点一次可能真的下单。
 * 因此除了把 `^去领取$` 从规则里删掉, 判定层还要有一道**独立于规则**的黑名单,
 * 防止以后有人再把转化 CTA 写进 keepWatchingDialogBtnRegex。
 */
class MisTapGuardTest {

    private fun node(
        text: String,
        left: Int = 400,
        top: Int = 1500,
        right: Int = 900,
        bottom: Int = 1600,
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

    /** 故意保留 `^去领取$`, 模拟"规则写错了"的情况, 验证判定层兜得住。 */
    private val unsafeRule = CompiledVideoRule(
        VideoScenarioRule(
            packageName = "com.phoenix.read",
            adIndicators = AdIndicators(anyTextRegex = listOf("上滑继续(?:观看|看)短剧")),
            countdownRules = CountdownRules(
                keepWatchingDialogBtnRegex = listOf(
                    "^继续观看$",
                    "^立即继续观看$",
                    "^开心收下$",
                    "^去领取$",
                ),
            ),
        )
    )

    @Test
    fun `去领取不能被当作挽留按钮点击`() {
        val verdict = AdClassifier.assess(
            snapshot(node("上滑继续看短剧"), node("去领取")),
            unsafeRule,
        )
        assertTrue("页面本身应识别为广告", verdict.isAd)
        assertNull("「去领取」是转化 CTA, 绝不能点", verdict.keepWatchingNode)
    }

    @Test
    fun `其他转化CTA同样被拦下`() {
        for (cta in listOf("立即抢购", "领券购买", "查看详情", "去看看", "立即下载")) {
            val verdict = AdClassifier.assess(
                snapshot(node("上滑继续看短剧"), node(cta)),
                CompiledVideoRule(
                    VideoScenarioRule(
                        packageName = "com.phoenix.read",
                        adIndicators = AdIndicators(anyTextRegex = listOf("上滑继续(?:观看|看)短剧")),
                        countdownRules = CountdownRules(keepWatchingDialogBtnRegex = listOf("^$cta$")),
                    )
                ),
            )
            assertNull("「$cta」不应被点击", verdict.keepWatchingNode)
        }
    }

    @Test
    fun `真正的挽留按钮仍然可以点击`() {
        for (btn in listOf("继续观看", "立即继续观看", "开心收下")) {
            val verdict = AdClassifier.assess(
                snapshot(node("上滑继续看短剧"), node(btn)),
                unsafeRule,
            )
            assertTrue("「$btn」应保留挽留按钮身份", verdict.keepWatchingNode != null)
        }
    }

    // ---------- 关闭按钮面积守卫 ----------

    @Test
    fun `全屏容器即使命中close控件id也不点`() {
        val rule = CompiledVideoRule(
            VideoScenarioRule(
                packageName = "com.phoenix.read",
                adIndicators = AdIndicators(anyTextRegex = listOf("上滑继续(?:观看|看)短剧")),
                countdownRules = CountdownRules(
                    completedTextRegex = listOf("已完成"),
                    modalCloseViewIdSuffix = listOf("tt_video_ad_close_layout"),
                ),
            )
        )
        // 容器铺满整屏: 点它的中心等于点广告主体, 会打开落地页
        val fullScreen = node("", 0, 0, 1272, 2772, "com.phoenix.read:id/tt_video_ad_close_layout")
        val verdict = AdClassifier.assess(snapshot(node("上滑继续看短剧"), fullScreen), rule)
        assertNull("整屏容器不能被当成关闭按钮", verdict.closeNode)
    }

    @Test
    fun `真正的小关闭按钮仍然可用`() {
        val rule = CompiledVideoRule(
            VideoScenarioRule(
                packageName = "com.phoenix.read",
                adIndicators = AdIndicators(anyTextRegex = listOf("上滑继续(?:观看|看)短剧")),
                countdownRules = CountdownRules(
                    completedTextRegex = listOf("已完成"),
                    modalCloseViewIdSuffix = listOf("iv_close"),
                ),
            )
        )
        val small = node("", 1150, 200, 1230, 280, "com.phoenix.read:id/iv_close")
        val verdict = AdClassifier.assess(snapshot(node("上滑继续看短剧"), small), rule)
        assertTrue("右上角小 × 应可用", verdict.closeNode != null)
    }

    @Test
    fun `文字X仍然可以作为关闭按钮`() {
        val rule = CompiledVideoRule(
            VideoScenarioRule(
                packageName = "com.phoenix.read",
                adIndicators = AdIndicators(anyTextRegex = listOf("上滑继续(?:观看|看)短剧")),
            )
        )
        val verdict = AdClassifier.assess(snapshot(node("上滑继续看短剧"), node("×", 1150, 200, 1230, 280)), rule)
        assertTrue("小面积文字 × 应可用", verdict.closeNode != null)
    }

    @Test
    fun `关闭按钮缺失时退回上滑`() {
        val rule = CompiledVideoRule(
            VideoScenarioRule(
                packageName = "com.phoenix.read",
                adIndicators = AdIndicators(anyTextRegex = listOf("上滑继续(?:观看|看)短剧")),
            )
        )
        val verdict = AdClassifier.assess(snapshot(node("上滑继续看短剧")), rule)
        assertNull(verdict.closeNode)
        assertFalse("没有可点的关闭按钮, 不应产出点击动作", verdict.keepWatchingNode != null)
    }
}
