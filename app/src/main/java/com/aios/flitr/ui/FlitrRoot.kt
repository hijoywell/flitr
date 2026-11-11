package com.aios.flitr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aios.flitr.ui.dashboard.DashboardScreen
import com.aios.flitr.ui.logs.LogsScreen
import com.aios.flitr.ui.rules.RulesScreen
import com.aios.flitr.ui.settings.SettingsScreen
import com.aios.flitr.ui.theme.LocalFlitrTokens

enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("仪表盘", Icons.Rounded.Home),
    Rules("场景规则", Icons.Rounded.Tune),
    Logs("触发日志", Icons.Rounded.Receipt),
    Settings("工具设置", Icons.Rounded.Settings),
}

@Composable
fun FlitrRoot(vm: FlitrViewModel) {
    var tab by remember { mutableStateOf(Tab.Dashboard) }
    val tokens = LocalFlitrTokens.current

    Scaffold(
        containerColor = tokens.page,
        bottomBar = { BottomDock(selected = tab, onSelect = { tab = it }) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                Tab.Dashboard -> DashboardScreen(vm = vm, onNavigate = { tab = it })
                Tab.Rules -> RulesScreen(vm = vm)
                Tab.Logs -> LogsScreen(vm = vm)
                Tab.Settings -> SettingsScreen(vm = vm)
            }
        }
    }
}

/** 悬浮胶囊式底部导航, 对应设计稿的浮动 dock。 */
@Composable
private fun BottomDock(selected: Tab, onSelect: (Tab) -> Unit) {
    val tokens = LocalFlitrTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .shadow(if (tokens.isDark) 0.dp else 14.dp, RoundedCornerShape(999.dp))
                .clip(RoundedCornerShape(999.dp))
                .background(tokens.card)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tab.entries.forEach { item ->
                val isSelected = item == selected
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelect(item) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (isSelected) tokens.primary else tokens.textTer,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) tokens.primary else tokens.textTer,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** 供各页面复用: 统一的页面内边距 */
val PagePadding = Modifier.padding(horizontal = 18.dp)

@Composable
fun VSpace(dp: Int) {
    Spacer(Modifier.height(dp.dp))
}

@Composable
fun HSpace(dp: Int) {
    Spacer(Modifier.width(dp.dp))
}
