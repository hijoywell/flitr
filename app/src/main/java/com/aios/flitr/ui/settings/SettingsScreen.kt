package com.aios.flitr.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aios.flitr.ui.FlitrViewModel
import com.aios.flitr.ui.VSpace
import com.aios.flitr.ui.components.BentoCard
import com.aios.flitr.ui.components.FlitrSwitch
import com.aios.flitr.ui.components.HairlineDivider
import com.aios.flitr.ui.components.PageHeader
import com.aios.flitr.ui.components.SectionHeader
import com.aios.flitr.ui.components.SegmentedChoice
import com.aios.flitr.ui.theme.AccentPalette
import com.aios.flitr.ui.theme.LocalFlitrTokens
import com.aios.flitr.ui.theme.ThemeMode
import com.aios.flitr.ui.util.SystemIntents
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: FlitrViewModel) {
    val tokens = LocalFlitrTokens.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val connected by vm.serviceConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importRules(uri) { error ->
                notice = error ?: "规则已导入, 立即生效"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            vm.exportRules { text ->
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    }
                }.isSuccess
                notice = if (ok) "规则已导出" else "导出失败"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        PageHeader(title = "工具设置")

        // ---------- 主题 ----------
        BentoCard {
            SectionHeader(title = "主题与外观风格", icon = "🎨")
            VSpace(12)
            SegmentedChoice(
                options = ThemeMode.entries.toList(),
                selected = prefs.themeMode,
                label = { it.label },
                onSelect = { vm.setThemeMode(it) },
            )
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentPalette.entries.forEach { accent ->
                    val selected = prefs.accent == accent
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(accent.color)
                            .then(
                                if (selected) {
                                    Modifier.border(3.dp, tokens.text.copy(alpha = 0.85f), CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { vm.setAccent(accent) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (accent == AccentPalette.Amber) Color(0xFF1F1204) else Color.White,
                            )
                        }
                    }
                }
            }
            VSpace(8)
            Text(
                text = "当前: ${prefs.accent.label}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textTer,
            )
        }

        VSpace(12)

        // ---------- 手势参数 ----------
        BentoCard {
            SectionHeader(title = "拟人贝塞尔手势参数", icon = "✋")
            VSpace(12)
            LabeledRange(
                label = "广告识别后反应延迟",
                valueText = "${prefs.reactionMinMs} ~ ${prefs.reactionMaxMs} ms",
                value = prefs.reactionMinMs.toFloat()..prefs.reactionMaxMs.toFloat(),
                range = 100f..1500f,
                onChange = { vm.setReactionRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
            )
            VSpace(6)
            LabeledRange(
                label = "滑动时长",
                valueText = "${prefs.swipeDurationMinMs} ~ ${prefs.swipeDurationMaxMs} ms",
                value = prefs.swipeDurationMinMs.toFloat()..prefs.swipeDurationMaxMs.toFloat(),
                range = 120f..420f,
                onChange = { vm.setSwipeDurationRange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
            )
            VSpace(6)
            LabeledSlider(
                label = "手指触屏避让冷却",
                valueText = "${"%.1f".format(prefs.touchCooldownMs / 1000f)} 秒",
                value = prefs.touchCooldownMs.toFloat(),
                range = 1000f..8000f,
                onChange = { vm.setTouchCooldown(it.roundToInt()) },
            )
            VSpace(6)
            LabeledSlider(
                label = "广告稳定帧观察期",
                valueText = "${prefs.debounceMs} ms",
                value = prefs.debounceMs.toFloat(),
                range = 200f..1000f,
                onChange = { vm.setDebounce(it.roundToInt()) },
            )
            VSpace(10)
            ToggleRow(
                title = "拟人轨迹随机化",
                desc = "随机起止坐标、弧度微偏、变速滑动",
                checked = prefs.humanizeGesture,
                onChange = { vm.setHumanizeGesture(it) },
            )
        }

        VSpace(12)

        // ---------- 行为开关 ----------
        BentoCard {
            SectionHeader(title = "行为开关", icon = "🎛")
            VSpace(8)
            ToggleRow(
                title = "中途弹窗自动续播",
                desc = "自动点击「继续观看 / 开心收下」",
                checked = prefs.autoContinueWatching,
                onChange = { vm.setAutoContinueWatching(it) },
            )
            HairlineDivider()
            ToggleRow(
                title = "液态毛玻璃状态胶囊",
                desc = "边缘实时显示读秒, 免悬浮窗权限",
                checked = prefs.pillEnabled,
                onChange = { vm.setPillEnabled(it) },
            )
            HairlineDivider()
            ToggleRow(
                title = "静默局部 OCR 兜底",
                desc = if (connected) "裁剪顶/底区域识别自绘无节点广告" else "需先开启无障碍服务",
                checked = prefs.ocrEnabled,
                onChange = { vm.setOcrEnabled(it) },
            )
            HairlineDivider()
            ToggleRow(
                title = "常驻状态通知",
                desc = "降低被系统回收的概率",
                checked = prefs.persistentNotification,
                onChange = { vm.setPersistentNotification(it) },
            )
        }

        VSpace(12)

        // ---------- 服务体检 ----------
        BentoCard {
            SectionHeader(title = "核心服务体检", icon = "🩺", trailing = {
                Text(
                    text = if (connected) "全部正常" else "待配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected) tokens.okFg else tokens.warn,
                )
            })
            VSpace(12)
            HealthRow("无障碍引擎", if (connected) "已连接" else "未开启", connected)
            HealthRow("电池优化", if (SystemIntents.isIgnoringBatteryOptimizations(context)) "已忽略" else "未设置", SystemIntents.isIgnoringBatteryOptimizations(context))
            HealthRow("端侧 OCR", "未接入", false)
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("无障碍设置") {
                    runCatching { context.startActivity(SystemIntents.accessibilitySettings()) }
                }
                SmallAction("应用详情") {
                    runCatching { context.startActivity(SystemIntents.appDetails(context)) }
                }
                SmallAction("电池白名单") {
                    SystemIntents.batteryOptimization(context)?.let {
                        runCatching { context.startActivity(it) }
                    }
                }
            }
        }

        VSpace(12)

        // ---------- 规则管理 ----------
        BentoCard {
            SectionHeader(title = "规则管理", icon = "📦")
            VSpace(6)
            Text(
                text = "规则更新通过系统文件选择器完成 —— 本应用没有 INTERNET 权限, 无法联网拉取。",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
            )
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("导入规则") {
                    runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                }
                SmallAction("导出规则") {
                    runCatching { exportLauncher.launch("flitr-rules.json") }
                }
                SmallAction("恢复默认") {
                    vm.resetRules()
                    notice = "已恢复内置规则"
                }
            }
            notice?.let {
                VSpace(10)
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.primary,
                )
            }
        }

        VSpace(12)

        // ---------- 隐私 ----------
        BentoCard(color = tokens.block) {
            SectionTitleRow()
            VSpace(6)
            Text(
                text = "Flitr 未在 AndroidManifest 中声明 INTERNET 权限。这不是一句承诺, 而是系统层面的证明:" +
                    "即使代码想联网, 系统也会拒绝。所有识别、判定、日志都在设备本地完成。",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
            )
        }

        Spacer(Modifier.padding(bottom = 28.dp))
    }
}

@Composable
private fun SectionTitleRow() {
    val tokens = LocalFlitrTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🔒", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "零网络离线运行保护",
            style = MaterialTheme.typography.titleSmall,
            color = tokens.text,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "已锁定",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.okFg,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val tokens = LocalFlitrTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = tokens.text)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = tokens.textSec)
        }
        Spacer(Modifier.width(10.dp))
        FlitrSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HealthRow(label: String, value: String, ok: Boolean) {
    val tokens = LocalFlitrTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (ok) tokens.okDot else tokens.textTer),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = tokens.textSec)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) tokens.okFg else tokens.warn,
        )
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    val tokens = LocalFlitrTokens.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.primaryWash)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.primary,
        )
    }
}

@Composable
private fun LabeledRange(
    label: String,
    valueText: String,
    value: ClosedFloatingPointRange<Float>,
    range: ClosedFloatingPointRange<Float>,
    onChange: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    val tokens = LocalFlitrTokens.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = tokens.text)
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.primary,
            )
        }
        RangeSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = tokens.primary,
                activeTrackColor = tokens.primary,
                inactiveTrackColor = tokens.track,
            ),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val tokens = LocalFlitrTokens.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = tokens.text)
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = tokens.primary,
                activeTrackColor = tokens.primary,
                inactiveTrackColor = tokens.track,
            ),
        )
    }
}
