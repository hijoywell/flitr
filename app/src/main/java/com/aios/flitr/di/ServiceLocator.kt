package com.aios.flitr.di

import android.content.Context
import android.content.pm.PackageManager
import com.aios.flitr.data.logs.LogRepository
import com.aios.flitr.data.prefs.SettingsRepository
import com.aios.flitr.engine.rules.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 轻量服务定位器。
 *
 * 这里没有引入 Hilt。原因不是 Hilt 不好, 而是这个项目的依赖图非常浅
 * (3 个仓库 + 1 个应用级作用域), 手写容器反而更透明、启动更快,
 * 也避免注解处理器给构建链增加一个易碎的环节。
 * 若后续模块数量上升, 再迁移到 Hilt 成本很低 —— 调用点都集中在这里。
 */
object ServiceLocator {

    private lateinit var appContext: Context
    private var initialized = false

    private val _appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appScope: CoroutineScope get() = _appScope

    lateinit var settings: SettingsRepository
        private set
    lateinit var logs: LogRepository
        private set
    lateinit var rules: RuleRepository
        private set

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        settings = SettingsRepository(appContext)
        logs = LogRepository(appContext)
        rules = RuleRepository(appContext)
        initialized = true
        _appScope.launch {
            logs.load()
            rules.load()
        }
    }

    fun context(): Context = appContext

    /** 包名 → 应用显示名。取不到时退回包名, 避免 UI 出现空白。 */
    fun appLabel(packageName: String): String = runCatching {
        val pm = appContext.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    /** 本应用自己的包名(debug 变体带后缀)。 */
    fun selfPackage(): String = appContext.packageName

    fun isPackageInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** 已安装的可见应用列表, 供白名单 / 托管应用选择。 */
    fun installedApps(): List<Pair<String, String>> = runCatching {
        val pm = appContext.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second }
            .toList()
    }.getOrDefault(emptyList())
}
