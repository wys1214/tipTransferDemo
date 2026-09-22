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
        displayName = "스타 벌룬",
        description = "별을 담아 보내는 대표 후원 재화",
        primary = Color(0xFFE83E78),
        dark = Color(0xFF4A1830),
        sparkle = Color(0xFFFFD76A),
        token = "★",
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
    FLOWER(
        displayName = "꽃",
        description = "꽃잎을 피워 보내는 테마",
        primary = Color(0xFFC026D3),
        dark = Color(0xFF3B1451),
        sparkle = Color(0xFFF5D0FE),
        token = "✿",
    ),
    HEART_BALLOON(
        displayName = "하트 풍선",
        description = "하트 풍선을 띄워 보내는 테마",
        primary = Color(0xFFEC4899),
        dark = Color(0xFF4A1732),
        sparkle = Color(0xFFFBCFE8),
        token = "♥",
    ),
    PAPER_PLANE(
        displayName = "종이비행기",
        description = "종이비행기를 날려 보내는 테마",
        primary = Color(0xFF0284C7),
        dark = Color(0xFF12304A),
        sparkle = Color(0xFFBAE6FD),
        token = "➤",
    ),
}

/**
 * 사용자에게 노출하는 실제 재화 목록.
 * PURPLE은 이전 설치/Firestore 데이터 호환을 위해 enum에는 남기되 새 선택 화면에서는 제거한다.
 */
val selectableVisualThemes = listOf(
    VisualTheme.PURPLE,
    VisualTheme.GOLD,
    VisualTheme.ROCKET,
    VisualTheme.FLOWER,
    VisualTheme.HEART_BALLOON,
    VisualTheme.PAPER_PLANE,
)
