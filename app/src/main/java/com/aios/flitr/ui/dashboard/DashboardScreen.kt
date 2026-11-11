package com.aios.flitr.ui.dashboard

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aios.flitr.data.prefs.FlitrPrefs
import com.aios.flitr.ui.FlitrViewModel
import com.aios.flitr.ui.PagePadding
import com.aios.flitr.ui.Tab
import com.aios.flitr.ui.VSpace
import com.aios.flitr.ui.WorkMode
import com.aios.flitr.ui.components.BentoCard
import com.aios.flitr.ui.components.NeutralChip
import com.aios.flitr.ui.components.PageHeader
import com.aios.flitr.ui.components.SectionHeader
import com.aios.flitr.ui.components.SegmentedChoice
import com.aios.flitr.ui.components.Sparkline
import com.aios.flitr.ui.components.StatusChip
import com.aios.flitr.ui.theme.LocalFlitrTokens
import com.aios.flitr.ui.util.SystemIntents
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(vm: FlitrViewModel, onNavigate: (Tab) -> Unit) {
    val tokens = LocalFlitrTokens.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val connected by vm.serviceConnected.collectAsStateWithLifecycle()
    val workMode by vm.workMode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        PageHeader(title = "仪表盘") {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(tokens.card)
                    .clickable { onNavigate(Tab.Settings) },
                contentAlignment = Alignment.Center,
            ) {
                Text("✎", style = MaterialTheme.typography.titleMedium, color = tokens.text)
            }
        }

        HeroCard(
            connected = connected,
            paused = prefs.paused,
            totalIntercepted = stats.totalIntercepted,
            savedMinutes = stats.savedMinutes,
            onTogglePause = { vm.setPaused(!prefs.paused) },
            onActivate = {
                runCatching { context.startActivity(SystemIntents.accessibilitySettings()) }
            },
        )

        VSpace(12)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = "⚡",
                title = "开屏秒跳",
                value = stats.todaySplash,
                caption = "近 7 日趋势",
                trend = stats.weekSplashTrend,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = "🎬",
                title = "短剧智划",
                value = stats.todayVideo,
                caption = "今日托管",
                trend = stats.weekVideoTrend,
            )
        }

        VSpace(12)

        BentoCard {
            SectionHeader(title = "工作模式", icon = "⚙")
            VSpace(12)
            SegmentedChoice(
                options = WorkMode.entries.toList(),
                selected = workMode,
                label = { it.label },
                onSelect = { vm.setWorkMode(it) },
            )
            VSpace(10)
            Text(
                text = when (workMode) {
                    WorkMode.Both -> "开屏秒跳与短剧智划同时生效"
                    WorkMode.SplashOnly -> "只处理开屏广告, 不介入视频流"
                    WorkMode.VideoOnly -> "只托管视频广告, 开屏保持原样"
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textTer,
            )
        }

        VSpace(12)

        VideoHostCard(
            prefs = prefs,
            onToggleVideo = { vm.setVideoEnabled(it) },
            onManage = { onNavigate(Tab.Rules) },
        )

        VSpace(12)

        if (!connected) {
            BentoCard(color = tokens.block) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "保活体检: 无障碍服务未开启",
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.text,
                        )
                        Text(
                            text = "点击前往系统设置开启 Flitr 广告智划",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSec,
                        )
                    }
                    Text(
                        text = "去开启",
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable {
                                runCatching { context.startActivity(SystemIntents.accessibilitySettings()) }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            VSpace(12)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeroCard(
    connected: Boolean,
    paused: Boolean,
    totalIntercepted: Int,
    savedMinutes: Int,
    onTogglePause: () -> Unit,
    onActivate: () -> Unit,
) {
    val tokens = LocalFlitrTokens.current
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (tokens.isDark) 0.dp else 12.dp, shape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        tokens.primaryWash,
                        tokens.card,
                    )
                )
            ),
    ) {
        // 右侧留出主操作按钮的宽度, 避免文字被按钮压住
        Column(Modifier.padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 80.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛡", style = MaterialTheme.typography.titleSmall, color = tokens.textSec)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "守护状态",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.textSec,
                )
                Spacer(Modifier.width(8.dp))
                if (connected) {
                    StatusChip(
                        text = if (paused) "已暂停" else "运行中",
                        dotColor = if (paused) tokens.textTer else tokens.okDot,
                        background = if (paused) tokens.block else tokens.okBg,
                        foreground = if (paused) tokens.textSec else tokens.okFg,
                    )
                } else {
                    StatusChip(
                        text = "未激活",
                        dotColor = tokens.warn,
                        background = tokens.block,
                        foreground = tokens.textSec,
                    )
                }
            }

            VSpace(14)

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "已拦截",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSec,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$totalIntercepted",
                    style = MaterialTheme.typography.displayMedium,
                    color = tokens.text,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "次",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.textSec,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            VSpace(10)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NeutralChip("零联网本地")
                NeutralChip("无障碍极速")
            }

            VSpace(16)

            Row(verticalAlignment = Alignment.CenterVertically) {
                UptimeLabel(connected = connected)
                Spacer(Modifier.width(22.dp))
                Column {
                    Text(
                        text = "累计节省时间",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTer,
                    )
                    Text(
                        text = formatMinutes(savedMinutes),
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.text,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 右侧主操作按钮
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
                .size(58.dp)
                .shadow(if (tokens.isDark) 0.dp else 10.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.primary)
                .clickable { if (connected) onTogglePause() else onActivate() },
            contentAlignment = Alignment.Center,
        ) {
            if (connected) {
                Icon(
                    imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (paused) "恢复" else "暂停",
                    tint = tokens.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            } else {
                Text(
                    text = "激活",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun UptimeLabel(connected: Boolean) {
    val tokens = LocalFlitrTokens.current
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val since = com.aios.flitr.engine.FlitrRuntime.serviceSince

    LaunchedEffect(connected) {
        if (!connected) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            val start = since.value
            elapsedMs = if (start > 0) System.currentTimeMillis() - start else 0L
            delay(1_000)
        }
    }

    Column {
        Text(
            text = "持续守护",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTer,
        )
        Text(
            text = formatDuration(elapsedMs),
            style = MaterialTheme.typography.titleSmall,
            color = tokens.text,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    icon: String,
    title: String,
    value: Int,
    caption: String,
    trend: List<Float>,
) {
    val tokens = LocalFlitrTokens.current
    BentoCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(5.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.text,
            )
        }
        VSpace(8)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$value",
                style = MaterialTheme.typography.headlineLarge,
                color = tokens.text,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "次",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTer,
        )
        VSpace(8)
        Sparkline(values = trend, height = 40.dp)
    }
}

@Composable
private fun VideoHostCard(
    prefs: FlitrPrefs,
    onToggleVideo: (Boolean) -> Unit,
    onManage: () -> Unit,
) {
    val tokens = LocalFlitrTokens.current
    BentoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("红", color = tokens.onPrimary, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "红果短剧 · 自动划广",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.text,
                )
                Text(
                    text = "无倒计时秒划 · 读秒播完划走",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSec,
                )
            }
            com.aios.flitr.ui.components.FlitrSwitch(
                checked = prefs.videoEnabled,
                onCheckedChange = onToggleVideo,
            )
        }
        VSpace(12)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { onManage() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "管理 ${prefs.videoPackages.size} 个托管应用",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text("›", style = MaterialTheme.typography.labelLarge, color = tokens.primary)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分钟"
