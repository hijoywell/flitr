package com.aios.flitr.engine

import android.content.Context
import com.aios.flitr.engine.model.ScreenSnapshot
import com.aios.flitr.engine.video.AdAssessment
import java.io.File

/**
 * 把当前屏幕快照落盘。
 *
 * 为什么需要它: 广告页只存在几秒, 而 `uiautomator dump` 在视频页会因窗口永不空闲而失败
 * ("could not get idle state"), 事后完全无法复现当时看到的是什么节点。
 * 让引擎在判定瞬间把节点树写进文件, 是唯一可靠的取证方式。
 *
 * 这份输出同时也是 PRD 里「规则快照拾取器」的数据基础 —— 用户抓到一次广告,
 * 就能把这份文件交给开发者适配规则。
 */
object SnapshotDumper {

    private const val THROTTLE_MS = 900L
    private var lastDumpAt = 0L
    private var lastFingerprint: String? = null

    fun dumpIfChanged(
        context: Context,
        snapshot: ScreenSnapshot,
        assessment: AdAssessment?,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val fingerprint = snapshot.fingerprint()
        val same = fingerprint == lastFingerprint
        if (!force) {
            if (same && now - lastDumpAt < THROTTLE_MS) return
            if (now - lastDumpAt < THROTTLE_MS) return
        }
        lastDumpAt = now
        lastFingerprint = fingerprint
        runCatching { write(context, snapshot, assessment) }
    }

    private fun write(context: Context, snapshot: ScreenSnapshot, assessment: AdAssessment?) {
        val dir = File(context.filesDir, "debug").apply { mkdirs() }
        val file = File(dir, "last_snapshot.txt")

        val sb = StringBuilder()
        sb.appendLine("# Flitr snapshot")
        sb.appendLine("time=${System.currentTimeMillis()}")
        sb.appendLine("package=${snapshot.packageName}")
        sb.appendLine("class=${snapshot.className}")
        sb.appendLine("screen=${snapshot.screenWidth}x${snapshot.screenHeight}")
        sb.appendLine("source=${snapshot.source}")
        sb.appendLine("nodes=${snapshot.nodes.size}")
        sb.appendLine("fingerprint=${snapshot.fingerprint()}")
        if (assessment != null) {
            sb.appendLine("isAd=${assessment.isAd} confidence=${"%.2f".format(assessment.confidence)}")
            sb.appendLine("dramaProtected=${assessment.isDramaProtected} panelOpen=${assessment.panelOpen}")
            sb.appendLine("countdown=${assessment.countdownSeconds} completed=${assessment.isCompleted}")
            sb.appendLine("adHit=${assessment.adHit?.normalized ?: "-"}")
            sb.appendLine("closeNode=${assessment.closeNode?.normalized ?: "-"}")
            sb.appendLine("keepWatching=${assessment.keepWatchingNode?.normalized ?: "-"}")
        }
        sb.appendLine("# ---- nodes (text | viewId | bounds) ----")
        snapshot.nodes.forEachIndexed { index, node ->
            val ratio = snapshot.areaRatio(node.bounds)
            sb.appendLine(
                "%03d | '%s' | id=%s | %s | clickable=%s editable=%s | ratio=%.3f".format(
                    index,
                    node.normalized.replace("\n", "\\n"),
                    node.viewId ?: "-",
                    "${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}",
                    node.clickable,
                    node.editable,
                    ratio,
                )
            )
        }
        file.writeText(sb.toString())
    }

    fun clear(context: Context) {
        runCatching { File(File(context.filesDir, "debug"), "last_snapshot.txt").delete() }
    }
}
