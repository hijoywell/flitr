package com.aios.flitr.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aios.flitr.di.ServiceLocator
import com.aios.flitr.ui.FlitrViewModel
import com.aios.flitr.ui.VSpace
import com.aios.flitr.ui.components.BentoCard
import com.aios.flitr.ui.components.FlitrSwitch
import com.aios.flitr.ui.components.HairlineDivider
import com.aios.flitr.ui.components.NeutralChip
import com.aios.flitr.ui.components.PageHeader
import com.aios.flitr.ui.components.SectionHeader
import com.aios.flitr.ui.components.SegmentedChoice
import com.aios.flitr.ui.theme.LocalFlitrTokens

private enum class RuleSegment(val label: String) {
    Video("视频托管"),
    Splash("开屏规则"),
    Whitelist("白名单"),
}

@Composable
fun RulesScreen(vm: FlitrViewModel) {
    val tokens = LocalFlitrTokens.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val bundle by vm.ruleBundle.collectAsStateWithLifecycle()
    val source by vm.ruleSource.collectAsStateWithLifecycle()
    var segment by remember { mutableStateOf(RuleSegment.Video) }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            PageHeader(title = "场景规则") {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(tokens.card),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge, color = tokens.text)
                }
            }
            SegmentedChoice(
                options = RuleSegment.entries.toList(),
                selected = segment,
                label = { it.label },
                onSelect = { segment = it },
            )
            VSpace(12)
        }

        when (segment) {
            RuleSegment.Video -> VideoRules(
                vm = vm,
                scenarios = bundle.videoScenarios,
                enabledPackages = prefs.videoPackages,
                ruleSource = source,
            )

            RuleSegment.Splash -> SplashRulesSection(
                vm = vm,
                sdkCount = bundle.splashRules.globalSdkSkipViewIds.size,
                heuristic = bundle.splashRules.heuristicSkipRegex,
                maxArea = bundle.splashRules.maxNodeScreenAreaRatio,
                enabled = prefs.splashEnabled,
            )

            RuleSegment.Whitelist -> WhitelistSection(
                whitelist = prefs.whitelist,
                onChange = { vm.setWhitelist(it) },
            )
        }
    }
}

@Composable
private fun VideoRules(
    vm: FlitrViewModel,
    scenarios: List<com.aios.flitr.engine.rules.VideoScenarioRule>,
    enabledPackages: Set<String>,
    ruleSource: String,
) {
    val tokens = LocalFlitrTokens.current

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            BentoCard {
                SectionHeader(title = "托管策略", icon = "🎯", trailing = {
                    NeutralChip(ruleSource)
                })
                VSpace(12)
                StrategyRow(
                    badge = "A",
                    badgeColor = tokens.primary,
                    title = "无倒计时信息流广告",
                    desc = "450ms 稳定帧确认无锁 → 拟人曲线直接划走",
                )
                VSpace(10)
                StrategyRow(
                    badge = "B",
                    badgeColor = tokens.warn,
                    title = "有倒计时解锁 / 激励广告",
                    desc = "实时追踪剩余秒数 → 归零后自动点关闭或上滑",
                )
            }
        }

        items(scenarios, key = { it.packageName }) { scenario ->
            val enabled = scenario.packageName in enabledPackages
            BentoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppAvatar(label = scenario.appName, primary = enabled)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = scenario.appName,
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.text,
                            )
                            if (enabled) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "托管中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.okFg,
                                )
                            }
                        }
                        Text(
                            text = scenario.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textTer,
                        )
                    }
                    FlitrSwitch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            val next = if (on) enabledPackages + scenario.packageName
                            else enabledPackages - scenario.packageName
                            vm.setVideoPackages(next)
                        },
                    )
                }
                if (enabled) {
                    VSpace(12)
                    HairlineDivider()
                    VSpace(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeutralChip("稳定帧 ${scenario.debounceMs}ms")
                        NeutralChip("延迟 ${scenario.reactionDelayRangeMs.joinToString("~")}ms")
                        if (scenario.ocrFallbackEnabled) NeutralChip("OCR 兜底")
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyRow(badge: String, badgeColor: androidx.compose.ui.graphics.Color, title: String, desc: String) {
    val tokens = LocalFlitrTokens.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(badgeColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = tokens.text)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = tokens.textSec)
        }
    }
}

@Composable
private fun AppAvatar(label: String, primary: Boolean) {
    val tokens = LocalFlitrTokens.current
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) tokens.primary else tokens.block),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1),
            style = MaterialTheme.typography.titleMedium,
            color = if (primary) tokens.onPrimary else tokens.textSec,
        )
    }
}

@Composable
private fun SplashRulesSection(
    vm: FlitrViewModel,
    sdkCount: Int,
    heuristic: String,
    maxArea: Float,
    enabled: Boolean,
) {
    val tokens = LocalFlitrTokens.current
    Column(Modifier.padding(horizontal = 18.dp)) {
        BentoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开屏广告秒跳", style = MaterialTheme.typography.titleMedium, color = tokens.text)
                    Text(
                        text = "三级匹配 + 面积防欺诈",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSec,
                    )
                }
                FlitrSwitch(checked = enabled, onCheckedChange = { vm.setSplashEnabled(it) })
            }
            VSpace(14)
            HairlineDivider()
            VSpace(14)
            KeyValueRow("广告 SDK 控件特征", "$sdkCount 条")
            VSpace(8)
            KeyValueRow("防假按钮面积上限", "屏占比 ${"%.0f".format(maxArea * 100)}%")
            VSpace(8)
            KeyValueRow("检测时间窗", "切入应用后 6 秒内")
            VSpace(14)
            Text(
                text = "启发式正则",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTer,
            )
            VSpace(4)
            Text(
                text = heuristic,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        VSpace(12)
        BentoCard(color = tokens.block) {
            Text(
                text = "为什么需要时间窗与面积校验?",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.text,
            )
            VSpace(6)
            Text(
                text = "开屏广告几乎只在应用切入瞬间出现, 限定 6 秒窗口可以避免你在微信聊天、浏览器阅读时误触。" +
                    "面积校验则用来识别全屏诱导层 —— 真正的跳过按钮一定是小控件。",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
            )
        }
    }
}

@Composable
private fun WhitelistSection(whitelist: Set<String>, onChange: (Set<String>) -> Unit) {
    val tokens = LocalFlitrTokens.current
    var query by remember { mutableStateOf("") }
    val apps = remember { ServiceLocator.installedApps() }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.second.contains(query, true) || it.first.contains(query, true) }
    }

    Column(Modifier.padding(horizontal = 18.dp)) {
        BentoCard {
            Text(
                text = "完全不介入的应用",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.text,
            )
            Text(
                text = "系统桌面、电话、支付收银台默认已勾选",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
            )
            VSpace(12)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.block)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索应用…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textTer,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.text),
                    cursorBrush = SolidColor(tokens.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filtered, key = { it.first }) { (pkg, label) ->
            val checked = pkg in whitelist
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(tokens.card)
                    .clickable {
                        onChange(if (checked) whitelist - pkg else whitelist + pkg)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppAvatar(label = label, primary = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = label, style = MaterialTheme.typography.titleSmall, color = tokens.text)
                    Text(text = pkg, style = MaterialTheme.typography.bodySmall, color = tokens.textTer)
                }
                FlitrSwitch(checked = checked, onCheckedChange = {
                    onChange(if (it) whitelist + pkg else whitelist - pkg)
                })
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    val tokens = LocalFlitrTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = key, style = MaterialTheme.typography.bodySmall, color = tokens.textSec)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.text,
            fontWeight = FontWeight.Bold,
        )
    }
}
