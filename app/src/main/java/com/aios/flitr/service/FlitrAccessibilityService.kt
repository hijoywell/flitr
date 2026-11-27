package com.aios.flitr.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aios.flitr.FlitrApp
import com.aios.flitr.R
import com.aios.flitr.data.prefs.FlitrPrefs
import com.aios.flitr.di.ServiceLocator
import com.aios.flitr.engine.AutomationCoordinator
import com.aios.flitr.engine.EngineLog
import com.aios.flitr.engine.EventRouter
import com.aios.flitr.engine.FlitrRuntime
import com.aios.flitr.engine.PillState
import com.aios.flitr.engine.Route
import com.aios.flitr.engine.gesture.HumanGestureDispatcher
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.perception.MlKitOcrRecognizer
import com.aios.flitr.engine.perception.NodeTreeParser
import com.aios.flitr.engine.perception.SilentRoiOcrParser
import com.aios.flitr.engine.rules.SplashRules
import com.aios.flitr.engine.splash.SplashSkipEngine
import com.aios.flitr.engine.video.CompiledVideoRule
import com.aios.flitr.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 无障碍服务宿主。
 *
 * 职责边界刻意收得很窄: **收事件 → 取快照 → 交给协调器**。
 * 所有判定逻辑都在 [AutomationCoordinator] 与状态机里, 这里不做业务判断,
 * 因此引擎可以脱离设备单独测试。
 */
class FlitrAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var parser: NodeTreeParser
    private lateinit var gesture: HumanGestureDispatcher
    private lateinit var coordinator: AutomationCoordinator
    private lateinit var router: EventRouter
    private lateinit var pill: OverlayPillController
    private lateinit var touchWatcher: TouchOutsideWatcher
    private lateinit var splashEngine: SplashSkipEngine
    private lateinit var popupEngine: com.aios.flitr.engine.popup.PopupAdEngine
    private lateinit var ocr: SilentRoiOcrParser

    private var prefs: FlitrPrefs = FlitrPrefs()
    private var splashRules: SplashRules = SplashRules()
    private var screenW: Int = 0
    private var screenH: Int = 0
    private var pendingRecheck: Runnable? = null
    private var ruleCache: Map<String, CompiledVideoRule> = emptyMap()
    private var lastOcrFingerprint: String? = null
    private var lastOcrAt: Long = 0L
    private var ocrRecognizer: MlKitOcrRecognizer? = null

    /** 最近一次收到无障碍事件的时刻, 视频通道心跳靠它判断"页面是不是静了" */
    private var lastEventAt: Long = 0L

    /** 最近一次心跳采样的时刻, 用于按需降频 */
    private var lastHeartbeatAt: Long = 0L

    /** 最近一次用户手指触屏的时刻, 触屏期间不心跳 */
    private var lastUserTouchAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceLocator.init(this)
        measureScreen()
        buildEngines()
        observeSettings()
        FlitrRuntime.setServiceConnected(true)
        updateNotification()
        applyOverlays()
        startPopupScanner()
        startVideoHeartbeat()
    }

    private fun measureScreen() {
        // 短剧 App 均为竖屏使用。currentWindowMetrics.bounds 在部分 ColorOS 机型上
        // 会返回自然方向的宽高(横屏自然方向的机器会把宽高颠倒), 直接用手势会让 x 坐标
        // 划到屏幕外, 表现为「日志里划了、页面不翻」。这里统一按竖屏归一化:
        // 短边 = 屏宽, 长边 = 屏高, 与 takeScreenshot / dispatchGesture 的坐标一致。
        val dm = resources.displayMetrics
        screenW = minOf(dm.widthPixels, dm.heightPixels)
        screenH = maxOf(dm.widthPixels, dm.heightPixels)
        if (screenW <= 0) {
            val wm = getSystemService(WindowManager::class.java)
            val b = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
            if (b != null && b.width() > 0) {
                screenW = minOf(b.width(), b.height())
                screenH = maxOf(b.width(), b.height())
            }
        }
        EngineLog.d("SCREEN ${screenW}x${screenH}")
    }

    private fun buildEngines() {
        parser = NodeTreeParser()
        gesture = HumanGestureDispatcher(this)
        pill = OverlayPillController(this)
        touchWatcher = TouchOutsideWatcher(this)
        val ocrRecognizer = MlKitOcrRecognizer()
        this.ocrRecognizer = ocrRecognizer
        ocr = SilentRoiOcrParser(this, ocrRecognizer)

        splashRules = ServiceLocator.rules.bundle.value.splashRules
        splashEngine = SplashSkipEngine(splashRules)
        popupEngine = com.aios.flitr.engine.popup.PopupAdEngine(ServiceLocator.rules.bundle.value.popupRules)
        router = EventRouter(splashRules)

        coordinator = AutomationCoordinator(
            gesture = gesture,
            splashEngine = splashEngine,
            popupEngine = popupEngine,
            logs = ServiceLocator.logs,
            scope = scope,
            prefsProvider = { prefs },
            ruleProvider = { pkg -> ruleCache[pkg] },
            onPill = { state ->
                // 用户关掉胶囊开关后, 引擎仍可工作, 只是不再有任何悬浮层
                if (prefs.pillEnabled && !prefs.paused) pill.update(state) else pill.hide()
            },
            requestRecheck = { delay -> scheduleRecheck(delay) },
        )
        rebuildRuleCache()
        EngineLog.d(
            "ENGINE ready scenarios=${ServiceLocator.rules.bundle.value.videoScenarios.size} " +
                "cache=${ruleCache.keys} videoPkgs=${prefs.videoPackages} rulesSource=${ServiceLocator.rules.source.value}"
        )
    }

    /** 把用户偏好(延迟区间 / 稳定帧)叠加到规则上, 避免改参数要改规则文件。 */
    private fun rebuildRuleCache() {
        ruleCache = ServiceLocator.rules.bundle.value.videoScenarios
            .filter { it.enabled }
            .associate { raw ->
                val tuned = raw.copy(
                    debounceMs = prefs.debounceMs,
                    reactionDelayRangeMs = listOf(prefs.reactionMinMs, prefs.reactionMaxMs),
                )
                raw.packageName to CompiledVideoRule(tuned)
            }
    }

    private fun observeSettings() {
        ServiceLocator.settings.prefs
            .onEach { latest ->
                val previousDebounce = prefs.debounceMs
                prefs = latest
                FlitrRuntime.setPaused(latest.paused)
                if (latest.debounceMs != previousDebounce) rebuildRuleCache()
                applyOverlays()
                updateNotification()
            }
            .launchIn(scope)

        // 规则文件被导入/重置后重建缓存
        ServiceLocator.rules.bundle
            .onEach {
                splashRules = it.splashRules
                splashEngine = SplashSkipEngine(splashRules)
                popupEngine = com.aios.flitr.engine.popup.PopupAdEngine(it.popupRules)
                rebuildRuleCache()
            }
            .launchIn(scope)
    }

    private fun applyOverlays() {
        val shouldShow = prefs.pillEnabled && !prefs.paused
        if (shouldShow) {
            // 只准备哨兵, 不主动显示胶囊。
            // 胶囊只在"引擎真的在处理广告"时出现, 平时完全不可见 —— 否则用户每切一个应用
            // 都会看到一个悬浮窗, 这本身就是一种打扰。
            if (!touchWatcher.isActive) {
                touchWatcher.start { x, y -> onUserTouch(x, y) }
            }
        } else {
            pill.hide()
            touchWatcher.stop()
        }
    }

    /**
     * 用户手指触屏。
     *
     * 必须区分"在操作视频"和"在操作系统 UI": 下拉通知栏、点状态栏、底部返回手势
     * 都不应该让视频托管进入避让冷却 —— 否则系统弹个通知、用户看一眼就触发一次"已避让"。
     */
    private fun onUserTouch(x: Float, y: Float) {
        val top = statusBarHeightPx()
        val bottom = screenH - navBarHeightPx()
        if (y < top || y > bottom) return
        if (x < 0 || x > screenW) return

        // 自己刚派发的手势也会走同一条输入通路被这里收到。如果不排除,
        // 每划走一条广告就会自我触发 3 秒避让冷却, 下一条广告要多等 3 秒 —— 实测体感就是"识别很慢"。
        val now = SystemClock.uptimeMillis()
        if (gesture.isSelfGesture(now)) return

        val pkg = router.foregroundPackage ?: return
        if (pkg !in prefs.videoPackages) return

        lastUserTouchAt = now
        coordinator.onUserTouch(now)
    }

    private fun statusBarHeightPx(): Int = systemDimen("status_bar_height")

    private fun navBarHeightPx(): Int = systemDimen("navigation_bar_height")

    private fun systemDimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType !in HANDLED_EVENTS) return

        val pkg = e.packageName?.toString() ?: return
        if (router.isNoise(pkg)) return
        if (pkg == ServiceLocator.selfPackage()) return

        val now = SystemClock.uptimeMillis()
        EngineLog.countEvent(now, "${eventTypeName(e.eventType)}/$pkg/cct=${e.contentChangeTypes}")

        // 翻页探针: 区分"翻页"与"播放中抖动"是「只监听翻页」方案成立的前提。
        // 逐条记录事件类型 + contentChangeTypes + 来源控件类名, 用于离线分析。
        if (pkg in prefs.videoPackages) {
            EngineLog.eventProbe(
                type = e.eventType,
                cct = e.contentChangeTypes,
                className = e.className?.toString(),
                sourceHasText = !e.text.isEmpty(),
                now = now,
            )
        }

        // 事件风暴治理: 视频页的进度条、弹幕、播放计时会以极高频率发出
        // TYPE_WINDOW_CONTENT_CHANGED, 且 contentChangeTypes 里没有文本/子树变化。
        // 这类事件不可能改变"是不是广告"的判断, 直接丢弃, 避免为了无用事件去遍历节点树。
        //
        // 注意 lastEventAt 必须放在这之后更新: 它是"引擎真的看见页面在动"的信号,
        // 心跳靠它判断要不要兜底。红果的图集广告页恰好只发这种被丢弃的事件 ——
        // 早先放在过滤之前, 心跳被这个"假活动"永久压制, 一次都没启动过。
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !hasMeaningfulChange(e)) {
            return
        }
        lastEventAt = now

        router.onForegroundChanged(pkg, now)
        FlitrRuntime.setForeground(pkg, ServiceLocator.appLabel(pkg))
        FlitrRuntime.markEvent(System.currentTimeMillis())

        val route = router.route(pkg, now, prefs, e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        logRoute(pkg, route, now)
        when (route) {
            is Route.Splash -> handleSplash(pkg, e.className?.toString(), route.elapsedMs, now)
            Route.Video -> handleVideo(pkg, e.className?.toString(), now)
            Route.Popup -> {
                handlePopup(pkg, e.className?.toString(), now)
                // 弹窗内容常常比窗口事件晚几百毫秒才渲染出来, 补一次检查
                schedulePopupRecheck(POPUP_RECHECK_MS)
            }
            Route.Ignore -> Unit
        }
    }

    /**
     * 插屏广告定时扫描。
     *
     * 为什么必须定时: 静态插屏（一张图片 + 一个 × ）在显示之后**不产生任何无障碍事件**,
     * 纯事件驱动的通道永远等不到它。实测脉脉的京东广告弹窗就是这样 —— 挂在屏幕上几分钟,
     * 引擎一条日志都没有。
     *
     * 代价控制: 间隔 2.5 秒、屏幕熄灭时跳过、白名单与视频托管应用跳过。
     * 相比视频通道的 400ms, 这个开销可以接受。
     */
    private val popupScanTask = object : Runnable {
        override fun run() {
            // runCatching 会把异常静默吞掉, 这里显式打出来, 否则"扫描器没反应"无法排查
            runCatching { scanPopupOnce() }
                .onFailure { EngineLog.w("POPUP scan failed: ${it.javaClass.simpleName} ${it.message}") }
            handler.postDelayed(this, POPUP_SCAN_INTERVAL_MS)
        }
    }

    private fun startPopupScanner() {
        handler.removeCallbacks(popupScanTask)
        handler.postDelayed(popupScanTask, POPUP_SCAN_INTERVAL_MS)
        EngineLog.d("POPUP scanner started, interval=${POPUP_SCAN_INTERVAL_MS}ms")
    }

    private fun stopPopupScanner() {
        handler.removeCallbacks(popupScanTask)
    }

    /**
     * 视频通道心跳。
     *
     * 存在的理由: 红果的直播间广告页把整屏内容画在 TextureView 里, 页面稳定之后
     * **不产生任何无障碍事件** —— 实测停留 20 秒, `EVENT` 与 `ROUTE` 都是零条。
     * 而视频通道是纯事件驱动的, 没有事件就永远不采样, 也就永远发现不了这类广告。
     *
     * 这和插屏通道 [popupScanTask] 是同一个教训: 静态内容必须靠定时器兜底。
     * 区别在于这里**不是无条件轮询** —— 用"距上次事件的时长"当信号:
     * 有事件说明页面在动、引擎看得见, 心跳完全不启动; 正片页因此不受影响。
     */
    private val videoHeartbeatTask = object : Runnable {
        override fun run() {
            runCatching { videoHeartbeatOnce() }
                .onFailure { EngineLog.w("VIDEO heartbeat failed: ${it.javaClass.simpleName} ${it.message}") }
            handler.postDelayed(this, VIDEO_HEARTBEAT_TICK_MS)
        }
    }

    private fun startVideoHeartbeat() {
        handler.removeCallbacks(videoHeartbeatTask)
        val now = SystemClock.uptimeMillis()
        // 初始化成"刚刚有过事件", 避免服务一启动就立刻采样
        lastEventAt = now
        lastHeartbeatAt = 0L
        handler.postDelayed(videoHeartbeatTask, VIDEO_HEARTBEAT_TICK_MS)
    }

    private fun stopVideoHeartbeat() {
        handler.removeCallbacks(videoHeartbeatTask)
    }

    private fun videoHeartbeatOnce() {
        val now = SystemClock.uptimeMillis()

        // 心跳必须可观测: 它是零事件广告页的唯一发现途径, "心跳没跑"等于"广告永不划走"。
        // 诊断行放在所有 return 之前, 每 10 秒最多一条, 把每道门槛的值直接打出来。
        if (now - lastHeartbeatDiagAt > 10_000L) {
            lastHeartbeatDiagAt = now
            EngineLog.d(
                "HB video=${prefs.videoEnabled} paused=${prefs.paused} " +
                    "idle=${now - lastEventAt} guard=${now - lastUserTouchAt} " +
                    "hbAt=${now - lastHeartbeatAt} fg=${router.foregroundPackage} rootCache=$cachedRootPkg",
            )
        }

        if (!prefs.videoEnabled || prefs.paused) return
        if (!isInteractiveNow() || !isScreenOnNow()) return

        // 廉价条件全部前置: 心跳 500ms 一跳, 节点树必须只在真正要采样时才拉。
        // 有事件时事件本身就是心跳, 不重复采样
        val idle = now - lastEventAt
        if (idle < VIDEO_HEARTBEAT_IDLE_MS) return

        // 用户正在划屏幕时不介入, 也别白烧 OCR
        if (now - lastUserTouchAt < VIDEO_HEARTBEAT_TOUCH_GUARD_MS) return

        // 长时间无事件说明用户大概率在安静看正片, 降频省电
        val interval = if (idle > VIDEO_HEARTBEAT_DOWNGRADE_MS) {
            VIDEO_HEARTBEAT_SLOW_MS
        } else {
            VIDEO_HEARTBEAT_FAST_MS
        }
        if (now - lastHeartbeatAt < interval) return

        // 先记账再采样。放在拉树之后会导致"非视频包"分支永远走不到记账那一行,
        // 于是每个 500ms tick 都重新拉一次整棵树 —— 实测 995 次/3000 行日志,
        // 纯属空转。记账前置后, 无论本次结论如何都要等满 interval 才允许下一次采样。
        lastHeartbeatAt = now

        // 包名优先从节点树读。
        //
        // router.foregroundPackage 只由无障碍事件写入 —— 服务被系统重绑后它是 null,
        // 而静默页(红果图集广告)又一条事件都不发, 心跳会永久卡在这一行 return。
        // 这正是"广告挂在屏幕上一直不划走"的直接原因, 实测踩过。
        // 读到的包名写回 [cachedRootPkg], 让弹窗扫描器的预筛也能用上。
        val pkg = acquireRoot()?.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?.also { cachedRootPkg = it }
            ?: router.foregroundPackage
            ?: return
        if (pkg in prefs.whitelist) return
        if (pkg !in prefs.videoPackages) return

        // 走和事件完全相同的入口, 包括 OCR 兜底与状态机, 不新增第二条判定路径
        handleVideo(pkg, null, now)
    }

    /** 息屏后没有事件, 心跳必须自己停, 否则会整夜截图 + 推理。 */
    private fun isScreenOnNow(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return true
        return runCatching { pm.isInteractive }.getOrDefault(true)
    }

    private var lastScanLogAt: Long = 0L
    private var scanCostSumMs = 0L
    private var scanCostCount = 0
    private var scanCostWindowStart = 0L

    /**
     * 最近一次从节点树读到的前台包名。
     *
     * router.foregroundPackage 只由事件维护, 静默页(红果广告页)一个事件都不发,
     * 服务重绑后它永远是 null —— 预筛就永远拦不住, 扫描器每 2.5 秒白拉一次树。
     * 这里把每次拉树读到的包名存下来, 下个周期就能走廉价分支跳过。
     */
    private var cachedRootPkg: String? = null

    /** 上次真正解析视频快照的时刻, 见 handleVideo 的节流 */
    private var lastVideoParseAt: Long = 0L

    /** 心跳诊断日志的节流 */
    private var lastHeartbeatDiagAt: Long = 0L

    /** handleVideo 解析诊断日志的节流 */
    private var lastHandleVideoDiagAt: Long = 0L

    /** 无文本页面结构的取证去重, 见 handleVideo 里的 TREE 日志 */
    private var lastEmptyTreeFp: String? = null

    /** 上一次成功的 OCR 结果, 用于结构未变的薄帧复用(见 handleVideo 的 OCR 粘滞) */
    private var lastOcrNodes: List<com.aios.flitr.engine.model.TextNode> = emptyList()
    private var lastOcrStructFp: String? = null
    private var lastOcrResultAt: Long = 0L
    private var lastOcrPkg: String? = null

    private fun scanPopupOnce() {
        val startedMs = SystemClock.uptimeMillis()
        try {
            scanPopupOnceInner(startedMs)
        } finally {
            // 扫描开销必须可见: 常驻定时器一旦变贵, 表现就是"待机也耗电",
            // 而这种问题在事件驱动的日志里完全看不出来。每 60 秒汇总一次。
            scanCostSumMs += SystemClock.uptimeMillis() - startedMs
            scanCostCount++
            if (scanCostWindowStart == 0L) scanCostWindowStart = startedMs
            if (startedMs - scanCostWindowStart >= 60_000L) {
                val avg = scanCostSumMs / scanCostCount.coerceAtLeast(1)
                EngineLog.d("POPUP scan cost: avg=${avg}ms total=${scanCostSumMs}ms over $scanCostCount scans/60s")
                scanCostSumMs = 0L
                scanCostCount = 0
                scanCostWindowStart = startedMs
            }
        }
    }

    private fun scanPopupOnceInner(now: Long) {
        fun skip(reason: String) {
            if (now - lastScanLogAt > 10_000) {
                lastScanLogAt = now
                EngineLog.d("POPUP scan skip: $reason")
            }
        }

        if (!prefs.popupEnabled && !prefs.videoEnabled) return skip("both channels disabled")
        if (prefs.paused) return skip("paused")
        if (!isInteractiveNow()) return skip("not interactive")

        // --- 廉价预筛: 能用缓存定案就绝不拉节点树 ---
        //
        // acquireRoot() 会把整棵无障碍树跨进程复制过来, 实测 48ms/次, 2.5 秒一次
        // 就是约 2% 的常驻 CPU —— 用户感受是"耗电快"。而绝大多数时刻前台 App
        // 既不是弹窗目标也不是视频托管, 拉了也是白拉。
        //
        // 缓存由 TYPE_WINDOW_STATE_CHANGED 维护, 切 App 必发这个事件, 所以缓存
        // 唯一可能空白的场景是"服务刚重绑且用户停在静默页" —— 那正是心跳要兜的场景,
        // 让它走缓存为空的分支拉一次树即可。
        val cached = router.foregroundPackage ?: cachedRootPkg
        if (now - lastScanLogAt > 10_000) {
            lastScanLogAt = now
            EngineLog.d(
                "POPUP prefilter router=${router.foregroundPackage} rootCache=$cachedRootPkg " +
                    "popupPkgs=${prefs.popupPackages.size} videoPkgs=${prefs.videoPackages.size}",
            )
        }
        if (cached != null) {
            if (cached in prefs.whitelist) return skip("whitelisted: $cached")
            if (prefs.videoEnabled && cached in prefs.videoPackages) {
                // 视频包由心跳兜底(它自己做 1.2-3s 的降频), 扫描器不再重复介入
                return skip("video app: $cached")
            }
            if (!prefs.popupEnabled || cached !in prefs.popupPackages) {
                return skip("not in popupPackages: $cached")
            }
        }

        // --- 到这里才拉树: 缓存为空(服务重绑)或缓存指向弹窗目标包 ---
        val root = acquireRoot()
        val pkg = root?.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?.also { cachedRootPkg = it }
            ?: cached
            ?: return skip("no package")

        if (pkg in prefs.whitelist) return skip("whitelisted: $pkg")
        if (pkg in prefs.videoPackages) return skip("video app: $pkg")
        if (!prefs.popupEnabled) return skip("popupEnabled=false")
        if (pkg !in prefs.popupPackages) return skip("not in popupPackages: $pkg")
        if (root == null) return skip("no root")

        val snapshot = buildSnapshot(root, pkg, null, now)
        if (now - lastScanLogAt > 10_000) {
            lastScanLogAt = now
            EngineLog.d("POPUP scan pkg=$pkg nodes=${snapshot.nodes.size}")
        }
        coordinator.handlePopup(snapshot, ServiceLocator.appLabel(pkg), now)
    }

    private fun handlePopup(pkg: String, className: String?, now: Long) {
        val root = acquireRoot()
        if (root == null) {
            logNoRoot()
            return
        }
        val snapshot = buildSnapshot(root, pkg, className, now)
        coordinator.handlePopup(snapshot, ServiceLocator.appLabel(pkg), now)
    }

    private var pendingPopupRecheck: Runnable? = null

    private fun schedulePopupRecheck(delayMs: Long) {
        pendingPopupRecheck?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { recheckPopup() }
        pendingPopupRecheck = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun recheckPopup() {
        val pkg = router.foregroundPackage ?: return
        if (!prefs.popupEnabled) return
        val root = acquireRoot() ?: return
        val now = SystemClock.uptimeMillis()
        val snapshot = buildSnapshot(root, pkg, null, now)
        coordinator.handlePopup(snapshot, ServiceLocator.appLabel(pkg), now)
    }

    private var lastRouteLogAt = 0L
    private var lastRouteKey: String? = null

    /** 路由决策日志: 只在决策或包名变化时打印, 避免自身刷屏。 */
    private fun logRoute(pkg: String, route: Route, now: Long) {
        // 用显式字符串而不是 route::class.simpleName —— R8 会把类名混淆成 kc1/ic1 这种,
        // 日志直接失去可读性, 排查时反而更费劲。
        val routeName = when (route) {
            is Route.Splash -> "Splash"
            Route.Video -> "Video"
            Route.Popup -> "Popup"
            Route.Ignore -> "Ignore"
        }
        val key = "$pkg|$routeName"
        if (key == lastRouteKey && now - lastRouteLogAt < 3000) return
        lastRouteKey = key
        lastRouteLogAt = now
        val extra = buildString {
            append("pkg=$pkg route=$routeName")
            append(" videoEnabled=${prefs.videoEnabled}")
            append(" paused=${prefs.paused}")
            append(" inVideoPkgs=${pkg in prefs.videoPackages}")
            append(" inWhitelist=${pkg in prefs.whitelist}")
            if (route is Route.Splash) append(" elapsed=${route.elapsedMs}ms")
        }
        EngineLog.d("ROUTE $extra")
    }

    /**
     * 判断一次内容变化是否值得重新解析。
     *
     * `contentChangeTypes == 0` 表示应用没有说明变化类型(很多国产 App 如此),
     * 这种情况不能一概丢弃, 否则会漏掉广告出现。只有明确声明了"非文本类变化"时才跳过。
     */
    private fun hasMeaningfulChange(e: AccessibilityEvent): Boolean {
        val types = e.contentChangeTypes
        if (types == 0) return true
        val meaningful = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
        return (types and meaningful) != 0
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WIN_STATE"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
        else -> type.toString()
    }

    private fun handleSplash(pkg: String, className: String?, elapsedMs: Long, now: Long) {
        val root = acquireRoot() ?: run {
            logNoRoot()
            return
        }
        val snapshot = buildSnapshot(root, pkg, className, now)
        val clicked = coordinator.handleSplash(snapshot, elapsedMs, ServiceLocator.appLabel(pkg))
        if (clicked) {
            // 命中即提前关闭时间窗, 之后不再扫描, 功耗归零
            router.closeSplashWindow()
        }
    }

    private fun handleVideo(pkg: String, className: String?, now: Long) {
        // 全局节流: 事件通道和心跳会在同一时刻各调一次, 而单次节点树遍历要 40-50ms。
        // 实测正片页因此达到 18% 单核占用。这里按"距上次真正解析的时间"兜底,
        // 保证任一时刻只有一条解析在跑。
        //
        // 300ms 不牺牲响应性: 广告出现的判定有 debounce (观察期 450ms) 兜着,
        // 而且倒计时本身 1 秒一跳, 300ms 的采样粒度远低于需求。
        val throttled = now - lastVideoParseAt < VIDEO_PARSE_MIN_GAP_MS
        lastVideoParseAt = now
        if (throttled) {
            if (now - lastHandleVideoDiagAt > 10_000L) {
                lastHandleVideoDiagAt = now
                EngineLog.d("VIDEO handleVideo throttled (gap ${now - lastVideoParseAt + VIDEO_PARSE_MIN_GAP_MS}ms)")
            }
            return
        }

        val root = acquireRoot()
        if (root == null) {
            logNoRoot()
            return
        }
        val snapshot = buildSnapshot(root, pkg, className, now)
        val textCount = snapshot.nodes.count { it.text.isNotBlank() }

        // 取证: 节点树里一条文本都读不到时, 把节点结构(控件 id + 中心点 + 可点击)打出来。
        // 红果的广告把字全画在 SurfaceView 里, 只剩 viewId 可用 —— 这是判断
        // 「能不能改用控件 id 识别、彻底绕开 OCR」的唯一依据。同一结构只打一次。
        if (textCount == 0 && snapshot.nodes.isNotEmpty()) {
            val fp = snapshot.fingerprint()
            if (fp != lastEmptyTreeFp) {
                lastEmptyTreeFp = fp
                EngineLog.d(
                    "TREE ${snapshot.nodes.size}n :: " + snapshot.nodes.take(24).joinToString(" | ") { n ->
                        "${n.viewId ?: "-"}@${n.bounds.centerX},${n.bounds.centerY}${if (n.clickable) "*" else ""}"
                    },
                )
            }
        }

        // 解析摘要: 广告页排查的最后一公里。节点树到底给了什么、走没走 OCR、
        // 为什么没喂状态机 —— 这一条日志全部回答。每 10 秒最多一条。
        if (now - lastHandleVideoDiagAt > 10_000L) {
            lastHandleVideoDiagAt = now
            EngineLog.d(
                "VIDEO parse nodes=${snapshot.nodes.size} text=$textCount " +
                    "texts=${snapshot.nodes.map { it.text }.filter { it.isNotBlank() }.take(6)}",
            )
        }

        // OCR 结果粘滞。
        //
        // 红果这类自绘广告页的节点树在"19 个节点 0 文本"与"OCR 合并后的厚帧"之间反复横跳。
        // 中间那些薄帧喂给状态机会被判定成"广告信号暂缺", 于是确认被推迟到下一次 OCR
        // 结果(1.1~2.2s 之后) —— 这正是"广告出来了要愣两秒"的剩余来源。
        // 只要节点结构完全没变, 就说明还是同一页面, 直接把上一次的 OCR 文本粘回来。
        val stickyNodes = if (textCount == 0) {
            val fresh = now - lastOcrResultAt <= OCR_STICKY_MS &&
                pkg == lastOcrPkg &&
                lastOcrStructFp != null &&
                snapshot.structureFingerprint() == lastOcrStructFp
            if (fresh) lastOcrNodes else emptyList()
        } else {
            emptyList()
        }
        val effective = if (stickyNodes.isEmpty()) {
            snapshot
        } else {
            snapshot.copy(
                nodes = snapshot.nodes + stickyNodes,
                source = com.aios.flitr.engine.model.SnapshotSource.NodeTreeWithOcr,
            )
        }
        val effectiveTextCount = effective.nodes.count { it.text.isNotBlank() }

        // 节点树已经读到文本时, 先把这一帧同步喂给状态机。
        //
        // 这一步是「反应速度」的关键: 红果广告页节点树里本来就带「返回」「上滑继续看短剧」,
        // 判定证据已经齐了。以前一律先走 OCR 再判定, 白等一次 1.1~1.5s 的截图+推理往返,
        // 体感就是"广告出来了要愣一会儿才划走"。
        val fedSync = if (effectiveTextCount > 0) {
            coordinator.handleVideo(effective, ServiceLocator.appLabel(pkg), now)
            // conf >= 0.5 说明命中的不是"广告"两个字这种弱信号, 而是组合特征,
            // 此时 OCR 不可能再翻案成非广告, 直接交给状态机, 不再烧 OCR。
            coordinator.lastAssessment?.let { it.isAd && it.confidence >= 0.5f } == true
        } else {
            false
        }
        if (fedSync) return

        // L2 兜底触发条件: 节点树里的有效文本太少, 说明内容很可能画在 SurfaceView / Canvas 里。
        // 真机实测红果广告页只有 2 条文本(「返回」「上滑继续观看短剧」), 而广告主体完全不可见。
        if (prefs.ocrEnabled && ocr.isAvailable && effectiveTextCount < OCR_TRIGGER_TEXT_COUNT) {
            val fingerprint = snapshot.fingerprint()
            // 同一页面不反复 OCR, 避免白白消耗电量; 但隔一段时间仍要重试, 因为页面可能是渐进的
            if (fingerprint != lastOcrFingerprint || now - lastOcrAt > OCR_REPEAT_MS) {
                lastOcrFingerprint = fingerprint
                lastOcrAt = now
                // 已经同步喂过(见上), 回调里只需补 OCR 结果, 不要重复喂同一帧
                captureOcrAsync(pkg, snapshot, feedSync = false)
                return
            }
            // 已节流过 OCR: 薄帧不再喂给状态机。
            //
            // 这里非常关键。薄帧(0 条文本)代表"引擎看不见页面", 喂进去会让状态机
            // 判定成"非广告"并消耗 AD_MISS_TOLERANCE 的宽容配额 —— 而同步判定
            // 每 300ms 就能喂一帧, 3 帧配额两秒内耗光, 广告确认被打断, 表现就是
            // "广告又不动了"。实测踩过。只有真的读到文本时才喂, 让 OCR 回调负责补全。
            if (effectiveTextCount == 0) return
        }

        // 文本帧在上面已经喂过, 别重复喂同一帧
        if (effectiveTextCount > 0) return

        coordinator.handleVideo(snapshot, ServiceLocator.appLabel(pkg), now)
    }

    /**
     * 异步补 OCR。
     *
     * [feedSync] 为 true 时先把节点树快照同步喂给状态机 —— 早期这里直接 return,
     * 判定完全押在异步回调上, OCR 一来一回 1.5-2.5s 期间状态机对页面一无所知。
     * 节点树自带「返回」「上滑继续观看短剧」的广告可以立即确认, 不必等 OCR。
     * 但 0 文本的薄帧不能喂(见 handleVideo 里的说明), 那时只等 OCR 结果。
     */
    private fun captureOcrAsync(
        pkg: String,
        snapshot: com.aios.flitr.engine.model.ScreenSnapshot,
        feedSync: Boolean,
    ) {
        val now = SystemClock.uptimeMillis()
        EngineLog.d("OCR trigger pkg=$pkg nodes=${snapshot.nodes.size} textCount=${snapshot.nodes.count { it.text.isNotBlank() }} feedSync=$feedSync")
        if (feedSync) {
            coordinator.handleVideo(snapshot, ServiceLocator.appLabel(pkg), now)
        }
        ocr.captureAsync(now) { ocrNodes ->
            if (ocrNodes.isEmpty()) {
                EngineLog.d("OCR done pkg=$pkg nodes=0 (empty)")
            } else {
                EngineLog.d("OCR done pkg=$pkg nodes=${ocrNodes.size} texts=${ocrNodes.take(6).joinToString(" / ") { it.normalized }}")
                // 记住这次 OCR 结果, 供后续"结构未变的薄帧"复用(见 handleVideo 的 OCR 粘滞)
                lastOcrNodes = ocrNodes
                lastOcrStructFp = snapshot.structureFingerprint()
                lastOcrResultAt = SystemClock.uptimeMillis()
                lastOcrPkg = pkg
            }
            val merged = if (ocrNodes.isEmpty()) {
                snapshot
            } else {
                snapshot.copy(
                    nodes = snapshot.nodes + ocrNodes,
                    source = com.aios.flitr.engine.model.SnapshotSource.NodeTreeWithOcr,
                )
            }
            coordinator.handleVideo(merged, ServiceLocator.appLabel(pkg), SystemClock.uptimeMillis())
        }
    }

    /**
     * 获取当前可解析的根节点。
     *
     * `rootInActiveWindow` 在不少视频类 App 上会返回 null —— 播放页常由 SurfaceView 承载,
     * 窗口不暴露常规节点树, 或者活动窗口不是我们以为的那一个。
     * 因此这里加了退化路径: 从 `windows` 里挑一个 active(或第一个有 root 的)窗口。
     *
     * 这条路径是**实测必需**的: 红果短剧的播放页在 ColorOS + Android 17 上,
     * `rootInActiveWindow` 稳定返回 null, 只有走 windows 才能拿到节点。
     */
    private fun acquireRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        val all = runCatching { windows }.getOrNull() ?: return null
        val ordered = all.sortedByDescending { it.isActive }
        for (w in ordered) {
            val r = runCatching { w.root }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    private var lastNoRootLogAt = 0L

    private fun logNoRoot() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNoRootLogAt < 3000) return
        lastNoRootLogAt = now
        EngineLog.d("VIDEO no-root: rootInActiveWindow=null 且 windows 中也没有可用 root")
    }

    private fun buildSnapshot(
        root: android.view.accessibility.AccessibilityNodeInfo,
        pkg: String,
        className: String?,
        now: Long,
    ): ScreenSnapshot = parser.parse(
        root = root,
        packageName = pkg,
        className = className,
        screenWidth = screenW,
        screenHeight = screenH,
        now = now,
        interactive = isInteractiveNow(),
    )

    /** 锁屏 / 系统权限弹窗等场景一律挂起。 */
    private fun isInteractiveNow(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        return km?.isKeyguardLocked != true
    }

    private fun scheduleRecheck(delayMs: Long) {
        pendingRecheck?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { recheck() }
        pendingRecheck = runnable
        handler.postDelayed(runnable, delayMs.coerceIn(80L, 3000L))
    }

    private fun recheck() {
        val pkg = router.foregroundPackage ?: return
        if (!prefs.videoEnabled || pkg !in prefs.videoPackages) return
        // 走和事件完全相同的入口(含 acquireRoot / OCR 兜底 / 薄帧保护)。
        // 早期这里直接用 rootInActiveWindow, 而红果播放页它稳定返回 null ——
        // 于是"观察期结束后的复核"整条路径静默失效, 广告只能等心跳下一次采样,
        // 平白多出 1~2 秒。同时也不该绕过薄帧保护, 否则会把 0 文本帧喂进状态机。
        handleVideo(pkg, null, SystemClock.uptimeMillis())
    }

    override fun onInterrupt() {
        FlitrRuntime.setPill(PillState(visible = false))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPopupScanner()
        stopVideoHeartbeat()
        pendingRecheck?.let { handler.removeCallbacks(it) }
        runCatching { touchWatcher.stop() }
        runCatching { pill.hide() }
        coordinator.resetAll()
        runCatching { ocrRecognizer?.close() }
        FlitrRuntime.onServiceDestroyed()
        scope.cancel()
    }

    private fun updateNotification() {
        if (!prefs.persistentNotification) {
            return
        }
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(this, FlitrApp.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_stat_flitr)
            .setContentTitle("Flitr 守护中")
            .setContentText(if (prefs.paused) "已暂停托管" else "开屏秒跳 · 短剧智划")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        // 这里刻意用 notify() 而不是 startForeground()。
        //
        // 曾经改成 startForeground() 想提高进程优先级, 结果在 ColorOS 上
        // 无障碍服务直接连不上, 系统设置里的条目也不再出现 ——
        // AccessibilityService 不能像普通 Service 那样声明 foregroundServiceType,
        // 强行 startForeground 会让系统判定服务启动失败。
        // 代价是保活能力弱一些, 但这远好过"服务根本起不来"。
        runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.notify(FlitrApp.NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        val HANDLED_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
        )

        /** 有效文本少于这个数量时, 认为内容可能在 SurfaceView 里, 触发 OCR 兜底 */
        const val OCR_TRIGGER_TEXT_COUNT = 4

        /** 两次视频快照解析之间的最小间隔, 见 handleVideo */
        const val VIDEO_PARSE_MIN_GAP_MS = 300L

        /** 同一页面重复 OCR 的最小间隔。
         *
         * 1500ms: 抖动页面(红果图集广告)的厚帧窗口约 2.5s 一次, 3s 的重试间隔经常
         * 刚好错过, 用户感受是"广告要挂 5-7 秒才走"。1.5s 让薄帧后的第一次厚帧
         * 就有机会补上 OCR, 代价是极端情况下多跑一次识别。
         */
        const val OCR_REPEAT_MS = 1500L

        /** OCR 结果的有效期: 结构未变的薄帧在这段时间内直接复用上次识别结果。
         *
         * 1200ms 比一次 OCR 往返(约 1.1~2.2s)略长, 刚好覆盖"薄帧—厚帧"的横跳间隙;
         * 又短到不会把上一个页面的文本带到下一个页面。
         */
        const val OCR_STICKY_MS = 1200L

        /** 窗口变化后补查插屏广告的延迟 */
        const val POPUP_RECHECK_MS = 600L

        /** 插屏定时扫描间隔: 覆盖不产生无障碍事件的静态弹窗 */
        const val POPUP_SCAN_INTERVAL_MS = 2500L

        /** 视频通道心跳的基础节拍 */
        const val VIDEO_HEARTBEAT_TICK_MS = 500L

        /** 距上次无障碍事件超过这个时长才启用心跳（有事件时事件本身就是心跳）。
         *
         * 500ms: 静默广告页(红果图集)唯一的发现途径就是心跳, 门限越低广告被
         * 拾起越快。正片页不受影响 —— 它的事件流持续, idle 永远到不了门限。
         */
        const val VIDEO_HEARTBEAT_IDLE_MS = 500L

        /** 正常心跳间隔 */
        const val VIDEO_HEARTBEAT_FAST_MS = 700L

        /** 超过这个无事件时长后降频, 避免安静看正片时持续烧电 */
        const val VIDEO_HEARTBEAT_DOWNGRADE_MS = 30_000L

        /** 降频后的心跳间隔 */
        const val VIDEO_HEARTBEAT_SLOW_MS = 1800L

        /** 用户触屏后这段时间内不心跳。
         *
         * 1500ms: 用户手动划到一条广告后, 心跳必须很快能接手, 否则体感就是"广告挂在那里不动"。
         * 这个窗口只挡**心跳**, 事件驱动的判定不受影响; 而且本引擎自己派发的手势已被
         * [com.aios.flitr.engine.gesture.HumanGestureDispatcher.isSelfGesture] 排除, 不会自我阻塞。
         */
        const val VIDEO_HEARTBEAT_TOUCH_GUARD_MS = 1500L
    }
}
