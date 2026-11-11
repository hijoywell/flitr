package com.aios.flitr.engine.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.aios.flitr.engine.EngineLog
import com.aios.flitr.engine.model.Bounds
import com.aios.flitr.engine.model.TextNode
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * L2 感知: 静默截图 + 局部 ROI OCR 兜底。
 *
 * 性能设计（见 ARCHITECTURE 3.2 与 DEVICE-VALIDATION 2.4）：
 * 不识别整张大图，只裁**顶部 15%**（广告角标 / 激励倒计时 / 跳过按钮）与**底部 28%**
 * （转化按钮 / 商品卡 / 领取入口），**竖向拼接成一张小图**只做一次推理。
 * 拼接而不是分别推理，是为了把每次抓帧的推理次数从 2 次压到 1 次。
 *
 * 之所以必须有这一层：真机实测红果广告页的节点树只有 24 个节点，
 * 广告主体（「广告」角标、「立即领取」、价格）全部画在 SurfaceView 里，节点树完全看不到。
 */
class SilentRoiOcrParser(
    private val service: AccessibilityService,
    private val recognizer: OcrRecognizer,
) {

    /** 结果回主线程, 因为后续会触发手势与 UI 更新 */
    private val mainExecutor: Executor = Executor { r -> Handler(Looper.getMainLooper()).post(r) }

    /** 截图与 OCR 都放在单线程后台, 避免阻塞主线程 */
    private val workExecutor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flitr-ocr").apply { isDaemon = true }
    }

    @Volatile
    private var lastCaptureAt: Long = 0L

    @Volatile
    private var capturing: Boolean = false

    val isAvailable: Boolean get() = recognizer.isAvailable

    /**
     * 尝试抓取一帧并识别。
     *
     * @param onResult 识别到的文本块（坐标已换算回屏幕坐标系）；失败或节流时回调空列表
     */
    fun captureAsync(now: Long, onResult: (List<TextNode>) -> Unit) {
        if (!recognizer.isAvailable) {
            onResult(emptyList())
            return
        }
        if (capturing) {
            onResult(emptyList())
            return
        }
        if (now - lastCaptureAt < MIN_INTERVAL_MS) {
            onResult(emptyList())
            return
        }
        capturing = true
        lastCaptureAt = now

        val dispatched = runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                workExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val nodes = runCatching { process(screenshot) }.getOrDefault(emptyList())
                        capturing = false
                        mainExecutor.execute { onResult(nodes) }
                    }

                    override fun onFailure(errorCode: Int) {
                        EngineLog.w("OCR takeScreenshot failed code=$errorCode")
                        capturing = false
                        mainExecutor.execute { onResult(emptyList()) }
                    }
                },
            )
        }.isSuccess

        if (!dispatched) {
            capturing = false
            onResult(emptyList())
        }
    }

    private fun process(screenshot: AccessibilityService.ScreenshotResult): List<TextNode> {
        val buffer = screenshot.hardwareBuffer
        val colorSpace = screenshot.colorSpace
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return emptyList()
            // 硬件位图不能直接裁剪, 先落到可读配置
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return emptyList()
            hardware.recycle()

            val w = software.width
            val h = software.height
            val topH = (h * TOP_RATIO).toInt()
            val bottomH = (h * BOTTOM_RATIO).toInt()
            val bottomTop = h - bottomH

            if (topH <= 0 || bottomH <= 0 || w <= 0) {
                software.recycle()
                return emptyList()
            }

            val topCrop = Bitmap.createBitmap(software, 0, 0, w, topH)
            val bottomCrop = Bitmap.createBitmap(software, 0, bottomTop, w, bottomH)
            software.recycle()

            // 竖向拼接: 上条 + 下条, 只做一次推理
            val combined = Bitmap.createBitmap(w, topH + bottomH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined)
            canvas.drawBitmap(topCrop, 0f, 0f, null)
            canvas.drawBitmap(bottomCrop, 0f, topH.toFloat(), null)
            topCrop.recycle()
            bottomCrop.recycle()

            val blocks = kotlinx.coroutines.runBlocking { recognizer.recognize(combined) }
            combined.recycle()

            val nodes = blocks.mapNotNull { block ->
                // 把拼接图里的 y 换算回屏幕坐标
                val screenTop = if (block.bounds.top < topH) {
                    block.bounds.top
                } else {
                    block.bounds.top - topH + bottomTop
                }
                val screenBottom = if (block.bounds.top < topH) {
                    block.bounds.bottom
                } else {
                    block.bounds.bottom - topH + bottomTop
                }
                val bounds = Bounds(
                    left = block.bounds.left,
                    top = screenTop,
                    right = block.bounds.right,
                    bottom = screenBottom,
                )
                if (bounds.isEmpty) return@mapNotNull null
                TextNode(text = block.text, bounds = bounds, fromOcr = true)
            }

            if (nodes.isNotEmpty()) {
                EngineLog.d("OCR ${nodes.size} blocks: " + nodes.take(8).joinToString(" / ") { it.normalized })
            }
            nodes
        } finally {
            runCatching { buffer.close() }
        }
    }

    companion object {
        /** 系统限制约 1 次/秒, 留一点余量 */
        const val MIN_INTERVAL_MS = 1100L

        /** 顶部状态区: 广告角标 / 倒计时 / 跳过按钮 */
        const val TOP_RATIO = 0.15f

        /** 底部信息栏: 转化按钮 / 商品卡 / 领取入口 */
        const val BOTTOM_RATIO = 0.28f
    }
}
