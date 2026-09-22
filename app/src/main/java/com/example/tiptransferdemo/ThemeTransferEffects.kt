package com.yunsi.tiptransferdemo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition

/** 선택한 UI 테마가 전송 자체의 모션 언어를 바꾸는 전용 효과. */
@Composable
fun ThemeSendingEffect(pack: UiThemePack, progress: Float, modifier: Modifier = Modifier) {
    val travelDistance = with(LocalDensity.current) { 360.dp.toPx() }
    val composition = rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.premium_send)).value
    val glowComposition = rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.transfer_glow)).value
    Box(modifier, contentAlignment = Alignment.Center) {
        LottieAnimation(
            composition = composition,
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxSize(),
        )
        // 검증된 기본 Lottie 광원은 재화와 함께 이동해 로딩 상황에서도 효과가 보인다.
        LottieAnimation(
            composition = glowComposition,
            progress = { (progress.coerceIn(0f, 1f) * .72f).coerceIn(0f, 1f) },
            modifier = Modifier.size(190.dp).graphicsLayer {
                val p = progress.coerceIn(0f, 1f)
                val eased = p * p * (3f - 2f * p)
                translationY = -eased * travelDistance
                alpha = ((1f - p) / .14f).coerceIn(0f, 1f)
            },
        )
        Image(
            painter = painterResource(pack.iconRes),
            contentDescription = "${pack.displayName} 전용 재화",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(116.dp).graphicsLayer {
                val p = progress.coerceIn(0f, 1f)
                val eased = p * p * (3f - 2f * p)
                translationY = -eased * travelDistance
                // 화면 바깥으로 충분히 이동한 마지막 구간에서만 부드럽게 사라진다.
                alpha = ((1f - p) / .16f).coerceIn(0f, 1f)
                val pulse = sin(p * PI).toFloat() * .08f
                scaleX = 1f + pulse
                scaleY = 1f + pulse
                rotationZ = sin(p * PI).toFloat() * 5f
            },
        )
    }
}

/** 수신 시 흩어진 빛이 중앙으로 모여 테마 에셋을 완성한다. */
@Composable
fun ThemeReceivingFormation(pack: UiThemePack, eventKey: Long, modifier: Modifier = Modifier) {
    val motion = remember { Animatable(1f) }
    LaunchedEffect(eventKey) {
        if (eventKey <= 0L) return@LaunchedEffect
        motion.snapTo(0f)
        motion.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
    }
    if (eventKey <= 0L) return
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            when (pack) {
                UiThemePack.FESTIVAL -> drawFireworkGather(motion.value, pack)
                UiThemePack.COSMIC -> drawShootingStarFlight(motion.value, pack, reverse = true)
                else -> Unit
            }
        }
        if (pack == UiThemePack.FESTIVAL) {
            val reveal = ((motion.value - .62f) / .38f).coerceIn(0f, 1f)
            Image(
                painter = painterResource(R.drawable.theme_firework_v1),
                contentDescription = "모여 완성된 불꽃",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(180.dp).graphicsLayer {
                    alpha = reveal
                    scaleX = .58f + reveal * .42f
                    scaleY = .58f + reveal * .42f
                },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFireworkSend(progress: Float, pack: UiThemePack) {
    if (progress <= 0f) return
    val center = Offset(size.width * .5f, size.height * .36f)
    val launchEnd = .36f
    if (progress < launchEnd) {
        val t = progress / launchEnd
        val head = Offset(center.x, size.height * .82f + (center.y - size.height * .82f) * t)
        repeat(12) { i ->
            val trail = i / 12f
            drawCircle(
                (if (i % 2 == 0) pack.accent else pack.secondary).copy(alpha = (.72f - trail * .5f) * t),
                3f + (i % 3),
                Offset(head.x + sin(i * 1.8f) * 8f, head.y + trail * 76f),
            )
        }
        drawCircle(Color.White.copy(alpha = .9f), 7f, head)
        return
    }
    val burst = ((progress - launchEnd) / (1f - launchEnd)).coerceIn(0f, 1f)
    val fade = (1f - burst).coerceIn(0f, 1f)
    repeat(30) { i ->
        val angle = i * (PI * 2 / 30).toFloat() + (i % 3) * .08f
        val distance = size.minDimension * (.09f + burst * (.23f + (i % 5) * .028f))
        val point = center + Offset(cos(angle) * distance, sin(angle) * distance + burst * burst * 32f)
        val color = when (i % 3) { 0 -> pack.accent; 1 -> pack.secondary; else -> Color.White }
        drawCircle(color.copy(alpha = fade * .9f), 2.5f + i % 4, point)
    }
    drawCircle(pack.secondary.copy(alpha = fade * .35f), size.minDimension * (.08f + burst * .24f), center, style = Stroke(4f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFireworkGather(progress: Float, pack: UiThemePack) {
    val center = Offset(size.width * .5f, size.height * .5f)
    val gather = (progress / .75f).coerceIn(0f, 1f)
    repeat(30) { i ->
        val angle = i * (PI * 2 / 30).toFloat() + (i % 4) * .1f
        val startDistance = size.minDimension * (.24f + (i % 5) * .035f)
        val distance = startDistance * (1f - gather)
        val point = center + Offset(cos(angle) * distance, sin(angle) * distance)
        val color = when (i % 3) { 0 -> pack.accent; 1 -> pack.secondary; else -> Color.White }
        drawCircle(color.copy(alpha = (1f - gather * .45f).coerceAtLeast(.25f)), 2.5f + i % 4, point)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShootingStarFlight(progress: Float, pack: UiThemePack, reverse: Boolean) {
    if (progress <= 0f) return
    val t = if (reverse) 1f - progress else progress
    val start = Offset(size.width * .78f, size.height * .18f)
    val end = Offset(size.width * .22f, size.height * .78f)
    val center = Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
    val direction = Offset(.707f, -.707f)
    repeat(8) { i ->
        val d = 15f + i * 13f
        drawLine(
            pack.secondary.copy(alpha = (.58f - i * .055f).coerceAtLeast(.08f)),
            center + direction * d,
            center + direction * (d + 18f),
            strokeWidth = (8f - i * .7f).coerceAtLeast(2f),
        )
    }
    drawPath(classicStar(center, 25f), Color(0xFFFFD766))
    drawPath(classicStar(center, 15f), Color.White.copy(alpha = .88f))
}

private fun classicStar(center: Offset, outer: Float): Path = Path().apply {
    repeat(10) { i ->
        val angle = -PI / 2 + i * PI / 5
        val radius = if (i % 2 == 0) outer else outer * .44f
        val point = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
        if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}
