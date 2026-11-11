package com.aios.flitr.engine.perception

import android.graphics.Bitmap
import com.aios.flitr.engine.model.Bounds
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 基于 ML Kit 内置中文模型（`text-recognition-chinese`）的端侧识别实现。
 *
 * 选型理由：
 * 1. **完全离线** —— 内置模型随 APK 分发，运行时不下载、不联网，
 *    不破坏「不声明 INTERNET 权限」这一核心约束；
 * 2. **不依赖 Google Play 服务** —— 国产 ROM 普遍没有 GMS，
 *    `play-services-mlkit-*` 那一套在目标机型上直接不可用；
 * 3. 中文短剧广告里的关键词（广告、立即领取、查看详情）都是常见字形，识别率足够。
 *
 * 代价是 APK 体积增加约 20MB，对这个场景是可接受的交换。
 */
class MlKitOcrRecognizer : OcrRecognizer, AutoCloseable {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override val isAvailable: Boolean = true

    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> =
        suspendCancellableCoroutine { cont ->
            val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
            if (image == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    if (cont.isActive) cont.resume(text.toBlocks())
                }
                .addOnFailureListener {
                    // 识别失败不应该影响主流程: 返回空列表, 上层继续走纯节点树判定
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    override fun close() {
        runCatching { recognizer.close() }
    }

    /**
     * 按**行**输出而不是按块。
     *
     * 行是广告判定最合适的粒度: 「15秒后可领取」这类倒计时文案往往独占一行,
     * 而按块合并后会把角标和正文粘在一起, 反而让正则难以精确匹配。
     */
    private fun Text.toBlocks(): List<OcrBlock> {
        val out = ArrayList<OcrBlock>(32)
        for (block in textBlocks) {
            for (line in block.lines) {
                val content = line.text.trim()
                if (content.isEmpty()) continue
                val box = line.boundingBox ?: continue
                out += OcrBlock(
                    text = content,
                    bounds = Bounds(box.left, box.top, box.right, box.bottom),
                )
            }
        }
        return out
    }
}
