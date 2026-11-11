package com.aios.flitr.engine

import com.aios.flitr.data.logs.ActionLog
import com.aios.flitr.data.logs.LogKind
import com.aios.flitr.data.logs.LogRepository
import com.aios.flitr.data.prefs.FlitrPrefs
import com.aios.flitr.di.ServiceLocator
import com.aios.flitr.engine.gesture.HumanGestureDispatcher
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.splash.SplashSkipEngine
import com.aios.flitr.engine.video.ActionType
import com.aios.flitr.engine.video.AdAssessment
import com.aios.flitr.engine.video.AutomationCommand
import com.aios.flitr.engine.video.CompiledVideoRule
import com.aios.flitr.engine.video.VideoAdStateMachine
import com.aios.flitr.engine.video.VideoHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 自动化协调器: 把「判定」与「执行」缝在一起。
 *
 * 分层意图很明确 ——
 * - 状态机只产出 [AutomationCommand], 完全不知道 Android 的存在;
 * - 协调器负责把命令翻译成手势、写日志、更新胶囊;
 * - 无障碍服务只负责收事件、取快照。
 * 这样状态机可以脱离设备做离线回放测试。
 */
class AutomationCoordinator(
    private val gesture: HumanGestureDispatcher,
    private val splashEngine: SplashSkipEngine,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
    private val prefsProvider: () -> FlitrPrefs,
    private val ruleProvider: (String) -> CompiledVideoRule?,
    private val onPill: (PillState) -> Unit,
    private val requestRecheck: (delayMs: Long) -> Unit,
) {

    private val machines = HashMap<String, VideoAdStateMachine>()

    /** 开屏通道: 命中即点, 命中后关闭时间窗。 */
    fun handleSplash(snapshot: ScreenSnapshot, elapsedMs: Long, appName: String): Boolean {
        val verdict = splashEngine.evaluate(snapshot)
        val target = verdict.target
        if (target == null) {
            verdict.rejected?.let { reason ->
                EngineLog.d("SPLASH rejected: $reason")
                // 只有明确拒绝时才记录, 避免日志被"未命中"淹没
                record(
                    snapshot = snapshot,
                    appName = appName,
                    kind = LogKind.Error,
                    summary = "开屏候选被拒: $reason",
                    detail = "占屏校验 / 白名单保护生效",
                    elapsedMs = elapsedMs,
                )
            }
            return false
        }

        EngineLog.d(
            "SPLASH hit '${target.normalized}' ratio=${"%.1f".format(snapshot.areaRatio(target.bounds) * 100)}% " +
                "elapsed=${elapsedMs}ms reason=${verdict.reason}"
        )
        val tap = gesture.buildMicroTap(target.bounds.centerX, target.bounds.centerY, MICRO_TAP_RADIUS_PX)
        val ok = gesture.performTap(tap)
        record(
            snapshot = snapshot,
            appName = appName,
            kind = LogKind.SplashSkip,
            summary = verdict.reason,
            detail = "命中节点「${target.normalized}」· 坐标微偏点击",
            elapsedMs = elapsedMs,
        )
        return ok
    }

    /** 视频托管通道: 状态机驱动。 */
    fun handleVideo(snapshot: ScreenSnapshot, appName: String, now: Long) {
        val rule = ruleProvider(snapshot.packageName) ?: run {
            EngineLog.d("VIDEO no-rule pkg=${snapshot.packageName} (规则未加载或该包未启用)")
            onPill(PillState(visible = true, mode = PillMode.Guarding, title = "未配置规则", subtitle = snapshot.packageName))
            return
        }

        val machine = machines.getOrPut(snapshot.packageName) { VideoAdStateMachine(rule) }
        val evaluation = machine.onSnapshot(snapshot, now)
        val assessment = machine.lastAssessment

        // 诊断: 页面结构变化时把节点摘要写进 logcat, 供事后校准规则
        EngineLog.snapshotChanged(
            fingerprint = snapshot.fingerprint(),
            now = now,
            summary = summarize(snapshot, assessment),
        )
        // 同时把完整节点树落盘, 供无法用 uiautomator 抓取的广告页取证
        SnapshotDumper.dumpIfChanged(ServiceLocator.context(), snapshot, assessment)
        if (assessment?.isAd == true) {
            EngineLog.d(
                "AD conf=${"%.2f".format(assessment.confidence)} countdown=${assessment.countdownSeconds} " +
                    "completed=${assessment.isCompleted} drama=${assessment.isDramaProtected} " +
                    "panel=${assessment.panelOpen} hit='${assessment.adHit?.normalized ?: "-"}' " +
                    "close='${assessment.closeNode?.normalized ?: "-"}' " +
                    "keep='${assessment.keepWatchingNode?.normalized ?: "-"}'"
            )
        }

        FlitrRuntime.setEngineState(evaluation.state)
        updatePill(evaluation.state, evaluation.note)

        for (command in evaluation.commands) {
            when (command) {
                is AutomationCommand.ClickNode -> {
                    EngineLog.d("ACT click '${command.target.normalized}' @(${command.target.bounds.centerX},${command.target.bounds.centerY}) reason=${command.reason}")
                    val tap = gesture.buildMicroTap(
                        command.target.bounds.centerX,
                        command.target.bounds.centerY,
                        MICRO_TAP_RADIUS_PX,
                    )
                    gesture.performTap(tap)
                    record(
                        snapshot = snapshot,
                        appName = appName,
                        kind = kindForClick(command.reason),
                        summary = command.reason,
                        detail = "目标「${command.target.normalized}」",
                    )
                }

                is AutomationCommand.SwipeUp -> {
                    val spec = gesture.buildSwipeUp(snapshot.screenWidth, snapshot.screenHeight, command.boost)
                    EngineLog.d(
                        "ACT swipe (${spec.startX.toInt()},${spec.startY.toInt()})->(${spec.endX.toInt()},${spec.endY.toInt()}) " +
                            "dur=${spec.durationMs}ms boost=${"%.2f".format(command.boost)} reason=${command.reason}"
                    )
                    gesture.performSwipe(spec)
                    record(
                        snapshot = snapshot,
                        appName = appName,
                        kind = kindForSwipe(evaluation.state),
                        summary = command.reason,
                        detail = "二阶贝塞尔 · 耗时 ${spec.durationMs}ms" +
                            if (command.boost > 1f) " · 补偿 x${"%.2f".format(command.boost)}" else "",
                    )
                }

                is AutomationCommand.ScheduleRecheck -> requestRecheck(command.delayMs)
            }
        }
    }

    /** 节点摘要: 只取可见文本的前若干个, 足够判断页面性质。 */
    private fun summarize(snapshot: ScreenSnapshot, assessment: AdAssessment?): String {
        val texts = snapshot.nodes.asSequence()
            .map { it.normalized }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(18)
            .joinToString(" / ")
        val flag = when {
            assessment == null -> "?"
            assessment.isAd -> "AD"
            assessment.isDramaProtected -> "DRAMA"
            else -> "NORMAL"
        }
        return "[$flag] ${snapshot.nodes.size}n $texts"
    }

    /** 用户手指触屏: 所有托管中的状态机立即避让。 */
    fun onUserTouch(now: Long) {
        machines.values.forEach { it.onUserTouch(now) }
        // 刻意不弹胶囊: 用户自己动手时给一个"已避让"的提示纯属噪音,
        // 用户并不需要被告知"我检测到你在摸屏幕"。
        onPill(PillState(visible = false))
    }

    fun resetAll() {
        machines.values.forEach { it.reset() }
        machines.clear()
    }

    private fun updatePill(state: VideoHostState, note: String?) {
        val pill = when (state) {
            is VideoHostState.WaitingCountdown -> PillState(
                visible = true,
                mode = PillMode.Waiting,
                title = "等待播完",
                subtitle = note ?: "倒计时中",
                remainingSeconds = state.remainingSeconds,
            )

            // 正片页 / 普通页面不显示胶囊。
            // 悬浮窗本身就是一种打扰, 只在引擎真的在做事情时才出现。
            is VideoHostState.WatchingNormalVideo -> PillState(visible = false)

            is VideoHostState.DebouncingAd -> PillState(
                visible = true,
                mode = PillMode.Guarding,
                title = "疑似广告",
                subtitle = note ?: "稳定帧确认中",
            )

            is VideoHostState.ExecutingAction -> PillState(
                visible = true,
                mode = if (state.actionType == ActionType.SwipeUp) PillMode.Guarding else PillMode.Waiting,
                title = if (state.actionType == ActionType.SwipeUp) "自动划走" else "自动关闭",
                subtitle = note ?: "",
            )

            is VideoHostState.UserTouchCooldown -> PillState(visible = false)

            is VideoHostState.NeedsManual -> PillState(
                visible = true,
                mode = PillMode.Paused,
                title = "已停止介入",
                subtitle = "连续滑动无效, 等待页面变化",
            )

            VideoHostState.Idle -> PillState(visible = false)
        }
        onPill(pill)
    }

    private fun kindForSwipe(state: VideoHostState): LogKind = when (state) {
        is VideoHostState.ExecutingAction -> LogKind.VideoNoCountdownSwipe
        else -> LogKind.VideoCountdownSwipe
    }

    private fun kindForClick(reason: String): LogKind = when {
        reason.contains("继续观看") -> LogKind.ContinueWatching
        reason.contains("关闭") -> LogKind.VideoClose
        else -> LogKind.VideoClose
    }

    private fun record(
        snapshot: ScreenSnapshot,
        appName: String,
        kind: LogKind,
        summary: String,
        detail: String = "",
        elapsedMs: Long = 0L,
    ) {
        val entry = ActionLog(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            packageName = snapshot.packageName,
            appName = appName,
            kind = kind.name,
            summary = summary,
            detail = detail,
            elapsedMs = elapsedMs,
        )
        scope.launch { logs.record(entry) }
    }

    companion object {
        /** 节点中心附近的可点击半径, 避免每次都打在几何中心 */
        const val MICRO_TAP_RADIUS_PX = 6
    }
}
