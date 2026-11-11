package com.aios.flitr.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 用户触摸感知哨兵。
 *
 * 思路: 在屏幕角落挂一个 1x1 的透明窗口, 带 `FLAG_WATCH_OUTSIDE_TOUCH`。
 * 用户在**窗口外**任何位置按下手指时, 系统会向这个窗口投递 `ACTION_OUTSIDE`,
 * 我们据此判断"用户在主动操作", 立刻中止自动动作。
 *
 * 已知局限(必须诚实记录):
 * - 部分 ROM 对 `TYPE_ACCESSIBILITY_OVERLAY` 的触摸监听有额外限制, 可能收不到事件;
 * - 因此**不能把它当作唯一依据**。服务层同时保留了事件侧推断(滚动/点击事件并非本服务发起),
 *   两者互为补充, 任一信号都能触发避让。
 *
 * 这个哨兵只在「托管 + 胶囊开启」时启用, 平时完全不存在, 不产生任何开销。
 */
class TouchOutsideWatcher(private val service: AccessibilityService) {

    private val windowManager: WindowManager? =
        service.getSystemService(WindowManager::class.java)

    private var view: View? = null

    val isActive: Boolean get() = view != null

    fun start(onUserTouch: (x: Float, y: Float) -> Unit): Boolean {
        if (view != null) return true
        val wm = windowManager ?: return false

        val sentinel = View(service).apply {
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                    event.actionMasked == MotionEvent.ACTION_DOWN
                ) {
                    // 必须把坐标传出去: 触摸发生在状态栏(下拉通知)或导航栏(返回手势)时,
                    // 与"用户在操作视频"完全无关, 由上层按区域过滤。
                    onUserTouch(event.rawX, event.rawY)
                }
                false
            }
        }

        val lp = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val added = runCatching { wm.addView(sentinel, lp) }.isSuccess
        if (added) view = sentinel
        return added
    }

    fun stop() {
        val wm = windowManager ?: return
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
