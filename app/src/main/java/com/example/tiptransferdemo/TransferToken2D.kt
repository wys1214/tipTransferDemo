package com.yunsi.tiptransferdemo

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
private fun TransferTokenAsset(theme: VisualTheme, modifier: Modifier = Modifier) {
    val drawable = when (theme) {
        VisualTheme.PURPLE -> R.drawable.token_star_balloon_v1
        VisualTheme.GOLD -> R.drawable.coin_premium_3d_v1
        VisualTheme.ROCKET -> R.drawable.token_rocket_v1
        VisualTheme.FLOWER -> R.drawable.token_flower_single_v1
        VisualTheme.HEART_BALLOON -> R.drawable.token_heart_balloon_v1
        VisualTheme.PAPER_PLANE -> R.drawable.token_paper_plane_v1
    }
    Image(painterResource(drawable), theme.displayName, modifier, contentScale = ContentScale.Fit)
}

@Composable
fun SendingTokenAssetScene(theme: VisualTheme, progress: Float, dragFraction: Float, modifier: Modifier = Modifier) {
    val t = progress.coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFFF9FBFF), Color(0xFFEDF2FA)))),
        contentAlignment = Alignment.Center,
    ) {
        val travel = constraints.maxHeight * 1.18f
        key(theme) {
            TransferTokenAsset(theme, Modifier.size(118.dp).graphicsLayer {
                translationY = if (progress > 0f) -travel * t * t else -travel * .23f * dragFraction
                translationX = if (theme == VisualTheme.PAPER_PLANE) travel * .16f * t else 0f
                rotationZ = when (theme) {
                    VisualTheme.GOLD -> 360f * t
                    VisualTheme.FLOWER -> 110f * t
                    VisualTheme.PAPER_PLANE -> -14f * t
                    else -> 8f * t
                }
                scaleX = 1f - .16f * t
                scaleY = scaleX
                alpha = if (t > .82f) ((1f - t) / .18f).coerceIn(0f, 1f) else 1f
            })
        }
    }
}

@Composable
fun TokenAssetSelectionScene(themes: List<VisualTheme>, selectedTheme: VisualTheme, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFFF9FBFF), Color(0xFFF0F4FA)))),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        themes.forEach { theme ->
            key(theme) {
                TransferTokenAsset(
                    theme,
                    Modifier.size(if (theme == selectedTheme) 58.dp else 48.dp)
                        .alpha(if (theme == selectedTheme) 1f else .68f)
                        .graphicsLayer { rotationZ = if (theme == selectedTheme) -3f else 0f },
                )
            }
        }
    }
}

private data class AssetToken(val index: Long, val theme: VisualTheme, val start: Long)

@Composable
fun ReceivingTokenAssetScene(theme: VisualTheme, count: Long, modifier: Modifier = Modifier) {
    val pack = LocalUiThemePack.current
    val tokens = remember { mutableStateListOf<AssetToken>() }
    var observedCount by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val view = LocalView.current
    LaunchedEffect(count) {
        if (count < observedCount) { tokens.clear(); observedCount = 0L }
        if (count > observedCount) {
            val start = SystemClock.uptimeMillis()
            val first = maxOf(observedCount, count - VISIBLE_TOKEN_LIMIT)
            for (index in first until count) tokens.add(AssetToken(index, theme, start + (index - first) * 90L))
            observedCount = count
            while (tokens.size > VISIBLE_TOKEN_LIMIT * 2) tokens.removeAt(0)
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }
    LaunchedEffect(tokens.lastOrNull()?.index) {
        while (tokens.any { now < it.start + TOKEN_LANDING_MS }) withFrameNanos { now = SystemClock.uptimeMillis() }
        while (tokens.size > VISIBLE_TOKEN_LIMIT) tokens.removeAt(0)
    }
    BoxWithConstraints(
        modifier.background(
            Brush.radialGradient(
                listOf(pack.accent.copy(alpha = .16f), Color.Transparent),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        tokens.forEach { token -> key(token.index) {
            if (now >= token.start) {
                val replaced = tokens.any { it.index > token.index && it.index % VISIBLE_TOKEN_LIMIT == token.index % VISIBLE_TOKEN_LIMIT && now >= it.start + TOKEN_LANDING_MS }
                if (!replaced) {
                    val p = ((now - token.start).toFloat() / TOKEN_LANDING_MS).coerceIn(0f, 1f)
                    val pose = arrivalPose(token.index, p, token.theme == VisualTheme.FLOWER, token.theme == VisualTheme.HEART_BALLOON)
                    TransferTokenAsset(token.theme, Modifier.size(94.dp).graphicsLayer {
                        translationX = pose.x * 104.dp.toPx()
                        translationY = -pose.y * 104.dp.toPx()
                        scaleX = pose.scale
                        scaleY = pose.scale
                        rotationX = pose.tilt
                        rotationZ = pose.turn
                        alpha = if (p < .08f) p / .08f else 1f
                    })
                }
            }
        } }
    }
}
