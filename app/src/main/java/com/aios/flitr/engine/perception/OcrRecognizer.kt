package com.aios.flitr.engine.perception

import android.graphics.Bitmap
import com.aios.flitr.engine.model.Bounds

/** OCR 识别出的一个文本块。 */
data class OcrBlock(val text: String, val bounds: Bounds)

/**
 * 端侧文字识别抽象。
 *
 * 之所以做成接口而不是直接绑死某个引擎: PRD 要求「零联网权限」,
 * 而不同 OCR 方案的模型分发方式差别很大(内置模型 / 系统能力 / 需下载)。
 * 用一个接口把选择权留在装配层, 换引擎不需要改动感知管线。
 */
interface OcrRecognizer {
    /** 当前设备上是否真的可用。不可用时上层应直接走纯节点树模式。 */
    val isAvailable: Boolean

    suspend fun recognize(bitmap: Bitmap): List<OcrBlock>
}

/**
 * 占位实现: 明确表达"未接入 OCR"。
 *
 * 与"接入了但识别不出"在语义上完全不同 —— 上层据此可以决定
 * 是否要把「疑似自绘广告」记为能力缺口, 而不是记为识别失败。
 */
object UnavailableOcrRecognizer : OcrRecognizer {
    override val isAvailable: Boolean = false
    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> = emptyList()
}
