package com.yunsi.tiptransferdemo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 모든 주요 행동 버튼에 공통으로 적용하는 밝은 표면 스타일. */
@Composable
fun ThemeButton(
    theme: VisualTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val pack = LocalUiThemePack.current
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(pack.corner.coerceAtMost(24).dp),
        border = BorderStroke(
            if (pack == UiThemePack.ARCADE) 2.dp else 1.dp,
            pack.accent.copy(alpha = if (enabled) .82f else .18f),
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = pack.surface,
            contentColor = pack.accent,
            disabledContainerColor = Color(0xFFF1F5F9),
            disabledContentColor = Color(0xFF94A3B8),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        content = content,
    )
}
