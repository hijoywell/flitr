package com.aios.flitr.engine.rules

import android.content.Context
import android.net.Uri
import com.aios.flitr.engine.video.CompiledVideoRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 规则仓库。
 *
 * 加载顺序: 用户导入的规则文件 > 内置 assets/default_rules.json。
 * 之所以支持"用户导入覆盖", 是因为 PRD 要求零网络权限 ——
 * 规则更新只能通过系统文件选择器完成, 这需要一个持久化的本地覆盖位。
 */
class RuleRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _bundle = MutableStateFlow(RuleBundle())
    val bundle: StateFlow<RuleBundle> = _bundle.asStateFlow()

    private val _compiled = MutableStateFlow<Map<String, CompiledVideoRule>>(emptyMap())
    val compiled: StateFlow<Map<String, CompiledVideoRule>> = _compiled.asStateFlow()

    private val _source = MutableStateFlow("内置规则")
    val source: StateFlow<String> = _source.asStateFlow()

    private val overrideFile: File
        get() = File(context.filesDir, "rules/user_rules.json")

    suspend fun load() = withContext(Dispatchers.IO) {
        val override = overrideFile
        val (text, label) = when {
            override.exists() -> override.readText() to "用户导入"
            else -> readAsset() to "内置规则"
        }
        val parsed = runCatching { json.decodeFromString(RuleBundle.serializer(), text) }
            .getOrElse {
                // 用户规则损坏时回退到内置规则, 保证引擎始终可用
                val fallback = readAsset()
                json.decodeFromString(RuleBundle.serializer(), fallback)
            }
        apply(parsed, if (parsed == RuleBundle()) "内置规则" else label)
    }

    private fun apply(bundle: RuleBundle, label: String) {
        _bundle.value = bundle
        _compiled.value = bundle.videoScenarios
            .filter { it.enabled }
            .associate { it.packageName to CompiledVideoRule(it) }
        _source.value = label
    }

    /** 从用户选择的文件导入规则。返回错误信息, null 表示成功。 */
    suspend fun importFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return@withContext "无法读取所选文件"

        val parsed = runCatching { json.decodeFromString(RuleBundle.serializer(), text) }
            .getOrElse { return@withContext "规则格式非法: ${it.message?.take(80)}" }

        if (parsed.videoScenarios.isEmpty() && parsed.splashRules.globalSdkSkipViewIds.isEmpty()) {
            return@withContext "规则内容为空, 已拒绝导入"
        }

        runCatching {
            overrideFile.parentFile?.mkdirs()
            overrideFile.writeText(text)
        }.onFailure { return@withContext "写入失败: ${it.message}" }

        apply(parsed, "用户导入")
        null
    }

    /** 导出当前规则为 JSON 文本。 */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        json.encodeToString(RuleBundle.serializer(), _bundle.value)
    }

    suspend fun resetToDefault() = withContext(Dispatchers.IO) {
        runCatching { overrideFile.delete() }
        val text = readAsset()
        val parsed = json.decodeFromString(RuleBundle.serializer(), text)
        apply(parsed, "内置规则")
    }

    private fun readAsset(): String =
        context.assets.open("default_rules.json").bufferedReader().use { it.readText() }

    fun ruleFor(packageName: String): CompiledVideoRule? = _compiled.value[packageName]

    /** 供「场景规则」页展示: 所有内置场景(含未启用) */
    fun scenarios(): List<VideoScenarioRule> = _bundle.value.videoScenarios
}
