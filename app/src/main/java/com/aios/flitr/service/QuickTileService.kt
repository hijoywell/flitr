package com.aios.flitr.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aios.flitr.di.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 通知栏快捷开关: 一键暂停 / 恢复托管。
 *
 * 暂停状态写入 DataStore, 无障碍服务监听同一个 Flow,
 * 因此不需要跨进程通信, 也不会出现「磁贴显示已暂停但服务仍在动作」的错位。
 */
class QuickTileService : TileService() {

    private var paused: Boolean = false

    override fun onStartListening() {
        super.onStartListening()
        ServiceLocator.appScope.launch {
            paused = runCatching { ServiceLocator.settings.prefs.first().paused }.getOrDefault(false)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        ServiceLocator.appScope.launch {
            val next = !runCatching { ServiceLocator.settings.prefs.first().paused }.getOrDefault(false)
            ServiceLocator.settings.setPaused(next)
            paused = next
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = "Flitr"
        tile.updateTile()
    }
}
