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
    }

    private fun measureScreen() {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
        if (bounds != null && bounds.width() > 0) {
            screenW = bounds.width()
            screenH = bounds.height()
        } else {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
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

        val pkg = router.foregroundPackage ?: return
        if (pkg !in prefs.videoPackages) return

        coordinator.onUserTouch(SystemClock.uptimeMillis())
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

        // 事件风暴治理: 视频页的进度条、弹幕、播放计时会以极高频率发出
        // TYPE_WINDOW_CONTENT_CHANGED, 且 contentChangeTypes 里没有文本/子树变化。
        // 这类事件不可能改变"是不是广告"的判断, 直接丢弃, 避免为了无用事件去遍历节点树。
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !hasMeaningfulChange(e)) {
            return
        }

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

    private var lastScanLogAt: Long = 0L

    private fun scanPopupOnce() {
        val now = SystemClock.uptimeMillis()
        fun skip(reason: String) {
            if (now - lastScanLogAt > 10_000) {
                lastScanLogAt = now
                EngineLog.d("POPUP scan skip: $reason")
            }
        }

        if (!prefs.popupEnabled) return skip("popupEnabled=false")
        if (prefs.paused) return skip("paused")
        if (!isInteractiveNow()) return skip("not interactive")

        val root = acquireRoot() ?: return skip("no root")

        // 包名从节点树自己读, 不用 router.foregroundPackage。
        // 后者只由无障碍事件写入, 而静态弹窗不产生任何事件 —— 服务重启后它一直是 null,
        // 扫描器会把自己挡在 "no foreground package" 这一步。实测踩过。
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: router.foregroundPackage
            ?: return skip("no package")

        if (pkg in prefs.whitelist) return skip("whitelisted: $pkg")
        // 视频托管应用由状态机处理, 这里不重复介入
        if (prefs.videoEnabled && pkg in prefs.videoPackages) return skip("video app: $pkg")

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
        val root = acquireRoot()
        if (root == null) {
            logNoRoot()
            return
        }
        val snapshot = buildSnapshot(root, pkg, className, now)

        // L2 兜底触发条件: 节点树里的有效文本太少, 说明内容很可能画在 SurfaceView / Canvas 里。
        // 真机实测红果广告页只有 2 条文本(「返回」「上滑继续观看短剧」), 而广告主体完全不可见。
        val textCount = snapshot.nodes.count { it.text.isNotBlank() }
        if (prefs.ocrEnabled && ocr.isAvailable && textCount < OCR_TRIGGER_TEXT_COUNT) {
            val fingerprint = snapshot.fingerprint()
            // 同一页面不反复 OCR, 避免白白消耗电量; 但隔一段时间仍要重试, 因为页面可能是渐进的
            if (fingerprint != lastOcrFingerprint || now - lastOcrAt > OCR_REPEAT_MS) {
                lastOcrFingerprint = fingerprint
                lastOcrAt = now
                ocr.captureAsync(now) { ocrNodes ->
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
                return
            }
        }

        coordinator.handleVideo(snapshot, ServiceLocator.appLabel(pkg), now)
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
        val root = rootInActiveWindow ?: return
        val now = SystemClock.uptimeMillis()
        val snapshot = buildSnapshot(root, pkg, null, now)
        coordinator.handleVideo(snapshot, ServiceLocator.appLabel(pkg), now)
    }

    override fun onInterrupt() {
        FlitrRuntime.setPill(PillState(visible = false))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPopupScanner()
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

        /** 同一页面重复 OCR 的最小间隔 */
        const val OCR_REPEAT_MS = 3000L

        /** 窗口变化后补查插屏广告的延迟 */
        const val POPUP_RECHECK_MS = 600L

        /** 插屏定时扫描间隔: 覆盖不产生无障碍事件的静态弹窗 */
        const val POPUP_SCAN_INTERVAL_MS = 2500L
    }
}
