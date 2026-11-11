package com.aios.flitr.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aios.flitr.engine.PillMode
import com.aios.flitr.engine.PillState

/**
 * 应用内微型悬浮胶囊。
 *
 * 使用 `TYPE_ACCESSIBILITY_OVERLAY`, **无需申请 SYSTEM_ALERT_WINDOW** ——
 * 这是 PRD 里「免悬浮窗权限」的落地方式。
 *
 * 这里刻意用原生 View 而不是 Compose: 悬浮层的生命周期独立于任何 Activity,
 * 用 ComposeView 需要自己搭 LifecycleOwner 与 SavedStateRegistry, 成本远大于收益。
 * 胶囊结构简单(圆点 + 两行文本), 原生 View 足够且更轻。
 */
class OverlayPillController(private val service: AccessibilityService) {

    private val windowManager: WindowManager? =
        service.getSystemService(WindowManager::class.java)

    private var root: LinearLayout? = null
    private var dot: View? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (root != null) return
        val wm = windowManager ?: return
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(16), dp(9))
        }

        val dotView = View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        container.addView(
            dotView,
            LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(9) },
        )

        val texts = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(service).apply {
            setTextColor(Color.parseColor("#D9FFFFFF"))
            textSize = 10.5f
        }
        texts.addView(title)
        texts.addView(subtitle)
        container.addView(texts)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(96)
        }

        runCatching { wm.addView(container, lp) }.onFailure { return }

        root = container
        dot = dotView
        titleView = title
        subtitleView = subtitle
        params = lp
    }

    fun hide() {
        val wm = windowManager ?: return
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
    }

    fun update(state: PillState) {
        if (!state.visible) {
            hide()
            return
        }
        show()
        val container = root ?: return
        val color = when (state.mode) {
            PillMode.Guarding -> "#E6007AFF"
            PillMode.Waiting -> "#E6F59337"
            PillMode.Protected -> "#E610B981"
            PillMode.Paused -> "#E64B5563"
        }
        container.background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(Color.parseColor(color))
        }
        titleView?.text = state.title
        subtitleView?.text = buildSubtitle(state)
        subtitleView?.visibility = if (subtitleView?.text.isNullOrBlank()) View.GONE else View.VISIBLE
        dot?.alpha = if (state.mode == PillMode.Paused) 0.45f else 1f
    }

    private fun buildSubtitle(state: PillState): String {
        val remaining = state.remainingSeconds
        return if (remaining != null) {
            "剩余 ${remaining}s · ${state.subtitle}"
        } else {
            state.subtitle
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    companion object {
        /** 胶囊顶部偏移, 避开状态栏与 App 自身的标题栏 */
        const val DEFAULT_TOP_OFFSET_DP = 96
    }
}
