package com.aios.flitr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aios.flitr.di.ServiceLocator

class FlitrApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_GUARD,
            "守护状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 Flitr 无障碍守护的运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_GUARD = "flitr_guard"
        const val NOTIFICATION_ID = 0x1F1
    }
}
