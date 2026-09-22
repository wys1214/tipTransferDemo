package com.yunsi.tiptransferdemo

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val unitOptions = listOf(100L, 1_000L, 5_000L, 10_000L)

@Composable
fun LiveTransferSessionScreen(
    receiverName: String, connectionMethod: String, unitAmount: Long, selectedTheme: VisualTheme,
    sentAmount: Long, sentCount: Long, isEnding: Boolean, isComplete: Boolean,
    canSend: Boolean, onUnitAmountChange: (Long) -> Unit,
    onSendToken: () -> Unit, onEnd: () -> Unit, onAdditional: () -> Unit, onHome: () -> Unit,
) {
    val pack = LocalUiThemePack.current
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var launching by remember { mutableStateOf(false) }
    var launchFrom by remember { mutableFloatStateOf(0f) }
    var launchId by remember { mutableIntStateOf(0) }
    val flight = remember { Animatable(0f) }
    val currentSend by rememberUpdatedState(onSendToken)
    val enabled by rememberUpdatedState(canSend && !isEnding && !launching)
    LaunchedEffect(selectedTheme) {
        if (!launching) {
            drag = 0f
            flight.snapTo(0f)
        }
    }
    val launch = {
        if (enabled) {
            launchFrom = drag.coerceAtLeast(.12f)
            launching = true
            launchId++
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            currentSend()
        }
    }
    val latestLaunch by rememberUpdatedState(launch)
    LaunchedEffect(launchId) {
        if (launchId > 0) {
            // 손을 뗀 위치에서 그대로 이어서 날아가게 해 프레임이 되감기는 느낌을 없앤다.
            flight.snapTo(launchFrom)
            // 빠르게 튀어 사라지지 않고 손의 관성을 이어받아 천천히 감속한다.
            flight.animateTo(1f, tween(900, easing = CubicBezierEasing(.16f, .72f, .22f, 1f)))
            drag = 0f
            flight.snapTo(0f)
            launching = false
        }
    }
    if (isComplete) {
        Box(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding()) {
            ThemeReceiveCelebration(pack, sentCount.coerceAtLeast(1L), Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Card(
                    shape = RoundedCornerShape(pack.corner.dp),
                    colors = CardDefaults.cardColors(containerColor = pack.surface.copy(alpha = .94f)),
                    border = androidx.compose.foundation.BorderStroke(2.dp, pack.accent.copy(alpha = .62f)),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(pack.iconRes),
                            contentDescription = "${pack.displayName} 재화",
                            modifier = Modifier.size(92.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "${receiverName}님에게 전송했어요",
                            color = pack.muted,
                            fontFamily = pack.fontFamily,
                            fontSize = 15.sp,
                        )
                        Text(
                            "${won(sentAmount)} 보냈어요",
                            color = pack.ink,
                            fontFamily = pack.fontFamily,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "총 ${sentCount}개 재화",
                            color = pack.accent,
                            fontFamily = pack.fontFamily,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onAdditional,
                    enabled = !isEnding,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(pack.corner.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = pack.accent),
                ) { Text(if (isEnding) "추가 전송 준비 중…" else "추가로 보내기", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onHome,
                    enabled = !isEnding,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(pack.corner.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, pack.secondary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = pack.surface.copy(alpha = .88f),
                        contentColor = pack.ink,
                    ),
                ) { Text("홈 화면으로", fontWeight = FontWeight.SemiBold) }
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding()) {
        val compact = maxHeight < 680.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
            TransferHeader("${receiverName}님에게", "$connectionMethod · 가까운 사람에게 보내기")
            TransferAmountCard("지금까지 보낸 금액", sentAmount, sentCount)
            Box(Modifier.fillMaxWidth().height(if (compact) 210.dp else 280.dp).clip(RoundedCornerShape(28.dp))) {
                ThemeSendingEffect(pack, if (launching) maxOf(flight.value, launchFrom) else drag, Modifier.fillMaxSize())
                // Compose owns gestures. SceneView's orbit controls are disabled.
                Box(Modifier.fillMaxSize().semantics {
                    contentDescription = "${selectedTheme.displayName}, ${won(unitAmount)} 보내기"
                    role = Role.Button
                    if (!enabled) disabled()
                    onClick("전송") { if (enabled) { latestLaunch(); true } else false }
                }.pointerInput(threshold) {
                    detectVerticalDragGestures(
                        onDragStart = { if (enabled) drag = 0f },
                        onDragCancel = { if (!launching) drag = 0f },
                        onDragEnd = { if (!launching) drag = 0f },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            if (enabled) {
                                drag = (drag - dy / threshold).coerceIn(0f, 1f)
                                if (drag >= .72f) latestLaunch()
                            }
                        },
                    )
                })
                Text(if (launching) "전달하는 중" else "↑  위로 밀어 보내세요",
                    color = pack.muted, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
            Surface(color = pack.surface, shape = RoundedCornerShape(pack.corner.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("한 번에 보낼 금액", color = pack.muted, fontSize = 14.sp)
                        Text(won(unitAmount), color = pack.accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Slider(value = unitOptions.indexOf(unitAmount).coerceAtLeast(0).toFloat(),
                        onValueChange = { if (!launching) onUnitAmountChange(unitOptions[it.roundToInt().coerceIn(0, 3)]) },
                        valueRange = 0f..3f, steps = 2, enabled = !isEnding,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "재화 1개당 금액" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        unitOptions.forEach { Text(won(it), color = pack.muted, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("테마 전용 재화", color = pack.muted, fontSize = 12.sp)
                        Text("${pack.displayName} 전용", color = pack.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Button(onClick = { if (!launching) onEnd() }, enabled = !isEnding,
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(pack.corner.dp), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) {
                Text(if (isEnding) "전송을 확인하고 있어요" else "전송 마치기", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
