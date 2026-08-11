package com.yunsi.tiptransferdemo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import androidx.camera.core.ExperimentalGetImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay

private const val MAX_TRANSFER_AMOUNT = 10
private const val DAILY_SEND_LIMIT = 100L

private data class ReceiverSession(val id: String, val receiverId: String, val nickname: String)

@Composable
fun SendScannerScreen(theme: VisualTheme, onBack: () -> Unit) {
    var session by remember { mutableStateOf<ReceiverSession?>(null) }
    var amount by remember { mutableStateOf(1) }
    var isReadyToSend by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var useNfc by remember { mutableStateOf(false) }

    BackHandler {
        when {
            isComplete -> onBack()
            isReadyToSend -> isReadyToSend = false
            else -> onBack()
        }
    }

    when {
        useNfc -> NfcSendScreen(theme = theme, onBack = { useNfc = false }, onSessionFound = { session = it })
        isComplete -> TransferCompleteScreen(amount = amount, theme = theme, onHome = onBack, onSendMore = {
            session = null
            amount = 1
            isComplete = false
        })
        isReadyToSend && session != null -> SendGestureScreen(
            amount = amount,
            theme = theme,
            isSending = isSending,
            onBack = { isReadyToSend = false },
            onSend = {
                isSending = true
                transfer(session!!, amount,
                    onSuccess = { isSending = false; isComplete = true },
                    onError = { isSending = false; isReadyToSend = false; session = null; message = it },
                )
            },
        )
        session != null -> AmountScreen(
            receiverName = session!!.nickname,
            amount = amount,
            theme = theme,
            onAmountChange = { amount = it },
            onConfirm = { isReadyToSend = true },
            onBack = { session = null },
        )
        else -> ScannerScreen(theme = theme, onBack = onBack, onUseNfc = { useNfc = true }, onSessionFound = { session = it }, onMessage = { message = it })
    }

    message?.let { text ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.background(Color(0xE8171B3A), RoundedCornerShape(20.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text, color = Color.White)
                Button(onClick = { message = null }) { Text("확인") }
            }
        }
    }
}

@Composable
private fun NfcSendScreen(theme: VisualTheme, onBack: () -> Unit, onSessionFound: (ReceiverSession) -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    DisposableEffect(adapter, activity) {
        if (adapter == null || activity == null) {
            message = "이 기기에서는 NFC를 사용할 수 없어요."
            return@DisposableEffect onDispose { }
        }
        if (!adapter.isEnabled) {
            message = "NFC를 켠 뒤 다시 시도해 주세요."
            return@DisposableEffect onDispose { }
        }
        adapter.enableReaderMode(
            activity,
            readerCallback@ { tag ->
                val isoDep = IsoDep.get(tag) ?: return@readerCallback
                try {
                    isoDep.connect()
                    val response = isoDep.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x06, 0xF0.toByte(), 0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0x00))
                    if (response.size <= 2) throw IllegalStateException()
                    val sessionId = String(response.copyOfRange(0, response.size - 2), Charsets.UTF_8)
                    if (sessionId.isBlank()) throw IllegalStateException()
                    activity.runOnUiThread {
                        if (!checking) {
                            checking = true
                            verifySession(sessionId, theme, onSessionFound, { message = it }) { checking = false }
                        }
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread { message = "NFC 연결에 실패했어요. 두 기기를 다시 가까이 대세요." }
                } finally {
                    try { isoDep.close() } catch (_: Exception) { }
                }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        onDispose { adapter.disableReaderMode(activity) }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("NFC로 보내기", color = theme.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("수신자의 NFC 받기 화면을 연 뒤\n휴대폰 뒷면을 가까이 대세요.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("〰", color = theme.primary, fontSize = 84.sp)
        Text(message ?: if (checking) "수신 세션을 확인하는 중이에요." else "NFC 연결을 기다리고 있어요.", color = Color(0xFF64748B), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        ThemeButton(theme = theme, onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("QR 스캔으로 전환") }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ScannerScreen(theme: VisualTheme, onBack: () -> Unit, onUseNfc: () -> Unit, onSessionFound: (ReceiverSession) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionRequested by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        permissionRequested = true
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var manualSessionId by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var torchControl by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraRetryKey by remember { mutableStateOf(0) }
    val verify: (String) -> Unit = { id ->
        if (checking) Unit else {
            checking = true
            verifySession(id, theme, onSessionFound, { onMessage(it) }) { checking = false }
        }
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("QR을 스캔하려면 카메라 권한이 필요해요.")
            if (permissionRequested) {
                Text("권한이 꺼져 있으면 앱 설정에서 카메라를 허용해 주세요.", color = Color(0xFF64748B), fontSize = 14.sp)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            },
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                ) { Text("앱 설정 열기") }
                ThemeButton(theme = theme, onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("권한 다시 요청") }
            } else {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("카메라 권한 허용") }
            }
            ThemeButton(theme = theme, onClick = onBack) { Text("홈으로") }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color(0xFFF8FAFC))) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 54.dp).fillMaxWidth().background(theme.primary, RoundedCornerShape(24.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("QR 스캔", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("수신자의 QR을 프레임 안에 맞춰 주세요.", color = theme.sparkle, fontSize = 14.sp)
        }
        Box(
            Modifier.align(Alignment.Center).offset(y = (-34).dp).size(250.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black),
        ) {
            if (cameraError == null) {
                key(cameraRetryKey) {
                    QrCamera(
                        onDetected = { value ->
                            val id = value.substringAfter("tiptransfer://session/", "")
                            if (id.isBlank()) onMessage("유효하지 않은 QR이에요.") else verify(id)
                        },
                        onCameraReady = { control -> torchControl = control },
                        onError = { cameraError = it },
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(cameraError!!, color = Color.White, fontSize = 14.sp)
                    ThemeButton(
                        theme = theme,
                        onClick = {
                            cameraError = null
                            torchControl = null
                            cameraRetryKey += 1
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("카메라 다시 열기") }
                }
            }
            Box(Modifier.fillMaxSize().border(3.dp, theme.primary, RoundedCornerShape(28.dp)))
            Text("⌁", color = theme.sparkle, fontSize = 26.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            if (cameraError == null) {
                Text("QR을 인식하는 중", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).background(theme.dark.copy(alpha = .7f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        torchControl?.let { control ->
            Button(
                onClick = { torchOn = !torchOn; control(torchOn) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 38.dp).size(44.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = theme.primary,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .75f)),
            ) { Text(if (torchOn) "☼" else "◉", fontSize = 18.sp) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).border(1.dp, theme.primary.copy(alpha = .35f), RoundedCornerShape(24.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("테스트 연결", color = theme.primary, fontWeight = FontWeight.Bold)
            ThemeButton(theme = theme, onClick = onUseNfc, modifier = Modifier.fillMaxWidth()) { Text("NFC로 연결") }
            Text("테스트 환경에서는 세션 ID로 확인할 수 있어요.", color = Color(0xFF64748B), fontSize = 12.sp)
            OutlinedTextField(value = manualSessionId, onValueChange = { manualSessionId = it }, label = { Text("테스트용 세션 ID") }, singleLine = true)
            Button(onClick = { verify(manualSessionId.trim()) }, enabled = manualSessionId.isNotBlank() && !checking, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) {
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("세션 ID로 확인")
            }
            ThemeButton(theme = theme, onClick = onBack) { Text("취소") }
        }
    }
}

@Composable
private fun AmountScreen(receiverName: String, amount: Int, theme: VisualTheme, onAmountChange: (Int) -> Unit, onConfirm: () -> Unit, onBack: () -> Unit) {
    var input by remember(amount) { mutableStateOf(amount.toString()) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Text("$receiverName 님에게 보낼 재화를 선택하세요")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onAmountChange((amount - 1).coerceAtLeast(1)) }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("−") }
            OutlinedTextField(value = input, onValueChange = {
                input = it.filter(Char::isDigit)
                val value = input.toIntOrNull()
                if (value != null && value in 1..MAX_TRANSFER_AMOUNT) onAmountChange(value)
            }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("수량") }, singleLine = true, modifier = Modifier.fillMaxWidth(.45f))
            Button(onClick = { onAmountChange((amount + 1).coerceAtMost(MAX_TRANSFER_AMOUNT)) }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("+") }
        }
        Text("한 번에 최대 10개까지 보낼 수 있어요.", color = Color(0xFF64748B))
        if (input.toIntOrNull()?.let { it > MAX_TRANSFER_AMOUNT } == true) Text("한 번에 10개만 보낼 수 있어요.", color = Color(0xFFDC2626))
        Button(onClick = onConfirm, enabled = input.toIntOrNull() in 1..MAX_TRANSFER_AMOUNT, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("확인") }
        ThemeButton(theme = theme, onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("다시 스캔") }
    }
}

@Composable
private fun SendGestureScreen(amount: Int, theme: VisualTheme, isSending: Boolean, onBack: () -> Unit, onSend: () -> Unit) {
    var launchStarted by remember { mutableStateOf(false) }
    val tokenOffset by animateDpAsState(
        targetValue = if (launchStarted && theme == VisualTheme.PURPLE) (-760).dp else 0.dp,
        animationSpec = tween(durationMillis = 620),
        label = "sendPurpleTokenLaunch",
    )
    val tokenRotation by animateFloatAsState(
        targetValue = if (launchStarted && theme == VisualTheme.GOLD) 720f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "goldCoinRotation",
    )
    LaunchedEffect(launchStarted) {
        if (launchStarted) {
            delay(
                when (theme) {
                    VisualTheme.GOLD -> 800L + (amount - 1) * 110L
                    VisualTheme.ROCKET -> 900L
                    VisualTheme.PARTICLE -> 720L
                    else -> 650L
                },
            )
            onSend()
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        if (!launchStarted) {
            Text("전송 대기 중", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("상대방에게 ${amount}개를 보낼 준비가 되었어요.", color = Color(0xFF64748B))
            Text("↑", color = theme.primary, fontSize = 90.sp)
        }
        Box(
            Modifier.fillMaxWidth().height(260.dp).pointerInput(isSending, launchStarted) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -80f && !isSending && !launchStarted) launchStarted = true
                }
            }, contentAlignment = Alignment.Center,
        ) {
            if (theme == VisualTheme.PURPLE && launchStarted) {
                PurpleToken(Modifier.offset(y = tokenOffset), size = 100.dp)
            } else if (theme == VisualTheme.GOLD && launchStarted) {
                GoldCoinLaunchSequence(amount)
            } else if (theme == VisualTheme.ROCKET && launchStarted) {
                RocketLaunchSequence()
            } else if (theme == VisualTheme.PARTICLE && launchStarted) {
                ParticleBurstLaunchSequence(theme)
            } else if (isSending) {
                CircularProgressIndicator(color = if (theme == VisualTheme.PURPLE) Color.White else theme.primary)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (theme == VisualTheme.GOLD) GoldCoin(Modifier.rotate(tokenRotation), size = 76.dp)
                    else if (theme == VisualTheme.ROCKET) RocketShip(size = 96.dp)
                    else if (theme == VisualTheme.PURPLE) PurpleToken(size = 94.dp)
                    else Text(theme.token, color = theme.primary, fontSize = 92.sp, modifier = Modifier.rotate(tokenRotation))
                    Text(
                        "${amount}개\n위로 밀어 보내기",
                        color = theme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        ThemeButton(theme = theme, onClick = onBack, enabled = !isSending && !launchStarted, modifier = Modifier.fillMaxWidth()) { Text("이전") }
    }
}

@Composable
fun PurpleToken(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Box(
        modifier.size(size * 1.28f).background(Color(0x334F46E5), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size)
                .background(Brush.radialGradient(listOf(Color(0xFFA5B4FC), Color(0xFF6366F1), Color(0xFF312E81))), CircleShape)
                .border(1.5.dp, Color(0xFFC7D2FE), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(size * .54f).background(Color.White.copy(alpha = .18f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = .42f), CircleShape),
            )
            Box(
                Modifier.align(Alignment.TopStart).padding(start = size * .20f, top = size * .17f)
                    .size(size * .13f).background(Color.White.copy(alpha = .78f), CircleShape),
            )
        }
    }
}

@Composable
fun GoldCoin(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        modifier.size(size * 1.20f).background(Color(0x33FDE68A), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFFFF3B0), Color(0xFFFBBF24), Color(0xFFB45309)),
                    ),
                    CircleShape,
                )
                .border(1.5.dp, Color(0xFFFFF7CC), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(size * .72f)
                    .background(Brush.radialGradient(listOf(Color(0xFFFFF7CC), Color(0xFFF59E0B))), CircleShape)
                    .border(1.dp, Color(0xFFB45309), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("✦", color = Color(0xFF92400E), fontSize = (size.value * .42f).sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.align(Alignment.TopStart).padding(start = size * .18f, top = size * .16f)
                    .size(size * .14f).background(Color.White.copy(alpha = .75f), CircleShape),
            )
        }
    }
}

@Composable
private fun GoldCoinLaunchSequence(count: Int) {
    val coinCount = count.coerceIn(1, MAX_TRANSFER_AMOUNT)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(coinCount) { index ->
            var launched by remember { mutableStateOf(false) }
            val y by animateDpAsState(if (launched) (-720).dp else 28.dp, tween(760), label = "coinLaunch$index")
            val rotation by animateFloatAsState(if (launched) 900f else 0f, tween(760), label = "coinSpin$index")
            LaunchedEffect(Unit) { delay(index * 110L); launched = true }
            val lane = ((index - (coinCount - 1) / 2f) * 18f).dp
            GoldCoin(Modifier.offset(x = lane, y = y).rotate(rotation).alpha(if (launched) 1f else 0f), size = 56.dp)
        }
    }
}

@Composable
private fun RocketLaunchSequence() {
    var launched by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (launched) (-760).dp else 55.dp, tween(850), label = "rocketLaunch")
    val flameSize by animateFloatAsState(if (launched) 1.3f else 0.6f, tween(250), label = "rocketFlame")
    LaunchedEffect(Unit) { launched = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.offset(y = y + 52.dp).size((52 * flameSize).dp, (82 * flameSize).dp)
                .background(Brush.verticalGradient(listOf(Color(0x00FDE047), Color(0xFFF97316), Color(0x00EF4444))), RoundedCornerShape(50)),
        )
        RocketShip(modifier = Modifier.offset(y = y), size = 104.dp)
    }
}

@Composable
fun RocketShip(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Box(modifier.size(size * .68f, size), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.size(size * .28f, size * .34f)
                .background(Brush.verticalGradient(listOf(Color(0xFFFDE047), Color(0xFFFB7185), Color(0x00F97316))), RoundedCornerShape(50))
                .offset(y = size * .12f),
        )
        Box(
            Modifier.size(size * .60f, size * .78f)
                .background(Brush.verticalGradient(listOf(Color(0xFFF8FAFC), Color(0xFFCBD5E1), Color(0xFF94A3B8))), RoundedCornerShape(50))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(50)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier.padding(top = size * .18f).size(size * .22f)
                    .background(Color(0xFF1E3A8A), CircleShape)
                    .border(2.dp, Color(0xFF93C5FD), CircleShape),
            )
        }
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = (-size * .07f), y = (-size * .04f))
                .size(size * .22f, size * .30f)
                .background(Color(0xFFDC2626), RoundedCornerShape(8.dp)),
        )
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = size * .07f, y = (-size * .04f))
                .size(size * .22f, size * .30f)
                .background(Color(0xFFDC2626), RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun ParticleBurstLaunchSequence(theme: VisualTheme) {
    var burst by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { burst = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(16) { index ->
            val xTarget = ((index % 4) - 1.5f) * 54f
            val yTarget = ((index / 4) - 1.5f) * 52f - 92f
            val x by animateDpAsState(if (burst) xTarget.dp else 0.dp, tween(680), label = "particleX$index")
            val y by animateDpAsState(if (burst) yTarget.dp else 0.dp, tween(680), label = "particleY$index")
            val alpha by animateFloatAsState(if (burst) .28f else 1f, tween(680), label = "particleAlpha$index")
            ParticleDot(
                modifier = Modifier.offset(x = x, y = y).alpha(alpha),
                size = if (index % 3 == 0) 15.dp else 10.dp,
                color = if (index % 2 == 0) theme.sparkle else theme.primary,
            )
        }
    }
}

@Composable
fun ParticleDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 12.dp, color: Color = Color(0xFF67E8F9)) {
    Box(
        modifier.size(size * 1.7f).background(color.copy(alpha = .18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).background(Brush.radialGradient(listOf(Color.White, color)), CircleShape),
        )
    }
}

@Composable
fun RocketLanding() {
    var landed by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (landed) 0.dp else (-220).dp, tween(750), label = "rocketLanding")
    val impactSize by animateDpAsState(if (landed) 108.dp else 20.dp, tween(440), label = "rocketImpact")
    val impactAlpha by animateFloatAsState(if (landed) 0f else 1f, tween(560), label = "rocketImpactAlpha")
    LaunchedEffect(Unit) { landed = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(impactSize).alpha(impactAlpha).border(3.dp, Color(0xFFFBBF24), CircleShape))
        RocketShip(modifier = Modifier.offset(y = y), size = 74.dp)
    }
}

@Composable
private fun TransferCompleteScreen(amount: Int, theme: VisualTheme, onHome: () -> Unit, onSendMore: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("전송 완료", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
        CelebrationSparkles(theme)
        Text("✨  ${amount}개를 보냈어요!  ✨", fontSize = 20.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.height(30.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("홈으로") }
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onSendMore, modifier = Modifier.fillMaxWidth()) { Text("추가로 보내기") }
    }
}

@Composable
fun CelebrationSparkles(theme: VisualTheme) {
    var started by remember { mutableStateOf(false) }
    val distance by animateDpAsState(if (started) 52.dp else 0.dp, tween(420), label = "sparkleDistance")
    val alpha by animateFloatAsState(if (started) 1f else 0f, tween(180), label = "sparkleAlpha")
    LaunchedEffect(Unit) {
        started = true
        delay(650)
        started = false
    }
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Text("✦", color = theme.primary, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center).offset(y = -distance).alpha(alpha))
        Text("✦", color = theme.sparkle, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center).offset(x = distance, y = (-18).dp).alpha(alpha))
        Text("✦", color = theme.primary, fontSize = 24.sp, modifier = Modifier.align(Alignment.Center).offset(x = -distance, y = 12.dp).alpha(alpha))
        Text("✦", color = theme.sparkle, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center).offset(x = 28.dp, y = distance).alpha(alpha))
        if (theme == VisualTheme.PARTICLE) {
            Text("·", color = theme.sparkle, fontSize = 34.sp, modifier = Modifier.align(Alignment.Center).offset(x = (-34).dp, y = -distance).alpha(alpha))
            Text("✧", color = theme.primary, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center).offset(x = 44.dp, y = distance).alpha(alpha))
        }
    }
}

private fun verifySession(id: String, theme: VisualTheme, onFound: (ReceiverSession) -> Unit, onError: (String) -> Unit, onComplete: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val sessionRef = db.collection("transferSessions").document(id)
    val senderId = FirebaseAuth.getInstance().currentUser?.uid
    if (senderId == null) { onError("로그인 정보를 찾을 수 없어요."); onComplete(); return }
    db.runTransaction<String> { tx ->
        val snapshot = tx.get(sessionRef)
        val receiverId = snapshot.getString("receiverId") ?: throw IllegalStateException("유효하지 않은 QR이에요.")
        if (snapshot.getString("status") != "active") throw IllegalStateException("이미 사용 중이거나 종료된 QR이에요.")
        if (receiverId == senderId) throw IllegalStateException("내 QR에는 보낼 수 없어요.")
        tx.update(
            sessionRef,
            "status", "claimed",
            "senderId", senderId,
            "visualTheme", theme.name,
            "expiresAt", Timestamp(Date(System.currentTimeMillis() + 60_000)),
        )
        receiverId
    }.addOnSuccessListener { receiverId ->
        db.collection("users").document(receiverId).get().addOnSuccessListener { user ->
            onFound(ReceiverSession(id, receiverId, user.getString("nickname") ?: "상대방"))
        }.addOnFailureListener { onError("수신자 정보를 불러오지 못했어요.") }.addOnCompleteListener { onComplete() }
    }.addOnFailureListener { onError(it.message ?: "세션을 확인하지 못했어요."); onComplete() }
}

private fun transfer(session: ReceiverSession, amount: Int, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val sender = auth.currentUser ?: run { onError("로그인 정보를 찾을 수 없어요."); return }
    val db = FirebaseFirestore.getInstance()
    val sessionRef = db.collection("transferSessions").document(session.id)
    val senderRef = db.collection("users").document(sender.uid)
    val receiverRef = db.collection("users").document(session.receiverId)
    val historyRef = db.collection("transactions").document()
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
    db.runTransaction { tx ->
        val liveSession = tx.get(sessionRef)
        val senderWallet = tx.get(senderRef)
        val receiverWallet = tx.get(receiverRef)
        val active = liveSession.getString("status") == "claimed" && liveSession.getString("senderId") == sender.uid
        val unexpired = liveSession.getTimestamp("expiresAt")?.toDate()?.after(Date()) == true
        if (!active || !unexpired) throw IllegalStateException("세션이 만료되었어요. 다시 QR을 스캔해 주세요.")
        val balance = senderWallet.getLong("balance") ?: 0L
        if (balance < amount) throw IllegalStateException("보유 재화가 부족해요.")
        val sentToday = if (senderWallet.getString("dailyLimitDate") == today) senderWallet.getLong("dailySentAmount") ?: 0L else 0L
        if (sentToday + amount > DAILY_SEND_LIMIT) throw IllegalStateException("하루에 보낼 수 있는 최대 수량이 초과되었어요.")
        tx.set(senderRef, mapOf("balance" to balance - amount, "dailySentAmount" to sentToday + amount, "dailyLimitDate" to today, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        tx.set(receiverRef, mapOf("balance" to (receiverWallet.getLong("balance") ?: 0L) + amount, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        tx.update(
            sessionRef,
            "status", "used",
            "usedAt", FieldValue.serverTimestamp(),
            "cleanupAt", terminalSessionCleanupAt(),
            "senderId", sender.uid,
            "amount", amount,
        )
        tx.set(historyRef, mapOf("senderId" to sender.uid, "receiverId" to session.receiverId, "amount" to amount, "type" to "transfer", "createdAt" to FieldValue.serverTimestamp()))
        null
    }.addOnSuccessListener { onSuccess() }.addOnFailureListener { error -> onError(error.message ?: "전송을 완료하지 못했어요.") }
}

@Composable
@OptIn(ExperimentalGetImage::class)
private fun QrCamera(
    onDetected: (String) -> Unit,
    onCameraReady: ((Boolean) -> Unit) -> Unit,
    onError: (String) -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    AndroidView(factory = { ctx ->
        PreviewView(ctx).also { view ->
            val provider = ProcessCameraProvider.getInstance(ctx)
            provider.addListener({
                try {
                    val cameraProvider = provider.get()
                    val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { image ->
                        val media = image.image
                        if (media == null) { image.close(); return@setAnalyzer }
                        scanner.process(com.google.mlkit.vision.common.InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes -> codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue?.let(onDetected) }
                            .addOnCompleteListener { image.close() }
                    }
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    onCameraReady { enabled -> camera.cameraControl.enableTorch(enabled) }
                } catch (_: Exception) {
                    onError("카메라를 열지 못했어요. 다른 앱에서 카메라를 사용 중인지 확인해 주세요.")
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, modifier = Modifier.fillMaxSize())
}
