package com.aios.flitr.ui.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.aios.flitr.service.FlitrAccessibilityService

/** 系统设置跳转与状态探测。国产 ROM 差异都收敛在这里。 */
object SystemIntents {

    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 应用详情页: Android 13+ 解除「受限设置」的入口在这里。 */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimization(context: Context): Intent? = runCatching {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 判断本应用的无障碍服务是否已启用。
     *
     * 直接读 `ENABLED_ACCESSIBILITY_SERVICES` 而不是缓存一个布尔值:
     * 用户可能在系统设置里随时关掉, 每次回到前台都应该重新核对。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, FlitrAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val target = expected.flattenToString()
        val targetShort = "${expected.packageName}/${expected.className}"
        return enabled.split(':').any {
            it.equals(target, ignoreCase = true) ||
                it.equals(targetShort, ignoreCase = true) ||
                it.endsWith("/${FlitrAccessibilityService::class.java.name}", ignoreCase = true)
        }
    }

    /** 电池优化是否已忽略(即允许后台不受限)。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
