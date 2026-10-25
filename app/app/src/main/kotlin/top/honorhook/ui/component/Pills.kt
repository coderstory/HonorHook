package top.honorhook.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 状态圆点色（iOS 系绿/琥珀；小尺寸圆点深浅色主题下均可读，不走 Miuix 色板）。 */
val StatusOk = Color(0xFF34C759)
val StatusWarn = Color(0xFFFF9500)

/**
 * 状态 chip：圆点 + 短标签的浅底胶囊（设计稿语言）。
 * 工具箱状态卡与功能页总览行共用；点色即语义（绿=正常，琥珀=告警）。
 */
@Composable
fun StatusChip(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (ok) StatusOk else StatusWarn, RoundedCornerShape(4.dp)))
        Text(label, fontSize = 11.sp, color = colorScheme.onSurface)
    }
}
