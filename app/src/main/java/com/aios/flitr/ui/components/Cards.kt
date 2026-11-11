package com.aios.flitr.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aios.flitr.ui.theme.LocalFlitrTokens

/** Bento 卡片: Flitr 的基础表面单位。26dp 圆角 + 极轻投影。 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(18.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val tokens = LocalFlitrTokens.current
    val shape = RoundedCornerShape(26.dp)
    val base = modifier
        .fillMaxWidth()
        .shadow(if (tokens.isDark) 0.dp else 10.dp, shape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
        .clip(shape)
        .background(color ?: tokens.card)
    Column(
        modifier = if (onClick != null) base.clickable { onClick() } else base,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** 小圆点 + 文本的状态徽标, 对应设计稿的 "● 运行中"。 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    background: Color? = null,
    foreground: Color? = null,
) {
    val tokens = LocalFlitrTokens.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background ?: tokens.okBg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dotColor != null) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = foreground ?: tokens.okFg,
        )
    }
}

/** 中性灰徽标, 用于 "零联网本地" / "无障碍极速" 这类能力标签。 */
@Composable
fun NeutralChip(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalFlitrTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(tokens.block)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tokens.textSec,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 分组小标题: 图标 + 标题 + 可选右侧动作。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tokens = LocalFlitrTokens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Text(icon, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            color = tokens.textSec,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 页面大标题栏: 左侧标题, 右侧圆形图标按钮。 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val tokens = LocalFlitrTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = tokens.text,
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** 细分隔线 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    val tokens = LocalFlitrTokens.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(tokens.hairline),
    )
}
