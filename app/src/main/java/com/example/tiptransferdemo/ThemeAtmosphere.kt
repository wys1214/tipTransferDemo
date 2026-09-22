package com.yunsi.tiptransferdemo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val UiThemePack.fontFamily: FontFamily
    get() = when (this) {
        UiThemePack.TROPHY, UiThemePack.ROYAL_GIFT, UiThemePack.SNOW_GLOBE -> FontFamily.Serif
        UiThemePack.ARCADE, UiThemePack.COSMIC, UiThemePack.CRYSTAL -> FontFamily.Monospace
        UiThemePack.BLOOM, UiThemePack.HEART_PARTY -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

/** 테마마다 다른 움직이는 배경 무대. 단순한 색상 교체로 보이지 않도록 패턴과 운동을 분리한다. */
fun Modifier.themeAtmosphere(pack: UiThemePack): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "${pack.name}Atmosphere")
    val progress by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "${pack.name}BackgroundProgress",
    )
    val asset = painterResource(pack.iconRes)
    drawBehind {
        drawRect(Brush.verticalGradient(listOf(pack.backgroundTop, pack.backgroundBottom)))
        when (pack) {
            UiThemePack.STAR_LIVE -> drawAssetFloaters(asset, progress, pack)
            UiThemePack.TROPHY -> { drawSpotlights(progress, pack); drawCornerEmblem(asset, .12f) }
            UiThemePack.BLOOM -> { drawPetals(progress, pack); drawCornerEmblem(asset, .10f) }
            UiThemePack.HEART_PARTY -> { drawHeartBubbles(progress, pack); drawAssetFloaters(asset, progress, pack) }
            UiThemePack.COSMIC -> drawShootingStars(asset, progress)
            UiThemePack.CRYSTAL -> drawCrystalShimmer(asset, progress, pack)
            UiThemePack.ARCADE -> drawNeonFrame(progress, pack)
            UiThemePack.SNOW_GLOBE -> drawSnow(progress, pack)
            UiThemePack.ROYAL_GIFT -> drawRoyalGift(asset, progress, pack)
            UiThemePack.FESTIVAL -> drawFireworks(progress, pack)
        }
    }
}

private fun DrawScope.drawAssetFloaters(asset: Painter, progress: Float, pack: UiThemePack) {
    val count = if (pack == UiThemePack.STAR_LIVE) 7 else 4
    repeat(count) { index ->
        val cycle = (progress + index * (1f / count)) % 1f
        val edge = size.minDimension * (if (pack == UiThemePack.STAR_LIVE) .11f else .085f)
        val x = size.width * (.08f + (index % 4) * .28f) + sin((cycle + index) * PI * 2).toFloat() * 18f
        val y = size.height * (1.12f - cycle * 1.32f)
        withTransform({
            translate(x - edge / 2f, y - edge / 2f)
            rotate((cycle * 18f - 9f) * if (index % 2 == 0) 1f else -1f, Offset(edge / 2f, edge / 2f))
        }) {
            with(asset) {
                draw(
                    size = androidx.compose.ui.geometry.Size(edge, edge),
                    alpha = if (pack == UiThemePack.STAR_LIVE) .22f else .12f,
                )
            }
        }
    }
}

private fun DrawScope.drawCornerEmblem(asset: Painter, alpha: Float) {
    val edge = size.minDimension * .28f
    withTransform({ translate(size.width - edge * .72f, size.height * .08f); rotate(9f) }) {
        with(asset) { draw(androidx.compose.ui.geometry.Size(edge, edge), alpha = alpha) }
    }
}

/** 여러 별똥별이 서로 다른 높이와 시간차로 오른쪽 위에서 왼쪽 아래를 가로지른다. */
private fun DrawScope.drawShootingStars(asset: Painter, progress: Float) {
    repeat(5) { index ->
        val speed = 1.18f + (index % 3) * .13f
        val cycle = (progress * speed + index * .21f) % 1f
        val edge = size.minDimension * (.14f + (index % 3) * .045f)
        val laneOffset = (index - 2) * size.width * .12f
        val x = size.width + edge + laneOffset - (size.width + edge * 2.4f) * cycle
        val startY = -edge * (1.15f + (index % 2) * .8f)
        val y = startY + size.height * (.82f + (index % 3) * .12f) * cycle
        withTransform({ translate(x, y) }) {
            with(asset) {
                draw(
                    androidx.compose.ui.geometry.Size(edge, edge),
                    alpha = sin(cycle * PI).toFloat().coerceAtLeast(0f) * (.18f + (index % 2) * .07f),
                )
            }
        }
    }
}

/** 크리스털은 움직이지 않고, 프리즘 주위의 빛만 맥박치듯 반짝인다. */
private fun DrawScope.drawCrystalShimmer(asset: Painter, progress: Float, pack: UiThemePack) {
    val edge = size.minDimension * .42f
    val pulse = (.08f + (sin(progress * PI * 2).toFloat() + 1f) * .055f)
    withTransform({ translate(size.width - edge * .78f, size.height * .12f) }) {
        with(asset) { draw(androidx.compose.ui.geometry.Size(edge, edge), alpha = .16f) }
    }
    repeat(9) { i ->
        val phase = (progress + i * .137f) % 1f
        val center = Offset(size.width * (.09f + (i % 4) * .27f), size.height * (.14f + (i / 4) * .28f))
        val ray = 5f + 12f * sin(phase * PI).toFloat().coerceAtLeast(0f)
        drawLine(Color.White.copy(alpha = pulse + .12f), center - Offset(ray, 0f), center + Offset(ray, 0f), 2f)
        drawLine(pack.secondary.copy(alpha = pulse + .08f), center - Offset(0f, ray), center + Offset(0f, ray), 2f)
    }
}

/** 아케이드는 배경 오브젝트 대신 화면과 패널 가장자리를 네온사인처럼 점등한다. */
private fun DrawScope.drawNeonFrame(progress: Float, pack: UiThemePack) {
    val pulse = .28f + (sin(progress * PI * 2).toFloat() + 1f) * .18f
    repeat(3) { layer ->
        val inset = 5f + layer * 5f
        val color = if (layer % 2 == 0) pack.accent else pack.secondary
        drawRoundRect(
            color.copy(alpha = pulse / (layer + 1)),
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f),
            style = Stroke(2f + (2 - layer)),
        )
    }
}

/** 로열 기프트는 떠다니지 않고 배경 하단에 선물 상자가 놓인 장면으로 고정한다. */
private fun DrawScope.drawRoyalGift(asset: Painter, progress: Float, pack: UiThemePack) {
    val edge = size.minDimension * .46f
    withTransform({ translate(size.width - edge * .82f, size.height - edge * 1.08f); rotate(-4f) }) {
        with(asset) { draw(androidx.compose.ui.geometry.Size(edge, edge), alpha = .14f) }
    }
    val glow = .05f + (sin(progress * PI * 2).toFloat() + 1f) * .035f
    drawCircle(pack.secondary.copy(alpha = glow), edge * .55f, Offset(size.width * .82f, size.height * .86f))
}

private fun DrawScope.drawStarBalloons(progress: Float, pack: UiThemePack) {
    repeat(7) { index ->
        val cycle = (progress + index * .17f) % 1f
        val x = size.width * (.10f + (index % 4) * .26f + sin(index * 1.7).toFloat() * .035f)
        val y = size.height * (1.12f - cycle * 1.35f)
        val radius = size.minDimension * (.026f + (index % 3) * .006f)
        val color = if (index % 2 == 0) pack.accent else pack.secondary
        drawLine(color.copy(alpha = .22f), Offset(x, y + radius), Offset(x + sin(cycle * PI * 2).toFloat() * 9f, y + radius * 2.7f), 1.5f)
        drawPath(starPath(Offset(x, y), radius), color.copy(alpha = .16f + .18f * (1f - cycle)))
        drawPath(starPath(Offset(x - radius * .2f, y - radius * .25f), radius * .24f), Color.White.copy(alpha = .38f))
    }
}

private fun DrawScope.drawSpotlights(progress: Float, pack: UiThemePack) {
    val sweep = sin(progress * PI * 2).toFloat() * size.width * .16f
    drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width * .46f + sweep, size.height); lineTo(size.width * .7f + sweep, size.height); close() }, pack.secondary.copy(alpha = .09f))
    drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width * .54f - sweep, size.height); lineTo(size.width * .3f - sweep, size.height); close() }, pack.accent.copy(alpha = .08f))
    repeat(5) { drawCircle(pack.secondary.copy(alpha = .15f), 3f + it, Offset(size.width * (it + 1) / 6f, size.height * .15f)) }
}

private fun DrawScope.drawPetals(progress: Float, pack: UiThemePack) {
    repeat(18) { i ->
        val p = (progress * 1.3f + i * .091f) % 1f
        val x = size.width * ((i * .31f + sin(p * PI * 2).toFloat() * .08f) % 1f)
        val y = size.height * p
        drawOval(if (i % 2 == 0) pack.accent.copy(alpha = .18f) else pack.secondary.copy(alpha = .18f), Offset(x, y), androidx.compose.ui.geometry.Size(12f, 22f))
    }
}

private fun DrawScope.drawHeartBubbles(progress: Float, pack: UiThemePack) {
    repeat(12) { i ->
        val p = (progress + i * .12f) % 1f
        val center = Offset(size.width * (.08f + (i % 5) * .22f), size.height * (1.05f - p * 1.18f))
        val r = 8f + (i % 3) * 5f
        drawCircle((if (i % 2 == 0) pack.accent else pack.secondary).copy(alpha = .15f), r, center)
        drawCircle(Color.White.copy(alpha = .18f), r * .22f, center - Offset(r * .25f, r * .25f))
    }
}

private fun DrawScope.drawGalaxy(progress: Float, pack: UiThemePack) {
    repeat(32) { i ->
        val angle = i * .73f + progress * (if (i % 2 == 0) 1f else -1f)
        val r = size.minDimension * (.1f + (i % 8) * .055f)
        drawCircle((if (i % 3 == 0) pack.secondary else Color.White).copy(alpha = .18f + (i % 4) * .07f), 1.5f + i % 3, Offset(size.width / 2 + cos(angle) * r, size.height * .42f + sin(angle) * r))
    }
    drawCircle(pack.accent.copy(alpha = .08f), size.minDimension * .28f, Offset(size.width * .5f, size.height * .42f), style = Stroke(2f))
}

private fun DrawScope.drawCrystals(progress: Float, pack: UiThemePack) {
    repeat(8) { i ->
        val x = size.width * (i + .5f) / 8f
        val y = size.height * (.18f + (i % 3) * .27f)
        val pulse = 14f + sin((progress + i * .13f) * PI * 2).toFloat() * 5f
        val path = Path().apply { moveTo(x, y - pulse); lineTo(x + pulse * .65f, y); lineTo(x, y + pulse); lineTo(x - pulse * .65f, y); close() }
        drawPath(path, (if (i % 2 == 0) pack.accent else pack.secondary).copy(alpha = .15f))
    }
}

private fun DrawScope.drawArcadeGrid(progress: Float, pack: UiThemePack) {
    val gap = 44f
    var x = -gap + progress * gap
    while (x < size.width + gap) { drawLine(pack.accent.copy(alpha = .11f), Offset(x, 0f), Offset(x, size.height), 1f); x += gap }
    var y = -gap + progress * gap
    while (y < size.height + gap) { drawLine(pack.secondary.copy(alpha = .1f), Offset(0f, y), Offset(size.width, y), 1f); y += gap }
}

private fun DrawScope.drawSnow(progress: Float, pack: UiThemePack) {
    repeat(30) { i ->
        val p = (progress * .7f + i * .073f) % 1f
        val x = size.width * ((i * .27f + sin(p * PI * 2).toFloat() * .04f) % 1f)
        drawCircle(Color.White.copy(alpha = .22f + (i % 4) * .08f), 2f + i % 4, Offset(x, size.height * p))
    }
}

private fun DrawScope.drawRibbons(progress: Float, pack: UiThemePack) {
    repeat(3) { i ->
        val y = size.height * (.22f + i * .26f)
        val path = Path().apply {
            moveTo(-30f, y)
            repeat(7) { step -> lineTo(size.width * step / 6f, y + sin(step + progress * PI * 2).toFloat() * (18f + i * 5f)) }
        }
        drawPath(path, (if (i % 2 == 0) pack.accent else pack.secondary).copy(alpha = .12f), style = Stroke(5f))
    }
}

private fun DrawScope.drawFireworks(progress: Float, pack: UiThemePack) {
    val centers = listOf(
        Offset(.14f, .14f), Offset(.50f, .12f), Offset(.84f, .18f),
        Offset(.30f, .38f), Offset(.72f, .43f),
        Offset(.12f, .67f), Offset(.88f, .72f), Offset(.50f, .82f),
    )
    centers.forEachIndexed { burst, normalized ->
        val local = (progress * (1.38f + (burst % 3) * .16f) + burst * .137f) % 1f
        val center = Offset(size.width * normalized.x, size.height * normalized.y)
        val scale = .92f + (burst % 4) * .12f
        repeat(16) { ray ->
            val angle = ray * PI.toFloat() / 8f
            val start = (12f + local * 34f) * scale
            val end = start + 55f * local * scale
            val color = when (burst % 3) { 0 -> pack.accent; 1 -> pack.secondary; else -> Color.White }
            drawLine(
                color.copy(alpha = (1f - local) * .44f),
                center + Offset(cos(angle) * start, sin(angle) * start),
                center + Offset(cos(angle) * end, sin(angle) * end),
                3.4f,
            )
        }
    }
}

private fun starPath(center: Offset, outer: Float): Path = Path().apply {
    repeat(10) { i ->
        val angle = -PI / 2 + i * PI / 5
        val radius = if (i % 2 == 0) outer else outer * .46f
        val point = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
        if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}
