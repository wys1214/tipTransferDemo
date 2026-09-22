package com.yunsi.tiptransferdemo

import kotlin.math.sin
import kotlin.math.PI

internal const val TOKEN_LANDING_MS = 760
internal const val VISIBLE_TOKEN_LIMIT = 12

internal data class TokenPose(val x: Float, val y: Float, val scale: Float, val tilt: Float, val turn: Float)

internal fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Slot coordinates and end pose are shared by the moving and resting model. */
internal fun restingPose(index: Long, flower: Boolean = false): TokenPose {
    val slot = (index % VISIBLE_TOKEN_LIMIT).toInt()
    // 규칙적인 행렬 대신 아래가 넓고 위가 좁은 비대칭 더미를 사용한다.
    val x = floatArrayOf(-.78f, -.30f, .18f, .70f, -.58f, -.08f, .46f, -.38f, .16f, .55f, -.14f, .28f)
    val y = floatArrayOf(-.92f, -.96f, -.90f, -.95f, -.67f, -.71f, -.64f, -.43f, -.47f, -.40f, -.20f, -.17f)
    val scale = floatArrayOf(.42f, .45f, .43f, .40f, .39f, .42f, .38f, .36f, .39f, .35f, .34f, .32f)
    val turn = floatArrayOf(-14f, 7f, -5f, 13f, 10f, -11f, 6f, -7f, 12f, -4f, 8f, -9f)
    return TokenPose(x[slot], y[slot], scale[slot], if (flower) 0f else -8f, turn[slot])
}

internal fun arrivalPose(index: Long, fraction: Float, flower: Boolean = false, floating: Boolean = false): TokenPose {
    val p = fraction.coerceIn(0f, 1f)
    val target = restingPose(index, flower)
    if (p >= 1f) return target
    if (p < .38f) {
        val t = smoothStep(p / .38f)
        return TokenPose(if (floating) sin(t * PI).toFloat() * .13f else 0f,
            1.85f + (.22f - 1.85f) * t, .82f, -12f * (1 - t), 40f * (1 - t))
    }
    if (p < .53f) return TokenPose(0f, .22f, .82f, 0f, 0f)
    val t = smoothStep((p - .53f) / .47f)
    return TokenPose(target.x * t, .22f + (target.y - .22f) * t,
        .82f + (target.scale - .82f) * t, target.tilt * t, target.turn * t)
}
