package com.aios.flitr.engine.video

import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.model.TextNode

/** 状态机可能发出的动作类型。 */
enum class ActionType { SwipeUp, ClickClose, ClickContinueWatching, ClickReward, None }

/**
 * 视频托管状态。这是 Flitr 与「规则匹配式」工具最本质的区别:
 * 我们维护的是**跨时间**的页面状态, 而不是单帧匹配结果。
 */
sealed interface VideoHostState {
    /** 非目标 App、已暂停, 或页面不可交互 */
    data object Idle : VideoHostState

    /** 正常剧集播放中, 静默 */
    data object WatchingNormalVideo : VideoHostState

    /** 疑似广告, 等待稳定帧二次确认 */
    data class DebouncingAd(val firstSeenAt: Long) : VideoHostState

    /** 有倒计时广告, 正在读秒 */
    data class WaitingCountdown(
        val initialSeconds: Int,
        val remainingSeconds: Int,
        val deadlineTimestamp: Long,
        val isModalOverlayAd: Boolean,
    ) : VideoHostState

    data class ExecutingAction(val actionType: ActionType, val at: Long) : VideoHostState

    /** 用户正在手动操作, 完全避让 */
    data class UserTouchCooldown(val resumeAt: Long) : VideoHostState

    /** 连续动作无效, 停止介入等待人工 */
    data class NeedsManual(val since: Long, val fingerprint: String) : VideoHostState
}

/** 状态机下发给执行层的指令。状态机本身不碰 Android API。 */
sealed interface AutomationCommand {
    data class ClickNode(val target: TextNode, val reason: String) : AutomationCommand

    /** [boost] 用于滑动失败后的补偿: 拉大起止跨度 */
    data class SwipeUp(val reason: String, val boost: Float = 1f) : AutomationCommand

    /** 要求上层在指定延迟后重新投喂一次快照 */
    data class ScheduleRecheck(val delayMs: Long) : AutomationCommand
}

data class Evaluation(
    val state: VideoHostState,
    val commands: List<AutomationCommand> = emptyList(),
    val note: String? = null,
)

/**
 * 视频流广告智能状态机 —— Flitr 的核心大脑。
 *
 * 设计要点:
 * 1. 所有时间判断都基于传入的 [now], 便于离线回放与单元测试;
 * 2. 单帧判定委托给 [AdClassifier], 本类只负责时间维度;
 * 3. 任何不确定都退化为「不动」, 绝不为了多跳一个广告而冒险划走正片。
 */
class VideoAdStateMachine(private val rule: CompiledVideoRule) {

    var state: VideoHostState = VideoHostState.Idle
        private set

    /** 最近一次单帧判定结果, 供上层做胶囊展示与诊断日志。 */
    var lastAssessment: AdAssessment? = null
        private set

    private var lastActionAt: Long? = null
    private var pendingSwipeFingerprint: String? = null
    private var pendingSwipeAt: Long = 0L
    private var swipeAttempts: Int = 0
    private var lastNote: String? = null

    /** 用户手指触屏: 立刻中止后续自动动作并进入避让冷却。 */
    fun onUserTouch(now: Long) {
        state = VideoHostState.UserTouchCooldown(now + USER_TOUCH_COOLDOWN_MS)
        pendingSwipeFingerprint = null
        swipeAttempts = 0
    }

    fun reset() {
        state = VideoHostState.Idle
        lastActionAt = null
        pendingSwipeFingerprint = null
        pendingSwipeAt = 0L
        swipeAttempts = 0
        lastNote = null
    }

    fun onSnapshot(snapshot: ScreenSnapshot, now: Long = snapshot.timestampMs): Evaluation {
        // --- 1. 触摸避让优先于一切 ---
        val current = state
        if (current is VideoHostState.UserTouchCooldown) {
            if (now < current.resumeAt) {
                return Evaluation(current, emptyList(), "用户操作中, 避让至 ${current.resumeAt}")
            }
            state = VideoHostState.WatchingNormalVideo
        }

        // --- 2. 等待人工介入: 只有页面真的变了才恢复 ---
        if (current is VideoHostState.NeedsManual) {
            if (snapshot.fingerprint() == current.fingerprint) {
                return Evaluation(current, emptyList(), "连续滑动无效, 已停止介入")
            }
            state = VideoHostState.WatchingNormalVideo
        }

        if (!snapshot.interactive) {
            state = VideoHostState.Idle
            return Evaluation(state, emptyList(), "页面不可交互, 挂起")
        }

        // --- 3. 复核上一次滑动是否真的生效 ---
        // 非 null 表示本次事件已被"滑动复核"消费掉, 不再继续走广告判定,
        // 否则标记人工介入后会被后续分支立刻覆盖回 DebouncingAd, 变成无限重试。
        verifyPendingSwipe(snapshot, now)?.let { return it }

        val assessment = AdClassifier.assess(snapshot, rule)
        lastAssessment = assessment

        // --- 4. 用户正在选集/评论: 绝不介入 ---
        if (assessment.panelOpen) {
            state = VideoHostState.WatchingNormalVideo
            return Evaluation(state, emptyList(), "用户正在操作面板, 静默")
        }

        // --- 5. 非广告 ---
        if (!assessment.isAd) {
            state = VideoHostState.WatchingNormalVideo
            return Evaluation(state, emptyList(), null)
        }

        // --- 6. 广告分支 ---
        return when (val cur = state) {
            is VideoHostState.DebouncingAd -> {
                val elapsed = now - cur.firstSeenAt
                if (elapsed < rule.debounceMs) {
                    return Evaluation(
                        cur,
                        listOf(AutomationCommand.ScheduleRecheck(rule.debounceMs - elapsed)),
                        "疑似广告, 等待稳定帧 (${rule.debounceMs - elapsed}ms)",
                    )
                }
                confirmAd(snapshot, assessment, now)
            }

            is VideoHostState.WaitingCountdown -> handleCountdown(snapshot, assessment, cur, now)

            else -> {
                // 首次看到广告: 不立刻动作, 先观察
                val next = VideoHostState.DebouncingAd(now)
                state = next
                Evaluation(
                    next,
                    listOf(AutomationCommand.ScheduleRecheck(rule.debounceMs)),
                    "疑似广告, 观察 ${rule.debounceMs}ms 稳定帧",
                )
            }
        }
    }

    /** 稳定帧已过, 做真正的分支决策。 */
    private fun confirmAd(
        snapshot: ScreenSnapshot,
        assessment: AdAssessment,
        now: Long,
    ): Evaluation {
        // 中途挽留弹窗优先处理
        assessment.keepWatchingNode?.let { node ->
            state = VideoHostState.ExecutingAction(ActionType.ClickContinueWatching, now)
            return Evaluation(
                state,
                listOf(AutomationCommand.ClickNode(node, "自动点击「继续观看」")),
                "命中中途挽留弹窗",
            )
        }

        val seconds = assessment.countdownSeconds
        if (seconds != null) {
            val deadline = now + seconds * 1000L + COUNTDOWN_GRACE_MS
            val next = VideoHostState.WaitingCountdown(
                initialSeconds = seconds,
                remainingSeconds = seconds,
                deadlineTimestamp = deadline,
                isModalOverlayAd = assessment.closeNode != null,
            )
            state = next
            return Evaluation(next, listOf(AutomationCommand.ScheduleRecheck(600)), "有倒计时 ${seconds}s, 等待播完")
        }

        if (assessment.isCompleted) {
            return finishAd(snapshot, assessment, now, "倒计时已完成")
        }

        // 无倒计时: 直接划走
        return performSwipe(snapshot, now, "无倒计时广告, 拟人上滑")
    }

    private fun handleCountdown(
        snapshot: ScreenSnapshot,
        assessment: AdAssessment,
        cur: VideoHostState.WaitingCountdown,
        now: Long,
    ): Evaluation {
        assessment.keepWatchingNode?.let { node ->
            state = cur
            return Evaluation(
                cur,
                listOf(AutomationCommand.ClickNode(node, "自动点击「继续观看」")),
                "倒计时中途被挽留弹窗拦截",
            )
        }

        val remaining = assessment.countdownSeconds
            ?: ((cur.deadlineTimestamp - now) / 1000L).toInt().coerceAtLeast(0)

        val finished = assessment.isCompleted || remaining <= 0 || now >= cur.deadlineTimestamp
        if (!finished) {
            state = cur.copy(remainingSeconds = remaining)
            return Evaluation(
                state,
                listOf(AutomationCommand.ScheduleRecheck(600)),
                "等待倒计时 ${remaining}s",
            )
        }
        return finishAd(snapshot, assessment, now, "倒计时结束")
    }

    /** 广告播完后的收尾: 弹窗式点关闭, 信息流式上滑。 */
    private fun finishAd(
        snapshot: ScreenSnapshot,
        assessment: AdAssessment,
        now: Long,
        reason: String,
    ): Evaluation {
        val close = assessment.closeNode
        if (close != null) {
            state = VideoHostState.ExecutingAction(ActionType.ClickClose, now)
            lastActionAt = now
            return Evaluation(
                state,
                listOf(AutomationCommand.ClickNode(close, "$reason → 点击关闭")),
                reason,
            )
        }
        return performSwipe(snapshot, now, "$reason → 拟人上滑")
    }

    private fun performSwipe(snapshot: ScreenSnapshot, now: Long, reason: String): Evaluation {
        // 首次动作不应该被冷却挡住, 因此 lastActionAt 用可空表示"从未执行过"
        val tooSoon = lastActionAt?.let { now - it < ACTION_COOLDOWN_MS } == true
        if (tooSoon) {
            return Evaluation(state, emptyList(), "动作冷却中")
        }
        lastActionAt = now
        pendingSwipeFingerprint = snapshot.fingerprint()
        pendingSwipeAt = now
        state = VideoHostState.ExecutingAction(ActionType.SwipeUp, now)
        lastNote = reason
        return Evaluation(
            state,
            listOf(AutomationCommand.SwipeUp(reason)),
            reason,
        )
    }

    /**
     * 滑动后复核: 若页面指纹未变, 补偿重试一次; 连续两次无效则标记人工介入。
     * 这是「绝不死循环狂划」的落地实现。
     *
     * @return null 表示无需干预, 继续正常判定; 非 null 表示本次事件已处理完毕
     */
    private fun verifyPendingSwipe(snapshot: ScreenSnapshot, now: Long): Evaluation? {
        val fingerprint = pendingSwipeFingerprint ?: return null
        val since = now - pendingSwipeAt
        if (since < VERIFY_DELAY_MS) return null

        if (snapshot.fingerprint() != fingerprint) {
            pendingSwipeFingerprint = null
            swipeAttempts = 0
            return null
        }

        if (swipeAttempts >= MAX_SWIPE_ATTEMPTS) {
            pendingSwipeFingerprint = null
            val next = VideoHostState.NeedsManual(now, snapshot.fingerprint())
            state = next
            lastNote = "连续 ${MAX_SWIPE_ATTEMPTS + 1} 次滑动未翻页, 停止介入"
            return Evaluation(next, emptyList(), lastNote)
        }

        swipeAttempts++
        lastActionAt = now
        pendingSwipeAt = now
        val boost = 1f + 0.18f * swipeAttempts
        state = VideoHostState.ExecutingAction(ActionType.SwipeUp, now)
        lastNote = "滑动未生效, 补偿重试 ($swipeAttempts/$MAX_SWIPE_ATTEMPTS)"
        return Evaluation(
            state,
            listOf(AutomationCommand.SwipeUp(lastNote ?: "", boost)),
            lastNote,
        )
    }

    companion object {
        /** 用户触屏后的避让冷却 */
        const val USER_TOUCH_COOLDOWN_MS = 3000L

        /** 滑动后多久复核页面是否变化 */
        const val VERIFY_DELAY_MS = 700L

        /** 最多补偿重试次数。1 表示"初次 + 1 次补偿", 与 PRD 的「连续 2 次未翻页则人工介入」一致。 */
        const val MAX_SWIPE_ATTEMPTS = 1

        /** 倒计时归零后的宽限, 等待页面切换 */
        const val COUNTDOWN_GRACE_MS = 500L

        /** 两次自动动作之间的最小间隔 */
        const val ACTION_COOLDOWN_MS = 1000L
    }
}
