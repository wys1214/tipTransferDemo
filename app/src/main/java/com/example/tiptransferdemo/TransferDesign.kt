package com.yunsi.tiptransferdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** App chrome stays stable when a user changes the collectible. */
object TransferPalette {
    val Background = Color(0xFFF4F6F9)
    val Ink = Color(0xFF191F28)
    val Muted = Color(0xFF6B7684)
    val Blue = Color(0xFF2868F0)
    val Line = Color(0xFFE8EDF3)
    val Green = Color(0xFF167D5A)
}

fun won(amount: Long) = String.format(Locale.KOREA, "%,d원", amount)

@Composable
fun TransferHeader(title: String, subtitle: String, complete: Boolean = false) {
    val pack = LocalUiThemePack.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = pack.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = pack.fontFamily)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 13.sp, color = pack.muted)
        }
        Spacer(Modifier.width(12.dp))
        Text(if (complete) "완료" else "연결됨", color = TransferPalette.Green,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(Color(0xFFE8F5EF), RoundedCornerShape(50)).padding(10.dp, 7.dp))
    }
}

@Composable
fun TransferAmountCard(label: String, amount: Long, count: Long) {
    val pack = LocalUiThemePack.current
    Surface(
        shape = RoundedCornerShape(pack.corner.dp),
        color = pack.surface,
        modifier = Modifier.fillMaxWidth(),
        border = if (pack == UiThemePack.ARCADE) BorderStroke(2.dp, pack.accent.copy(alpha = .72f)) else null,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = pack.muted, fontSize = 13.sp)
                Text("${count}회", color = pack.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(won(amount), color = pack.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp, fontFamily = pack.fontFamily)
        }
    }
}
