package com.yunsi.tiptransferdemo

import androidx.compose.ui.graphics.Color

enum class VisualTheme(
    val displayName: String,
    val description: String,
    val primary: Color,
    val dark: Color,
    val sparkle: Color,
    val token: String,
) {
    PURPLE(
        displayName = "기본 보라",
        description = "깔끔한 디지털 재화 테마",
        primary = Color(0xFF4F46E5),
        dark = Color(0xFF171B3A),
        sparkle = Color(0xFFFDE68A),
        token = "●",
    ),
    GOLD(
        displayName = "골드 코인",
        description = "보상처럼 반짝이는 코인 테마",
        primary = Color(0xFFD97706),
        dark = Color(0xFF3B2A12),
        sparkle = Color(0xFFFDE68A),
        token = "●",
    ),
    ROCKET(
        displayName = "로켓 배송",
        description = "로켓으로 빠르게 전달하는 테마",
        primary = Color(0xFFDC2626),
        dark = Color(0xFF25183C),
        sparkle = Color(0xFFFBBF24),
        token = "🚀",
    ),
    PARTICLE(
        displayName = "파티클",
        description = "빛 조각이 퍼지는 축하 테마",
        primary = Color(0xFF0F766E),
        dark = Color(0xFF123331),
        sparkle = Color(0xFF67E8F9),
        token = "✦",
    ),
}
