package com.aios.flitr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aios.flitr.data.logs.ActionLog
import com.aios.flitr.data.logs.LogKind
import com.aios.flitr.data.prefs.FlitrPrefs
import com.aios.flitr.di.ServiceLocator
import com.aios.flitr.engine.FlitrRuntime
import com.aios.flitr.engine.rules.RuleBundle
import com.aios.flitr.engine.video.VideoHostState
import com.aios.flitr.ui.theme.AccentPalette
import com.aios.flitr.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/** 仪表盘聚合指标。 */
data class DashboardStats(
    val todaySplash: Int = 0,
    val todayVideo: Int = 0,
    val totalIntercepted: Int = 0,
    val savedMinutes: Int = 0,
    val weekSplashTrend: List<Float> = emptyList(),
    val weekVideoTrend: List<Float> = emptyList(),
    val todaySavedMinutes: Int = 0,
)

/** 工作模式: 由两个引擎开关推导出的用户意图。 */
enum class WorkMode(val label: String) {
    Both("双擎全开"),
    SplashOnly("仅跳开屏"),
    VideoOnly("仅划短剧"),
}

class FlitrViewModel : ViewModel() {

    private val settings = ServiceLocator.settings
    private val logs = ServiceLocator.logs
    private val rules = ServiceLocator.rules

    val prefs: StateFlow<FlitrPrefs> = settings.prefs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FlitrPrefs(),
    )

    val logEntries: StateFlow<List<ActionLog>> = logs.logs

    val ruleBundle: StateFlow<RuleBundle> = rules.bundle

    val ruleSource: StateFlow<String> = rules.source

    val serviceConnected: StateFlow<Boolean> = FlitrRuntime.serviceConnected
    val currentApp: StateFlow<String?> = FlitrRuntime.currentAppName
    val engineState: StateFlow<VideoHostState> = FlitrRuntime.engineState
    val pill: StateFlow<com.aios.flitr.engine.PillState> = FlitrRuntime.pill

    val stats: StateFlow<DashboardStats> = logEntries.combine(prefs) { entries, _ ->
        computeStats(entries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardStats())

    val workMode: StateFlow<WorkMode> = prefs
        .map { p ->
            when {
                p.splashEnabled && p.videoEnabled -> WorkMode.Both
                p.splashEnabled -> WorkMode.SplashOnly
                p.videoEnabled -> WorkMode.VideoOnly
                else -> WorkMode.Both
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkMode.Both)

    // ---------- 设置动作 ----------

    fun setThemeMode(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun setAccent(accent: AccentPalette) = launch { settings.setAccent(accent) }
    fun setFollowSystemDark(v: Boolean) = launch { settings.setFollowSystemDark(v) }
    fun setSplashEnabled(v: Boolean) = launch { settings.setSplashEnabled(v) }
    fun setVideoEnabled(v: Boolean) = launch { settings.setVideoEnabled(v) }
    fun setPopupEnabled(v: Boolean) = launch { settings.setPopupEnabled(v) }
    fun setPaused(v: Boolean) = launch { settings.setPaused(v) }
    fun setPillEnabled(v: Boolean) = launch { settings.setPillEnabled(v) }
    fun setOcrEnabled(v: Boolean) = launch { settings.setOcrEnabled(v) }
    fun setAutoContinueWatching(v: Boolean) = launch { settings.setAutoContinueWatching(v) }
    fun setHumanizeGesture(v: Boolean) = launch { settings.setHumanizeGesture(v) }
    fun setPersistentNotification(v: Boolean) = launch { settings.setPersistentNotification(v) }
    fun setReactionRange(min: Int, max: Int) = launch { settings.setReactionRange(min, max) }
    fun setSwipeDurationRange(min: Int, max: Int) = launch { settings.setSwipeDurationRange(min, max) }
    fun setTouchCooldown(ms: Int) = launch { settings.setTouchCooldown(ms) }
    fun setDebounce(ms: Int) = launch { settings.setDebounce(ms) }
    fun setWhitelist(pkgs: Set<String>) = launch { settings.setWhitelist(pkgs) }
    fun setVideoPackages(pkgs: Set<String>) = launch { settings.setVideoPackages(pkgs) }

    fun setWorkMode(mode: WorkMode) = launch {
        when (mode) {
            WorkMode.Both -> {
                settings.setSplashEnabled(true)
                settings.setVideoEnabled(true)
            }

            WorkMode.SplashOnly -> {
                settings.setSplashEnabled(true)
                settings.setVideoEnabled(false)
            }

            WorkMode.VideoOnly -> {
                settings.setSplashEnabled(false)
                settings.setVideoEnabled(true)
            }
        }
    }

    fun markFalsePositive(id: String) = launch { logs.markFalsePositive(id) }
    fun clearLogs() = launch { logs.clear() }

    fun importRules(uri: android.net.Uri, onResult: (String?) -> Unit) = launch {
        val error = rules.importFrom(uri)
        onResult(error)
    }

    fun exportRules(onReady: (String) -> Unit) = launch { onReady(rules.exportJson()) }

    fun resetRules() = launch { rules.resetToDefault() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun computeStats(entries: List<ActionLog>): DashboardStats {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val today = entries.filter { it.timestampMs >= todayStart && !it.falsePositive }
        val splashKinds = setOf(LogKind.SplashSkip.name)
        val videoKinds = setOf(
            LogKind.VideoNoCountdownSwipe.name,
            LogKind.VideoCountdownSwipe.name,
            LogKind.VideoClose.name,
            LogKind.ContinueWatching.name,
        )

        val todaySplash = today.count { it.kind in splashKinds }
        val todayVideo = today.count { it.kind in videoKinds }

        // 节省时间估算: 开屏按 4s / 次, 视频广告按 12s / 次。
        // 这是产品口径的粗估, 不追求秒级精确, 但保证跨天可比较。
        val savedSeconds = entries.count { !it.falsePositive && it.kind in splashKinds } * 4 +
            entries.count { !it.falsePositive && it.kind in videoKinds } * 12

        val dayBuckets = (6 downTo 0).map { offset ->
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -offset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }
        val splashTrend = dayBuckets.map { start ->
            entries.count {
                it.timestampMs >= start && it.timestampMs < start + DAY_MS && it.kind in splashKinds
            }.toFloat()
        }
        val videoTrend = dayBuckets.map { start ->
            entries.count {
                it.timestampMs >= start && it.timestampMs < start + DAY_MS && it.kind in videoKinds
            }.toFloat()
        }

        return DashboardStats(
            todaySplash = todaySplash,
            todayVideo = todayVideo,
            totalIntercepted = entries.count { !it.falsePositive },
            savedMinutes = savedSeconds / 60,
            weekSplashTrend = splashTrend,
            weekVideoTrend = videoTrend,
            todaySavedMinutes = (todaySplash * 4 + todayVideo * 12) / 60,
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
