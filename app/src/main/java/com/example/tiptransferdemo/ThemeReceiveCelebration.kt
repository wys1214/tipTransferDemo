package com.yunsi.tiptransferdemo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 도착 섬광부터 리본과 테마 조각까지 한 타임라인으로 이어지는 수신 팡파레. */
@Composable
private fun LegacyCanvasReceiveCelebration(pack: UiThemePack, eventKey: Long, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(eventKey) {
        if (eventKey <= 0L) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1_650, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        if (eventKey <= 0L || progress.value >= 1f) return@Canvas
        val whole = progress.value
        val center = Offset(size.width * .5f, size.height * .42f)
        val primary = pack.accent
        val secondary = pack.secondary

        // 1. 재화가 도착하는 순간 짧고 선명한 렌즈 플레어가 열린다.
        val flash = motionPhase(whole, 0f, .20f)
        val flashFade = (1f - flash).coerceIn(0f, 1f)
        drawCircle(Color.White.copy(alpha = flashFade * .72f), 18f + flash * 86f, center)
        drawLine(primary.copy(alpha = flashFade * .85f), center - Offset(110f * flash, 0f), center + Offset(110f * flash, 0f), 8f, StrokeCap.Round)
        drawLine(secondary.copy(alpha = flashFade * .72f), center - Offset(0f, 82f * flash), center + Offset(0f, 82f * flash), 5f, StrokeCap.Round)

        // 2. 하나의 중심 충격파가 열리고, 좌우의 작은 팡파레가 시간차로 응답한다.
        val impact = motionPhase(whole, .06f, .55f)
        val impactFade = (1f - impact).coerceIn(0f, 1f)
        drawCircle(primary.copy(alpha = impactFade * .68f), size.minDimension * (.05f + impact * .38f), center, style = Stroke(11f))
        drawCircle(secondary.copy(alpha = impactFade * .52f), size.minDimension * (.03f + impact * .27f), center, style = Stroke(5f))
        repeat(20) { index ->
            val angle = index * (PI * 2 / 20).toFloat()
            val inner = size.minDimension * (.08f + impact * .10f)
            val outer = size.minDimension * (.17f + impact * .29f)
            drawLine(
                (if (index % 2 == 0) primary else secondary).copy(alpha = impactFade * .9f),
                center + Offset(cos(angle) * inner, sin(angle) * inner),
                center + Offset(cos(angle) * outer, sin(angle) * outer),
                if (index % 3 == 0) 8f else 5f,
                StrokeCap.Round,
            )
        }
        listOf(Offset(.23f, .31f) to .18f, Offset(.78f, .35f) to .30f).forEachIndexed { side, (normalized, delay) ->
            val p = motionPhase(whole, delay, delay + .38f)
            val fade = 1f - p
            val burstCenter = Offset(size.width * normalized.x, size.height * normalized.y)
            drawCircle((if (side == 0) secondary else primary).copy(alpha = fade * .62f), 18f + p * 92f, burstCenter, style = Stroke(7f))
            repeat(10) { ray ->
                val angle = ray * (PI * 2 / 10).toFloat()
                drawLine(
                    (if ((ray + side) % 2 == 0) primary else secondary).copy(alpha = fade * .72f),
                    burstCenter + Offset(cos(angle) * 14f, sin(angle) * 14f),
                    burstCenter + Offset(cos(angle) * (34f + p * 76f), sin(angle) * (34f + p * 76f)),
                    5f,
                    StrokeCap.Round,
                )
            }
        }

        // 3. 좌우 하단에서 시작한 리본이 중앙 무대를 감싸며 위로 펼쳐진다.
        val fanfare = motionPhase(whole, .16f, .76f)
        val fanfareFade = (1f - motionPhase(whole, .64f, 1f)).coerceIn(0f, 1f)
        repeat(8) { ribbon ->
            val fromLeft = ribbon % 2 == 0
            val start = Offset(if (fromLeft) -18f else size.width + 18f, size.height * (.72f + (ribbon % 4) * .045f))
            val end = Offset(size.width * (.28f + (ribbon % 4) * .15f), size.height * (.16f + (ribbon % 3) * .10f))
            val animatedEnd = start + (end - start) * fanfare
            val path = Path().apply {
                moveTo(start.x, start.y)
                cubicTo(
                    size.width * (if (fromLeft) .18f else .82f), size.height * .58f,
                    size.width * (if (fromLeft) .34f else .66f), size.height * .25f,
                    animatedEnd.x, animatedEnd.y,
                )
            }
            drawPath(path, (if (ribbon % 3 == 0) secondary else primary).copy(alpha = fanfareFade * .82f), style = Stroke(7f, cap = StrokeCap.Round))
        }

        // 4. 마지막에는 테마 조각만 천천히 내려오며 다음 재화가 들어올 공간을 비운다.
        val settle = motionPhase(whole, .28f, 1f)
        repeat(34) { index ->
            val lane = ((index * 37) % 100) / 100f
            val x = size.width * (.05f + lane * .9f) + sin(index * 1.7f + settle * PI * 2).toFloat() * 16f
            val launchY = size.height * (.10f + (index % 5) * .08f)
            val y = launchY + settle * size.height * (.46f + (index % 4) * .08f)
            val alpha = (1f - motionPhase(whole, .78f, 1f)) * (.58f + (index % 3) * .14f)
            val color = when (index % 3) { 0 -> primary; 1 -> secondary; else -> Color.White }
            val point = Offset(x, y)
            when (pack) {
                UiThemePack.BLOOM, UiThemePack.ROYAL_GIFT -> drawOval(color.copy(alpha = alpha), point, androidx.compose.ui.geometry.Size(10f, 18f))
                UiThemePack.SNOW_GLOBE -> drawCircle(Color.White.copy(alpha = alpha), 4f + index % 3, point)
                UiThemePack.HEART_PARTY, UiThemePack.STAR_LIVE -> drawPath(sparkPath(point, 6f + index % 4), color.copy(alpha = alpha))
                else -> drawCircle(color.copy(alpha = alpha), 4f + index % 4, point)
            }
        }
    }
}

private fun motionPhase(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun sparkPath(center: Offset, radius: Float): Path = Path().apply {
    moveTo(center.x, center.y - radius)
    lineTo(center.x + radius * .35f, center.y - radius * .25f)
    lineTo(center.x + radius, center.y)
    lineTo(center.x + radius * .35f, center.y + radius * .25f)
    lineTo(center.x, center.y + radius)
    lineTo(center.x - radius * .35f, center.y + radius * .25f)
    lineTo(center.x - radius, center.y)
    lineTo(center.x - radius * .35f, center.y - radius * .25f)
    close()
}
