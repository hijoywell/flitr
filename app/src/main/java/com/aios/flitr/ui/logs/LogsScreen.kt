package com.aios.flitr.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aios.flitr.data.logs.ActionLog
import com.aios.flitr.data.logs.LogKind
import com.aios.flitr.ui.FlitrViewModel
import com.aios.flitr.ui.VSpace
import com.aios.flitr.ui.components.BentoCard
import com.aios.flitr.ui.components.PageHeader
import com.aios.flitr.ui.components.SegmentedChoice
import com.aios.flitr.ui.theme.LocalFlitrTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: String) {
    All("全部"),
    Video("短剧智划"),
    Splash("开屏跳过"),
    Popup("弹窗关闭"),
}

@Composable
fun LogsScreen(vm: FlitrViewModel) {
    val tokens = LocalFlitrTokens.current
    val entries by vm.logEntries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LogFilter.All) }

    val filtered = remember(entries, filter) {
        when (filter) {
            LogFilter.All -> entries
            LogFilter.Video -> entries.filter { it.kindEnum in VIDEO_KINDS }
            LogFilter.Splash -> entries.filter { it.kindEnum == LogKind.SplashSkip }
            LogFilter.Popup -> entries.filter { it.kindEnum == LogKind.PopupClose }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            PageHeader(title = "触发日志") {
                Text(
                    text = "${entries.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSec,
                )
            }
            SegmentedChoice(
                options = LogFilter.entries.toList(),
                selected = filter,
                label = { it.label },
                onSelect = { filter = it },
            )
            VSpace(12)
        }

        if (filtered.isEmpty()) {
            EmptyLogs()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { entry ->
                    LogRow(entry = entry, onMarkFalsePositive = { vm.markFalsePositive(entry.id) })
                }
            }
        }
    }
}

@Composable
private fun EmptyLogs() {
    val tokens = LocalFlitrTokens.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
    ) {
        BentoCard(color = tokens.block) {
            Text(
                text = "还没有触发记录",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.text,
            )
            VSpace(6)
            Text(
                text = "开启无障碍服务后, 每一次自动点击或滑动都会记录在这里。" +
                    "如果你发现某条记录不该发生, 可以直接标记为误触, 后续会生成豁免。",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
            )
        }
    }
}

@Composable
private fun LogRow(entry: ActionLog, onMarkFalsePositive: () -> Unit) {
    val tokens = LocalFlitrTokens.current
    val kind = entry.kindEnum
    val accent = when (kind) {
        LogKind.SplashSkip -> tokens.primary
        LogKind.PopupClose -> tokens.primary
        LogKind.VideoNoCountdownSwipe -> tokens.primary
        LogKind.VideoCountdownSwipe -> tokens.warn
        LogKind.VideoClose -> tokens.warn
        LogKind.ContinueWatching -> tokens.warn
        LogKind.Guard -> tokens.okFg
        LogKind.Manual -> tokens.textSec
        LogKind.Error -> tokens.danger
    }

    BentoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.appName.take(1),
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.appName,
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.text,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                }
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSec,
                )
            }
            Text(
                text = timeOf(entry.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTer,
            )
        }

        if (entry.detail.isNotBlank() || entry.elapsedMs > 0) {
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTer,
                    modifier = Modifier.weight(1f),
                )
                if (entry.elapsedMs > 0) {
                    Text(
                        text = "耗时 ${entry.elapsedMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
        }

        VSpace(6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.falsePositive) {
                Text(
                    text = "已标记误触 · 将生成豁免",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.danger,
                )
            } else {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "标记误触",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onMarkFalsePositive() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private val VIDEO_KINDS = setOf(
    LogKind.VideoNoCountdownSwipe,
    LogKind.VideoCountdownSwipe,
    LogKind.VideoClose,
    LogKind.ContinueWatching,
)

private fun timeOf(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
