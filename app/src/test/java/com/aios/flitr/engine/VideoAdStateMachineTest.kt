package com.aios.flitr.engine

import com.aios.flitr.engine.model.Bounds
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode
import com.aios.flitr.engine.rules.AdIndicators
import com.aios.flitr.engine.rules.CountdownRules
import com.aios.flitr.engine.rules.DramaSafeguards
import com.aios.flitr.engine.rules.VideoScenarioRule
import com.aios.flitr.engine.splash.SplashSkipEngine
import com.aios.flitr.engine.rules.SplashRules
import com.aios.flitr.engine.video.AutomationCommand
import com.aios.flitr.engine.video.CompiledVideoRule
import com.aios.flitr.engine.video.VideoAdStateMachine
import com.aios.flitr.engine.video.VideoHostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心引擎离线回归。
 *
 * 这些用例的意义: 状态机的全部时间逻辑都能脱离设备验证。
 * 红果改版、规则调整、手势参数变化时, 先跑这一组用例, 再上真机。
 */
class VideoAdStateMachineTest {

    // ---------- 夹具 ----------

    private fun node(
        text: String,
        left: Int = 100,
        top: Int = 100,
        right: Int = 300,
        bottom: Int = 180,
        viewId: String? = null,
        editable: Boolean = false,
    ) = TextNode(
        text = text,
        bounds = Bounds(left, top, right, bottom),
        viewId = viewId,
        editable = editable,
    )

    private fun snapshot(vararg nodes: TextNode, pkg: String = "com.phoenix.read"): ScreenSnapshot =
        ScreenSnapshot(
            packageName = pkg,
            className = "MainActivity",
            timestampMs = 0L,
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = nodes.toList(),
        )

    private val rule = CompiledVideoRule(
        VideoScenarioRule(
            packageName = "com.phoenix.read",
            appName = "红果短剧",
            debounceMs = 450,
            adIndicators = AdIndicators(
                anyTextRegex = listOf("^广告$", "^查看详情$", ".*秒后可?(领取|解锁).*"),
                anyViewIdSuffix = listOf("ad_label"),
            ),
            normalDramaSafeguards = DramaSafeguards(
                allTextRegex = listOf("第\\s*\\d+\\s*集", "选集"),
                excludeWhenPanelOpenRegex = listOf("发表评论.*"),
            ),
            countdownRules = CountdownRules(
                extractRegex = "(?:还剩|观看|广告)?\\s*(\\d{1,3})\\s*(?:s|S|秒)",
                completedTextRegex = listOf("已完成", "可划走", "领取成功"),
                keepWatchingDialogBtnRegex = listOf("^继续观看$", "^开心收下$"),
                modalCloseViewIdSuffix = listOf("iv_close"),
                maxWaitSeconds = 40,
            ),
        )
    )

    /** 带「返回 + 上滑继续观看短剧」组合特征的规则, 对应真机抓到的红果广告页。 */
    private val ruleWithCombo = CompiledVideoRule(
        VideoScenarioRule(
            packageName = "com.phoenix.read",
            appName = "红果短剧",
            debounceMs = 450,
            adIndicators = AdIndicators(
                anyTextRegex = listOf(
                    "^广告$",
                    "^不感兴趣$",
                    "^去商城购买$",
                    "^图集\\d+/\\d+$",
                    "商家推荐",
                    "^\\s*@?拼多多\\s*$",
                ),
                allTextRegex = listOf("^返回$", "^上滑继续观看短剧$"),
            ),
            normalDramaSafeguards = DramaSafeguards(
                allTextRegex = listOf("第\\s*\\d+\\s*集", "选集"),
                excludeWhenPanelOpenRegex = listOf("发表评论.*"),
            ),
            countdownRules = CountdownRules(
                keepWatchingDialogBtnRegex = listOf("^继续观看$", "^开心收下$"),
            ),
        )
    )

    // ---------- 用例 ----------

    @Test
    fun `无倒计时广告 先观察稳定帧 再上滑`() {
        val machine = VideoAdStateMachine(rule)
        val ad = snapshot(node("广告"), node("查看详情"))

        val first = machine.onSnapshot(ad, now = 10_000)
        assertTrue("首次命中应进入稳定帧观察", first.state is VideoHostState.DebouncingAd)
        assertTrue("观察期内不应产生动作", first.commands.none { it is AutomationCommand.SwipeUp })
        assertTrue(
            "应要求上层在观察期结束后重新投喂快照",
            first.commands.any { it is AutomationCommand.ScheduleRecheck },
        )

        val second = machine.onSnapshot(ad, now = 10_500)
        val swipe = second.commands.filterIsInstance<AutomationCommand.SwipeUp>().firstOrNull()
        assertTrue("稳定帧结束后应上滑", swipe != null)
        assertTrue("应处于执行动作状态", second.state is VideoHostState.ExecutingAction)
    }

    @Test
    fun `有倒计时广告 不立即上滑 而是进入读秒`() {
        val machine = VideoAdStateMachine(rule)
        val ad = snapshot(node("广告"), node("15秒后可领取"))

        machine.onSnapshot(ad, now = 10_000)
        val second = machine.onSnapshot(ad, now = 10_500)

        val waiting = second.state as? VideoHostState.WaitingCountdown
        assertTrue("应进入读秒状态", waiting != null)
        assertEquals("应解析出 15 秒", 15, waiting!!.initialSeconds)
        assertTrue(
            "读秒期间绝不允许上滑",
            second.commands.none { it is AutomationCommand.SwipeUp },
        )
    }

    @Test
    fun `倒计时归零后 有弹窗则点关闭`() {
        val machine = VideoAdStateMachine(rule)
        val ad = snapshot(node("广告"), node("15秒后可领取"))
        machine.onSnapshot(ad, now = 10_000)
        machine.onSnapshot(ad, now = 10_500)

        val done = snapshot(
            node("广告"),
            node("已完成"),
            node("×", viewId = "com.phoenix.read:id/iv_close"),
        )
        val result = machine.onSnapshot(done, now = 26_000)
        val click = result.commands.filterIsInstance<AutomationCommand.ClickNode>().firstOrNull()
        assertTrue("应点击关闭按钮", click != null)
        assertEquals("×", click!!.target.normalized)
    }

    @Test
    fun `倒计时归零后 无弹窗则上滑`() {
        val machine = VideoAdStateMachine(rule)
        val ad = snapshot(node("广告"), node("15秒后可领取"))
        machine.onSnapshot(ad, now = 10_000)
        machine.onSnapshot(ad, now = 10_500)

        val done = snapshot(node("广告"), node("已完成"))
        val result = machine.onSnapshot(done, now = 26_000)
        assertTrue(
            "信息流内嵌广告应上滑而非点击",
            result.commands.any { it is AutomationCommand.SwipeUp },
        )
    }

    @Test
    fun `正片页面 绝不动作`() {
        val machine = VideoAdStateMachine(rule)
        val drama = snapshot(
            node("第 19 集"),
            node("选集"),
            node("深宅迷雾 · 第 19 集 (全 82 集)"),
        )
        val result = machine.onSnapshot(drama, now = 10_000)
        assertTrue("正片应静默", result.commands.isEmpty())
        assertEquals(VideoHostState.WatchingNormalVideo, result.state)
    }

    @Test
    fun `正片特征存在时 即使出现广告字样也不动作`() {
        val machine = VideoAdStateMachine(rule)
        val ambiguous = snapshot(
            node("第 3 集"),
            node("选集"),
            node("广告"),
        )
        val result = machine.onSnapshot(ambiguous, now = 10_000)
        assertTrue("正片保护优先级高于广告判定", result.commands.isEmpty())
    }

    @Test
    fun `用户触屏后 冷却期内完全静默`() {
        val machine = VideoAdStateMachine(rule)
        machine.onUserTouch(now = 10_000)

        val ad = snapshot(node("广告"), node("查看详情"))
        val result = machine.onSnapshot(ad, now = 11_000)
        assertTrue("冷却期内不应动作", result.commands.isEmpty())
        assertTrue(result.state is VideoHostState.UserTouchCooldown)
    }

    @Test
    fun `冷却结束后恢复判断`() {
        val machine = VideoAdStateMachine(rule)
        machine.onUserTouch(now = 10_000)

        val ad = snapshot(node("广告"), node("查看详情"))
        val afterCooldown = machine.onSnapshot(ad, now = 14_000)
        assertTrue(
            "冷却结束后应重新进入观察",
            afterCooldown.state is VideoHostState.DebouncingAd,
        )
    }

    @Test
    fun `倒计时中途被挽留弹窗拦截 应点击继续观看`() {
        val machine = VideoAdStateMachine(rule)
        val ad = snapshot(node("广告"), node("15秒后可领取"))
        machine.onSnapshot(ad, now = 10_000)
        machine.onSnapshot(ad, now = 10_500)

        val interrupted = snapshot(
            node("广告"),
            node("15秒后可领取"),
            node("继续观看"),
        )
        val result = machine.onSnapshot(interrupted, now = 13_000)
        val click = result.commands.filterIsInstance<AutomationCommand.ClickNode>().firstOrNull()
        assertTrue("应自动点击继续观看", click != null)
        assertEquals("继续观看", click!!.target.normalized)
    }

    @Test
    fun `滑动未生效 补偿一次后仍无效则停止介入`() {
        val machine = VideoAdStateMachine(rule)
        val ad = snapshot(node("广告"), node("查看详情"))

        machine.onSnapshot(ad, now = 10_000)
        val swipe = machine.onSnapshot(ad, now = 10_500)
        assertTrue(swipe.commands.any { it is AutomationCommand.SwipeUp })

        // 800ms 后页面指纹没变 -> 补偿重试
        val retry = machine.onSnapshot(ad, now = 11_300)
        val boosted = retry.commands.filterIsInstance<AutomationCommand.SwipeUp>().firstOrNull()
        assertTrue("应触发补偿重试", boosted != null)
        assertTrue("补偿应放大滑动跨度", boosted!!.boost > 1f)

        // 再次未变 -> 标记人工介入, 不再狂划
        val giveUp = machine.onSnapshot(ad, now = 12_100)
        assertTrue("应停止介入", giveUp.state is VideoHostState.NeedsManual)
        assertTrue("停止后不应再有动作", giveUp.commands.none { it is AutomationCommand.SwipeUp })
    }

    @Test
    fun `页面已翻走 不应重复动作`() {
        val machine = VideoAdStateMachine(rule)
        val ad = snapshot(node("广告"), node("查看详情"))
        machine.onSnapshot(ad, now = 10_000)
        machine.onSnapshot(ad, now = 10_500)

        val nextEpisode = snapshot(node("第 20 集"), node("选集"), node("发表评论..."))
        val result = machine.onSnapshot(nextEpisode, now = 11_300)
        assertTrue("翻页成功后应静默", result.commands.isEmpty())
        assertEquals(VideoHostState.WatchingNormalVideo, result.state)
    }

    @Test
    fun `用户正在选集面板 不介入`() {
        val machine = VideoAdStateMachine(rule)
        val panelOpen = snapshot(
            node("广告"),
            node("发表评论..."),
        )
        val result = machine.onSnapshot(panelOpen, now = 10_000)
        assertTrue("用户操作面板时应让路", result.commands.isEmpty())
    }

    /**
     * 真机样本回归: 红果广告页的节点树只有「返回」+「上滑继续观看短剧」,
     * 广告主体画在 SurfaceView 里完全不可见。这条用例锁定该组合能被识别。
     */
    @Test
    fun `红果广告页组合特征 应被识别为广告`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val adPage = snapshot(
            node("返回"),
            node("上滑继续观看短剧"),
        )
        val first = machine.onSnapshot(adPage, now = 10_000)
        assertTrue(first.state is VideoHostState.DebouncingAd)

        val second = machine.onSnapshot(adPage, now = 10_500)
        assertTrue(
            "组合特征命中后应上滑划走",
            second.commands.any { it is AutomationCommand.SwipeUp },
        )
    }

    /**
     * 真机样本回归: 「上滑继续观看短剧」不能被当成「继续观看」挽留弹窗按钮。
     * 早期用 containsMatchIn 的 `继续观看` 会误命中这个滚动提示, 导致去点提示文字。
     */
    @Test
    fun `滚动提示不能被当成继续观看按钮`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val adPage = snapshot(node("返回"), node("上滑继续观看短剧"))
        machine.onSnapshot(adPage, now = 10_000)
        val result = machine.onSnapshot(adPage, now = 10_500)
        val click = result.commands.filterIsInstance<AutomationCommand.ClickNode>().firstOrNull()
        assertTrue("不应点击任何节点", click == null)
        assertTrue("应上滑而不是点击", result.commands.any { it is AutomationCommand.SwipeUp })
    }

    /** 正片页没有这两个特征, 组合规则不应误触发 */
    @Test
    fun `正片页缺少组合特征时 不误判`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val drama = snapshot(node("第 92 集"), node("选集"), node("全屏观看"))
        val result = machine.onSnapshot(drama, now = 10_000)
        assertTrue("正片不应被划走", result.commands.isEmpty())
    }

    /**
     * 真机回归: 广告页节点树抖动导致永远划不走。
     *
     * 红果的商城图集广告页, 节点树在读得到「上滑继续看短剧」的厚帧和读不到的薄帧之间
     * 每 5 秒来回跳。旧实现里只要出现一次"非广告"就复位状态机, debounce 计时永远走不完,
     * 广告挂在屏幕上一整局都不动。这里用厚帧/薄帧交替复现。
     */
    @Test
    fun `广告信号间歇性缺失时 仍能完成上滑`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val thick = snapshot(node("返回"), node("上滑继续观看短剧"))
        val thin = snapshot() // 薄帧: 一个文本节点都没有

        // 交替投喂, 模拟 5 秒周期抖动; 观察期 450ms, 每次间隔 1000ms
        var now = 10_000L
        machine.onSnapshot(thick, now) // 进入观察
        var swiped = false
        for (i in 0 until 12) {
            now += 1_000L
            val feed = if (i % 2 == 0) thin else thick
            val r = machine.onSnapshot(feed, now)
            if (r.commands.any { it is AutomationCommand.SwipeUp }) {
                swiped = true
                break
            }
        }
        assertTrue("薄帧不应打断广告观察, 最终应上滑", swiped)
    }

    /** 但连续读不到广告信号(真的不是广告了)必须复位, 不能一直挂着。 */
    @Test
    fun `连续非广告帧 超容忍后复位`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val thick = snapshot(node("返回"), node("上滑继续观看短剧"))
        val drama = snapshot(node("第 92 集"), node("选集"))

        machine.onSnapshot(thick, now = 10_000)
        assertTrue(machine.state is VideoHostState.DebouncingAd)

        // 喂正片帧, 直到超过宽容时长
        var now = 10_500L
        var last = machine.onSnapshot(drama, now)
        while (now - 10_500L <= VideoAdStateMachine.AD_MISS_GRACE_MS + 1000L) {
            now += 500L
            last = machine.onSnapshot(drama, now)
        }
        assertTrue(
            "连续正片帧超容忍后应复位到 WatchingNormalVideo, 实际=${last.state}",
            last.state is VideoHostState.WatchingNormalVideo,
        )
        assertTrue("复位后不应有动作", last.commands.none { it is AutomationCommand.SwipeUp })
    }

    /**
     * 高频薄帧不能把宽容额度吃光。
     *
     * 同步判定每 300ms 喂一帧薄帧, 按帧数计的容忍两秒内就耗尽了,
     * 广告确认被打断 —— 真机表现就是"广告又不动了"。按**时长**计则不受采样频率影响。
     */
    @Test
    fun `高频薄帧不打断广告观察`() {        val machine = VideoAdStateMachine(ruleWithCombo)
        val thick = snapshot(node("返回"), node("上滑继续观看短剧"))
        val thin = snapshot()

        machine.onSnapshot(thick, now = 10_000)
        assertTrue(machine.state is VideoHostState.DebouncingAd)

        // 密集喂薄帧(每 300ms 一帧, 共 10 帧 = 3 秒), 全程都在宽容时长内
        var now = 10_300L
        var swiped = false
        repeat(10) {
            val r = machine.onSnapshot(thin, now)
            if (r.commands.any { it is AutomationCommand.SwipeUp }) swiped = true
            now += 300L
        }
        assertTrue("薄帧不应打断观察, 状态仍应是观察/执行", machine.state !is VideoHostState.WatchingNormalVideo)

        // 厚帧回来后应能完成确认
        val done = machine.onSnapshot(thick, now)
        assertTrue(
            "厚帧回来应完成上滑, 实际=${done.state} cmds=${done.commands}",
            done.commands.any { it is AutomationCommand.SwipeUp } || swiped,
        )
    }

    /**
     * 真机回归: 商城图集广告(玻璃水)节点树里有 4+ 条文本(商城好物省心选/不感兴趣/
     * 去商城购买/已售N件), 超过 OCR 触发阈值导致 OCR 从不运行, 而决定性的
     * 「上滑继续看短剧」画在视频层里节点树看不到 —— 结果规则永远不命中。
     * 修法: 把商城卡片特征文本(不感兴趣/去商城购买/图集N/N)加进 anyTextRegex,
     * 让节点树单独就能确认广告。这些控件正片页绝不会出现。
     */
    @Test
    fun `商城图集广告卡片文本即可确认广告`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val page = snapshot(
            node("商城好物省心选"),
            node("不感兴趣"),
            node("去商城购买"),
            node("已售647件"),
        )
        machine.onSnapshot(page, now = 10_000)
        val result = machine.onSnapshot(page, now = 10_500)
        assertTrue(
            "商城卡片文本应命中广告并上滑, 实际=${result.state} cmds=${result.commands}",
            result.commands.any { it is AutomationCommand.SwipeUp },
        )
    }

    /** 但正片页不会出现这些控件 —— 带剧集特征时绝不误判 */
    @Test
    fun `正片页即使多文本也不命中商城特征`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val drama = snapshot(
            node("第 471 集"),
            node("选集"),
            node("魔神人生: 弃子问天第一季"),
            node("全屏观看"),
        )
        val result = machine.onSnapshot(drama, now = 10_000)
        assertTrue(
            "正片页不应被划走, 实际=${result.commands}",
            result.commands.none { it is AutomationCommand.SwipeUp },
        )
    }

    /**
     * 真机回归: 信息流拼多多广告的识别特征全在 contentDescription 里。
     *
     * 该页节点树 text 只有 8 条导航栏文字, 广告的「拼多多」「商家推荐」
     * 「@拼多多」完全不在 text 中 —— 早期 NodeTreeParser 用 `text ?: desc`
     * 二选一, 空字符串被当成有值, desc 永远读不到, 这一页因此被判成 [NORMAL]。
     *
     * 本用例直接验证规则侧: desc 被并入 text 之后应能命中。
     */
    @Test
    fun `信息流拼多多广告 desc 特征可命中`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        // 节点树 text 只有底部导航, 广告信息以 desc 形式落在节点上
        val page = snapshot(
            node("首页"),
            node("剧场"),
            node("商城"),
            node("我的"),
            node("拼多多"),
            node("商家推荐"),
            node("@拼多多"),
        )
        machine.onSnapshot(page, now = 10_000)
        val result = machine.onSnapshot(page, now = 10_500)
        assertTrue(
            "商家推荐/拼多多 特征应命中广告, 实际=${result.state} cmds=${result.commands}",
            result.commands.any { it is AutomationCommand.SwipeUp },
        )
    }

    /** 普通正片信息流(纯导航文字 + 剧集)不应被这些新特征误伤 */
    @Test
    fun `普通信息流页不命中商家推荐特征`() {
        val machine = VideoAdStateMachine(ruleWithCombo)
        val drama = snapshot(node("首页"), node("剧场"), node("推荐"))
        val result = machine.onSnapshot(drama, now = 10_000)
        assertTrue(
            "普通信息流不应被划走, 实际=${result.commands}",
            result.commands.none { it is AutomationCommand.SwipeUp },
        )
    }
}

/** 开屏引擎的防误触用例。 */
class SplashSkipEngineTest {

    private val rules = SplashRules(
        globalSdkSkipViewIds = listOf("tt_splash_skip_btn"),
        heuristicSkipRegex = SplashRules.DEFAULT_SKIP_REGEX,
        maxNodeScreenAreaRatio = 0.12f,
    )

    private fun snapshot(vararg nodes: TextNode) = ScreenSnapshot(
        packageName = "com.example",
        className = null,
        timestampMs = 0L,
        screenWidth = 1080,
        screenHeight = 2400,
        nodes = nodes.toList(),
    )

    private fun node(
        text: String,
        areaRatio: Double,
        viewId: String? = null,
        editable: Boolean = false,
    ): TextNode {
        val area = (1080.0 * 2400.0 * areaRatio).toInt()
        val side = kotlin.math.sqrt(area.toDouble()).toInt()
        return TextNode(
            text = text,
            bounds = Bounds(100, 200, 100 + side, 200 + side),
            viewId = viewId,
            editable = editable,
        )
    }

    @Test
    fun `SDK 控件 id 优先命中`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(
            snapshot(node("跳过", 0.02, viewId = "com.x:id/tt_splash_skip_btn")),
        )
        assertTrue(result.shouldClick)
        assertTrue(result.reason.contains("SDK"))
    }

    @Test
    fun `启发式跳过 小按钮命中`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(snapshot(node("跳过", 0.02)))
        assertTrue(result.shouldClick)
    }

    @Test
    fun `带倒计时的跳过文案也能命中`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(snapshot(node("3 跳过", 0.02)))
        assertTrue(result.shouldClick)
    }

    /** 真机上跳过按钮的文案形态很杂, 这些都要能命中 */
    @Test
    fun `各种跳过按钮文案形态`() {
        val engine = SplashSkipEngine(rules)
        val cases = listOf(
            "跳过",
            "跳过广告",
            "跳过 3",
            "3s跳过",
            "3 秒 跳过",
            "5秒 | 跳过",
            "跳过 | 5",
            "跳过广告 5s",
            "Skip",
            "Skip 5",
        )
        cases.forEach { text ->
            val result = engine.evaluate(snapshot(node(text, 0.02)))
            assertTrue("「$text」应被识别为跳过按钮", result.shouldClick)
        }
    }

    /** 正文里出现"跳过"不能误判 */
    @Test
    fun `正文含跳过二字不命中`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(snapshot(node("这段剧情可以跳过", 0.02)))
        assertFalse(result.shouldClick)
    }

    @Test
    fun `全屏诱导层被拒绝`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(snapshot(node("跳过", 0.85)))
        assertFalse("大容器不应被点击", result.shouldClick)
        assertTrue(result.rejected!!.contains("诱导层"))
    }

    @Test
    fun `输入框被拒绝`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(snapshot(node("跳过", 0.02, editable = true)))
        assertFalse(result.shouldClick)
    }

    @Test
    fun `长文本被拒绝`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(snapshot(node("点击跳过这段内容继续阅读全文", 0.02)))
        assertFalse(result.shouldClick)
    }

    @Test
    fun `没有跳过控件时不动作`() {
        val engine = SplashSkipEngine(rules)
        val result = engine.evaluate(snapshot(node("首页", 0.02), node("推荐", 0.02)))
        assertFalse(result.shouldClick)
    }
}
