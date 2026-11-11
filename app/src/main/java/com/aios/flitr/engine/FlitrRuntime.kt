package com.aios.flitr.engine

import com.aios.flitr.engine.video.VideoHostState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 悬浮胶囊的展示模式。对应设计稿三种形态: 蓝(秒划) / 橙(读秒) / 绿(正片保护)。 */
enum class PillMode { Guarding, Waiting, Paused, Protected }

data class PillState(
    val visible: Boolean = false,
    val mode: PillMode = PillMode.Guarding,
    val title: String = "守护运行中",
    val subtitle: String = "",
    val remainingSeconds: Int? = null,
)

/**
 * 服务与 UI 之间的实时状态桥。
 *
 * 用单例而非注入的原因: 无障碍服务与前台 Activity 是两套完全独立的生命周期,
 * 单例 StateFlow 是最轻的跨进程组件通信方式, 也便于 UI 在服务未启动时优雅降级。
 */
object FlitrRuntime {

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    private val _currentAppName = MutableStateFlow<String?>(null)
    val currentAppName: StateFlow<String?> = _currentAppName.asStateFlow()

    private val _engineState = MutableStateFlow<VideoHostState>(VideoHostState.Idle)
    val engineState: StateFlow<VideoHostState> = _engineState.asStateFlow()

    private val _pill = MutableStateFlow(PillState())
    val pill: StateFlow<PillState> = _pill.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt.asStateFlow()

    private val _serviceSince = MutableStateFlow(0L)

    /** 本次守护开始的墙钟时间(毫秒)。0 表示服务未连接。 */
    val serviceSince: StateFlow<Long> = _serviceSince.asStateFlow()

    fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
        _serviceSince.value = if (connected) System.currentTimeMillis() else 0L
    }

    fun setForeground(pkg: String?, appName: String?) {
        _currentPackage.value = pkg
        _currentAppName.value = appName
    }

    fun setEngineState(state: VideoHostState) {
        _engineState.value = state
    }

    fun setPill(state: PillState) {
        _pill.value = state
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    fun markEvent(now: Long) {
        _lastEventAt.value = now
    }

    /** 服务销毁时清理, 避免 UI 显示过期状态。 */
    fun onServiceDestroyed() {
        _serviceConnected.value = false
        _currentPackage.value = null
        _currentAppName.value = null
        _engineState.value = VideoHostState.Idle
        _pill.value = PillState(visible = false)
    }
}
