package com.aios.flitr.engine.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.math.abs
import kotlin.random.Random

/** 一次滑动的几何与时间参数。抽成数据类便于离线验证与日志回放。 */
data class SwipeSpec(
    val startX: Float,
    val startY: Float,
    val controlX: Float,
    val controlY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long,
) {
    fun toPath(): Path = Path().apply {
        moveTo(startX, startY)
        quadTo(controlX, controlY, endX, endY)
    }
}

/** 一次点击的目标点。 */
data class TapSpec(val x: Float, val y: Float, val durationMs: Long = 60L)

/**
 * 拟人化手势引擎。
 *
 * 目的不是"对抗风控"这种灰色诉求, 而是让自动操作在视觉与触感上接近真实拇指,
 * 减少突兀跳变带来的误触和观感问题。所有随机范围都可在设置页调整。
 */
class HumanGestureDispatcher(
    private val service: AccessibilityService,
    private val random: Random = Random.Default,
) {

    /** 手势进行中标记, 避免叠加派发。 */
    @Volatile
    private var inFlight: Boolean = false

    /** 看门狗用, 主线程 handler。见 [dispatch]。 */
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 本引擎自己派发的手势要到什么时候才算"结束"。
     *
     * 为什么必须记: `TouchOutsideWatcher` 用 FLAG_WATCH_OUTSIDE_TOUCH 监听手指按下,
     * 而 `dispatchGesture` 注入的触摸走的是同一条输入通路 —— 我们自己划的那一下
     * 也会被当成"用户在操作", 于是触发 3 秒避让冷却。结果是每划走一条广告,
     * 下一条广告就要多等 3 秒才可能被处理, 体感就是"识别特别慢"。
     * 这里记录一个自用手势时间窗, 上层据此忽略自己的触摸。
     */
    @Volatile
    private var selfGestureUntil: Long = 0L

    /** [now] 时刻是否处于本引擎自己派发的手势窗口内。 */
    fun isSelfGesture(now: Long): Boolean = now < selfGestureUntil

    val isBusy: Boolean get() = inFlight

    /**
     * 生成右手中下部自然上挑的轨迹。
     *
     * @param boost 滑动失败后的补偿系数, >1 时拉大纵向跨度
     */
    fun buildSwipeUp(
        screenWidth: Int,
        screenHeight: Int,
        boost: Float = 1f,
    ): SwipeSpec {
        val w = screenWidth.toFloat()
        val h = screenHeight.toFloat()

        // 起点: 拇指热区, 宽 55%~78%, 高 72%~82%
        val startX = w * (0.55f + random.nextFloat() * 0.23f)
        val startY = h * (0.72f + random.nextFloat() * 0.10f)

        // 终点: 向上 48%~60% 屏高, 补偿时最多再加 36%
        val spanRatio = (0.48f + random.nextFloat() * 0.12f) * boost.coerceIn(1f, 1.4f)
        val endY = (startY - h * spanRatio).coerceAtLeast(h * 0.10f)
        val endX = startX + (random.nextFloat() - 0.35f) * (w * 0.08f)

        // 二阶贝塞尔控制点: 模拟大拇指关节旋转的轻微弧线
        val controlX = (startX + endX) / 2f + (random.nextFloat() - 0.5f) * (w * 0.10f)
        val controlY = (startY + endY) / 2f

        // 时长服从正态分布并钳制, 避免每次都是同一毫秒数
        val duration = (235 + random.nextGaussianClamped() * 30)
            .toLong()
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        return SwipeSpec(startX, startY, controlX, controlY, endX, endY, duration)
    }

    /** 在节点中心附近做微偏点击, 避免每次都精确打在几何中心。 */
    fun buildMicroTap(centerX: Int, centerY: Int, radiusPx: Int): TapSpec {
        val dx = (random.nextFloat() - 0.5f) * 2f * radiusPx
        val dy = (random.nextFloat() - 0.5f) * 2f * radiusPx
        return TapSpec(
            x = centerX + dx,
            y = centerY + dy,
            durationMs = (40 + random.nextInt(0, 50)).toLong(),
        )
    }

    fun performSwipe(spec: SwipeSpec, onDone: (Boolean) -> Unit = {}): Boolean {
        if (inFlight) {
            onDone(false)
            return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(spec.toPath(), 0L, spec.durationMs))
            .build()
        return dispatch(gesture, onDone)
    }

    fun performTap(spec: TapSpec, onDone: (Boolean) -> Unit = {}): Boolean {
        if (inFlight) {
            onDone(false)
            return false
        }
        val path = Path().apply {
            moveTo(spec.x, spec.y)
            lineTo(spec.x + 1f, spec.y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, spec.durationMs))
            .build()
        return dispatch(gesture, onDone)
    }

    private fun dispatch(gesture: GestureDescription, onDone: (Boolean) -> Unit): Boolean {
        inFlight = true
        val durationMs = gesture.getStroke(0)?.duration ?: 0L
        // 自己这一下触摸不算"用户操作", 见 selfGestureUntil 的说明。
        selfGestureUntil = android.os.SystemClock.uptimeMillis() + durationMs + SELF_GESTURE_GRACE_MS
        // 看门狗: 系统吞掉回调时(ColorOS 上实测发生过), inFlight 会永久卡在 true,
        // 之后所有手势都静默 no-op —— 表现是"日志里划了, 屏幕上没动"。
        // 手势时长 + 1.5s 还没收到回调, 就强制解锁。正常回调会先到, 这里只是兜底。
        watchdog.postDelayed({
            if (inFlight) inFlight = false
        }, durationMs + 1500L)
        val ok = runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        watchdog.removeCallbacksAndMessages(null)
                        inFlight = false
                        onDone(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        watchdog.removeCallbacksAndMessages(null)
                        inFlight = false
                        onDone(false)
                    }
                },
                null,
            )
        }.getOrDefault(false)
        if (!ok) {
            watchdog.removeCallbacksAndMessages(null)
            inFlight = false
        }
        return ok
    }

    /** Kotlin 标准库没有 nextGaussian, 用 Box-Muller 补一个并做截断。 */
    private fun Random.nextGaussianClamped(): Float {
        val u1 = nextFloat().coerceAtLeast(1e-6f)
        val u2 = nextFloat()
        val g = (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1.toDouble())) *
            kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
        return g.coerceIn(-2f, 2f)
    }

    companion object {
        const val MIN_DURATION_MS = 185L
        const val MAX_DURATION_MS = 310L

        /** 手势结束后的宽限, 覆盖系统把 ACTION_OUTSIDE 投递上来的延迟 */
        const val SELF_GESTURE_GRACE_MS = 400L

        /** 距离阈值, 用于判断两次点击是否算"同一点" */
        fun distance(a: TapSpec, b: TapSpec): Float =
            kotlin.math.hypot(abs(a.x - b.x), abs(a.y - b.y))
    }
}
