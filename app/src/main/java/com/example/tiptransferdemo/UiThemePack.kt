package com.yunsi.tiptransferdemo

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class UiThemePack(
    val displayName: String,
    val subtitle: String,
    val motif: String,
    val iconRes: Int,
    val accent: Color,
    val secondary: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val corner: Int,
) {
    STAR_LIVE("스타 라이브", "별풍선이 떠오르는 실시간 후원 무대", "★", R.drawable.token_star_balloon_v1, Color(0xFFFF477E), Color(0xFFFFC857), Color(0xFF241335), Color(0xFF5B204D), Color(0xFF351A45), Color.White, Color(0xFFD8BFD8), 26),
    TROPHY("럭셔리 트로피", "금빛 코인과 시상식 스포트라이트", "♛", R.drawable.coin_premium_3d_v1, Color(0xFFD7A632), Color(0xFFFFE7A3), Color(0xFF17130D), Color(0xFF3B2B15), Color(0xFF2A2115), Color(0xFFFFF7E2), Color(0xFFCDBF9B), 18),
    BLOOM("블룸 가든", "한 송이 꽃과 흩날리는 꽃잎", "✿", R.drawable.token_flower_single_v1, Color(0xFFEE6F9E), Color(0xFF82C9A5), Color(0xFFFFF3F7), Color(0xFFE5F6EC), Color(0xFFFFFBFD), Color(0xFF3D2831), Color(0xFF846A75), 32),
    HEART_PARTY("하트 파티", "하트 풍선이 오르는 팝 파티", "♥", R.drawable.token_heart_balloon_v1, Color(0xFFFF4F87), Color(0xFF8B5CF6), Color(0xFFFFE9F1), Color(0xFFF1E9FF), Color.White, Color(0xFF3C2530), Color(0xFF8E6979), 28),
    COSMIC("코스믹 갤럭시", "오른쪽 위에서 떨어지는 선명한 별똥별", "✦", R.drawable.theme_shooting_star_v3, Color(0xFF7C6CFF), Color(0xFF28D7E5), Color(0xFF090B24), Color(0xFF1B1851), Color(0xFF171A3D), Color.White, Color(0xFFDCE5FF), 22),
    CRYSTAL("오로라 크리스털", "빛을 머금고 반짝이는 프리즘", "◆", R.drawable.theme_aurora_crystal_v1, Color(0xFF4FD1C5), Color(0xFFC084FC), Color(0xFF10242B), Color(0xFF28334D), Color(0x332FFFFFF), Color.White, Color(0xFFC8DAE2), 16),
    ARCADE("네온 아케이드", "버튼과 패널을 감싸는 네온사인", "⌁", R.drawable.theme_neon_arcade_v1, Color(0xFF00E5FF), Color(0xFFFF3DCE), Color(0xFF080817), Color(0xFF16103A), Color(0xFF11152B), Color.White, Color(0xFFAEB8D5), 8),
    SNOW_GLOBE("스노 글로브", "눈송이만 내려앉는 겨울 야경", "❄", R.drawable.theme_snowflake_v1, Color(0xFF69B7FF), Color(0xFFE5F5FF), Color(0xFF10243B), Color(0xFF315A7A), Color(0xCCF4FAFF), Color(0xFF173048), Color(0xFF6C8498), 30),
    ROYAL_GIFT("로열 기프트", "왕실 선물 상자와 금빛 장식", "✧", R.drawable.theme_royal_gift_v1, Color(0xFF9B67E8), Color(0xFFE9C46A), Color(0xFFF7F0FF), Color(0xFFFFF5E5), Color.White, Color(0xFF34263F), Color(0xFF806C8B), 20),
    FESTIVAL("페스티벌", "밤하늘에서 터지는 축제 불꽃", "✺", R.drawable.theme_firework_v1, Color(0xFFFF6B35), Color(0xFFFFD166), Color(0xFF18112C), Color(0xFF402052), Color(0xFF291A3A), Color.White, Color(0xFFD2BEDB), 24),
}

val LocalUiThemePack = staticCompositionLocalOf { UiThemePack.STAR_LIVE }

/** 보내기 화면에서는 UI 테마와 어울리는 재화를 자동으로 하나만 사용한다. */
val UiThemePack.fixedVisualTheme: VisualTheme
    get() = when (this) {
        UiThemePack.STAR_LIVE, UiThemePack.COSMIC, UiThemePack.CRYSTAL -> VisualTheme.PURPLE
        UiThemePack.TROPHY -> VisualTheme.GOLD
        UiThemePack.BLOOM, UiThemePack.ROYAL_GIFT -> VisualTheme.FLOWER
        UiThemePack.HEART_PARTY -> VisualTheme.HEART_BALLOON
        UiThemePack.ARCADE -> VisualTheme.ROCKET
        UiThemePack.SNOW_GLOBE -> VisualTheme.PAPER_PLANE
        UiThemePack.FESTIVAL -> VisualTheme.PURPLE
    }
