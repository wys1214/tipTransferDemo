package com.yunsi.tiptransferdemo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty

/** 체크나 성공 배지 없이 테마 색상의 팡파레만 여러 지점에서 터뜨린다. */
@Composable
fun ThemeReceiveCelebration(pack: UiThemePack, eventKey: Long, modifier: Modifier = Modifier) {
    if (eventKey <= 0L) return

    val composition = rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.premium_fanfare_external),
    ).value
    val timeline = remember { Animatable(1f) }
    val dynamicProperties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.COLOR, pack.accent.toArgb(), "cannon (small - left)", "**"),
        rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, pack.accent.toArgb(), "cannon (small - left)", "**"),
        rememberLottieDynamicProperty(LottieProperty.COLOR, pack.secondary.toArgb(), "cannon (small - right)", "**"),
        rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, pack.secondary.toArgb(), "cannon (small - right)", "**"),
    )

    LaunchedEffect(eventKey, composition) {
        if (composition == null) return@LaunchedEffect
        timeline.snapTo(0f)
        timeline.animateTo(1f, tween(durationMillis = 2_450, easing = FastOutSlowInEasing))
    }

    LottieAnimation(
        composition = composition,
        progress = { timeline.value },
        dynamicProperties = dynamicProperties,
        modifier = modifier,
    )
}
