package com.aios.flitr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aios.flitr.ui.theme.LocalFlitrTokens

/**
 * 面积折线图。
 *
 * 设计稿里的曲线不是装饰: 它让「近 7 日趋势」在一张小卡片里可读。
 * 这里用 Canvas 手绘而不是引图表库 —— 只有一条线, 没必要为它增加一个重依赖。
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    color: Color? = null,
) {
    val tokens = LocalFlitrTokens.current
    val lineColor = color ?: tokens.primary
    val data = if (values.size < 2) listOf(0.15f, 0.3f, 0.22f, 0.5f, 0.38f, 0.62f, 0.55f) else values
    val maxV = data.maxOrNull() ?: 1f
    val minV = data.minOrNull() ?: 0f
    val range = (maxV - minV).takeIf { it > 0.0001f } ?: 1f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val w = size.width
        val h = size.height
        val stepX = if (data.size > 1) w / (data.size - 1) else w

        fun yOf(v: Float): Float {
            val norm = (v - minV) / range
            // 上下留 12% 余量, 避免折线贴边
            return h * (0.88f - norm * 0.76f)
        }

        val line = Path()
        val fill = Path()
        data.forEachIndexed { index, v ->
            val x = stepX * index
            val y = yOf(v)
            if (index == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                val prevX = stepX * (index - 1)
                val prevY = yOf(data[index - 1])
                val midX = (prevX + x) / 2f
                val midY = (prevY + y) / 2f
                line.quadraticTo(midX, prevY, midX, midY)
                line.quadraticTo(midX, y, x, y)
                fill.quadraticTo(midX, prevY, midX, midY)
                fill.quadraticTo(midX, y, x, y)
            }
        }
        fill.lineTo(w, h)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0.02f)),
            ),
        )
        drawPath(
            path = line,
            color = lineColor,
            style = Stroke(width = 2.2f * density),
        )
    }
}

/** 数值大字 + 单位 + 说明的小排版单元。 */
@Composable
fun BigStat(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val tokens = LocalFlitrTokens.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                color = valueColor ?: tokens.text,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSec,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textTer,
        )
    }
}
