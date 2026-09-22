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
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
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
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import androidx.camera.core.ExperimentalGetImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay

// 기존 QR/BLE 연결 보조 화면이 사용하는 표시용 최대 개수다. 라이브 전송은 금액 단위를 사용한다.
private const val MAX_TRANSFER_AMOUNT = 10
private const val DEFAULT_DAILY_SEND_LIMIT = 300_000L
private const val DEFAULT_SINGLE_SEND_LIMIT = 10_000L
private val TRANSFER_UNIT_OPTIONS = listOf(100L, 1_000L, 5_000L, 10_000L)

private data class ReceiverSession(
    val id: String,
    val receiverId: String,
    val nickname: String,
    val connectionMethod: String = "연결",
)

private data class PendingTokenTransfer(val unitAmount: Long, val theme: VisualTheme)

@Composable
fun SendScannerScreen(theme: VisualTheme, onBack: () -> Unit) {
    var session by remember { mutableStateOf<ReceiverSession?>(null) }
    var unitAmount by remember { mutableStateOf(1_000L) }
    val selectedTheme = LocalUiThemePack.current.fixedVisualTheme
    var sentAmount by remember { mutableStateOf(0L) }
    var sentCount by remember { mutableStateOf(0L) }
    var isEndingSession by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var expiredWithoutTransfer by remember { mutableStateOf(false) }
    // 화면 스와이프는 즉시 반응시키고, 서버 반영만 내부 대기열에서 순서대로 처리한다.
    var pendingTransfers by remember { mutableStateOf<List<PendingTokenTransfer>>(emptyList()) }
    var tokenRequestInFlight by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 기본 전송 방식은 NFC이며, 필요할 때 주변 BLE/QR로 전환한다.
    var useNfc by remember { mutableStateOf(true) }
    var useBle by remember { mutableStateOf(false) }

    DisposableEffect(session?.id) {
        val activeSession = session ?: return@DisposableEffect onDispose { }
        val registration = FirebaseFirestore.getInstance().collection("transferSessions")
            .document(activeSession.id).addSnapshotListener { snapshot, _ ->
                val terminalStatus = snapshot?.getString("status")
                if (terminalStatus != "expired" && terminalStatus != "used") return@addSnapshotListener
                val finalCount = snapshot.getLong("deliveredCount") ?: sentCount
                val finalAmount = snapshot.getLong("amount") ?: sentAmount
                pendingTransfers = emptyList()
                tokenRequestInFlight = false
                isEndingSession = false
                sentCount = finalCount
                sentAmount = finalAmount
                if (finalCount > 0L) {
                    isComplete = true
                } else {
                    expiredWithoutTransfer = true
                }
            }
        onDispose { registration.remove() }
    }

    LaunchedEffect(pendingTransfers, tokenRequestInFlight, isEndingSession, session) {
        val activeSession = session ?: return@LaunchedEffect
        val nextTransfer = pendingTransfers.firstOrNull()
        if (nextTransfer != null && !tokenRequestInFlight) {
            tokenRequestInFlight = true
            transferOne(activeSession, nextTransfer.unitAmount, nextTransfer.theme,
                onSuccess = {
                    pendingTransfers = pendingTransfers.drop(1)
                    tokenRequestInFlight = false
                    sentAmount += nextTransfer.unitAmount
                    sentCount += 1
                },
                onError = {
                    tokenRequestInFlight = false
                    pendingTransfers = emptyList()
                    message = it
                },
            )
        } else if (isEndingSession && nextTransfer == null && !tokenRequestInFlight) {
            finalizeTransfer(activeSession,
                onSuccess = { isEndingSession = false; isComplete = true },
                onError = { isEndingSession = false; message = it },
            )
        }
    }

    BackHandler {
        when {
            isComplete -> onBack()
            session != null -> session = null
            else -> onBack()
        }
    }

    when {
        session != null -> LiveTransferSessionScreen(
            receiverName = session!!.nickname,
            connectionMethod = session!!.connectionMethod,
            unitAmount = unitAmount,
            selectedTheme = selectedTheme,
            sentAmount = sentAmount + pendingTransfers.sumOf { it.unitAmount },
            sentCount = sentCount + pendingTransfers.size,
            canSend = pendingTransfers.size < 8,
            isEnding = isEndingSession,
            isComplete = isComplete,
            onUnitAmountChange = { unitAmount = it },
            onSendToken = { pendingTransfers = pendingTransfers + PendingTokenTransfer(unitAmount, selectedTheme) },
            onEnd = { isEndingSession = true },
            onAdditional = {
                val activeSession = session
                if (activeSession != null && !isEndingSession) {
                    isEndingSession = true
                    reopenTransferSession(
                        activeSession,
                        selectedTheme,
                        onSuccess = {
                            pendingTransfers = emptyList()
                            tokenRequestInFlight = false
                            sentAmount = 0L
                            sentCount = 0L
                            isComplete = false
                            isEndingSession = false
                        },
                        onError = {
                            isEndingSession = false
                            message = it
                        },
                    )
                }
            },
            onHome = onBack,
        )
        useBle -> BleFinderScreen(
            theme = theme,
            onSessionId = { token ->
                verifyBleSession(token, theme, {
                    session = it.copy(connectionMethod = "주변 연결")
                    useBle = false
                }, { message = it }) { }
            },
            onUseNfc = { useBle = false; useNfc = true },
            onUseQr = { useBle = false; useNfc = false },
            onHome = onBack,
        )
        useNfc -> NfcSendScreen(
            theme = theme,
            onUseBle = { useNfc = false; useBle = true },
            onUseQr = { useNfc = false },
            onBack = onBack,
            onSessionFound = { session = it.copy(connectionMethod = "NFC 연결") },
        )
        else -> ScannerScreen(
            theme = theme,
            onBack = onBack,
            onUseNfc = { useNfc = true },
            onSessionFound = { session = it.copy(connectionMethod = "QR 연결") },
            onMessage = { message = it },
        )
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
    if (expiredWithoutTransfer) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("세션이 만료되었어요") },
            text = { Text("60초 동안 주고받은 재화가 없어 연결을 종료했어요.") },
            confirmButton = { TextButton(onClick = { expiredWithoutTransfer = false; onBack() }) { Text("홈으로") } },
        )
    }
}

private fun verifyBleSession(
    token: String,
    theme: VisualTheme,
    onFound: (ReceiverSession) -> Unit,
    onError: (String) -> Unit,
    onComplete: () -> Unit,
) {
    FirebaseFirestore.getInstance().collection("transferSessions")
        .whereEqualTo("bleToken", token)
        .limit(1)
        .get()
        .addOnSuccessListener { result ->
            val sessionId = result.documents.firstOrNull()?.id
            if (sessionId == null) {
                onError("현재 사용할 수 없는 주변 연결이에요. 수신 측에서 주변 연결을 다시 시작해 주세요.")
                onComplete()
            } else {
                verifySession(sessionId, theme, "주변 연결", onFound, onError, onComplete)
            }
        }
        .addOnFailureListener {
            onError("주변 연결 정보를 확인하지 못했어요. 인터넷 연결을 확인해 주세요.")
            onComplete()
        }
}

@Composable
private fun NfcSendScreen(
    theme: VisualTheme,
    onUseBle: () -> Unit,
    onUseQr: () -> Unit,
    onBack: () -> Unit,
    onSessionFound: (ReceiverSession) -> Unit,
) {
    val context = LocalContext.current
    val pack = LocalUiThemePack.current
    val activity = context.findActivity()
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val nfcEnabled = rememberNfcEnabled(context, adapter)
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    DisposableEffect(adapter, activity, nfcEnabled) {
        if (adapter == null || activity == null) {
            message = "이 기기에서는 NFC를 사용할 수 없어요."
            return@DisposableEffect onDispose { }
        }
        if (!nfcEnabled) {
            message = "NFC를 켠 뒤 다시 시도해 주세요."
            return@DisposableEffect onDispose { }
        }
        // NFC를 다시 켠 뒤에는 꺼져 있을 때의 안내를 남기지 않는다.
        message = null
        adapter.enableReaderMode(
            activity,
            readerCallback@ { tag ->
                val isoDep = IsoDep.get(tag)
                if (isoDep == null) {
                    activity.runOnUiThread {
                        message = "NFC는 감지했지만 이 기기는 전송용 NFC 형식(ISO-DEP)을 지원하지 않아요. QR로 전송해 주세요."
                    }
                    return@readerCallback
                }
                try {
                    isoDep.connect()
                    val response = isoDep.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x06, 0xF0.toByte(), 0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0x00))
                    if (response.size <= 2 || response[response.size - 2] != 0x90.toByte() || response.last() != 0x00.toByte()) {
                        throw IllegalStateException("수신 기기에서 NFC 전송 세션을 열지 않았어요.")
                    }
                    val sessionId = String(response.copyOfRange(0, response.size - 2), Charsets.UTF_8)
                    if (sessionId.isBlank()) throw IllegalStateException()
                    activity.runOnUiThread {
                        if (!checking) {
                            checking = true
                            verifySession(sessionId, theme, "NFC 세션", onSessionFound, { message = it }) { checking = false }
                        }
                    }
                } catch (error: Exception) {
                    activity.runOnUiThread {
                        message = error.message ?: "NFC 연결에 실패했어요. 두 기기를 다시 가까이 대세요."
                    }
                } finally {
                    try { isoDep.close() } catch (_: Exception) { }
                }
            },
            // Android HCE의 ISO-DEP 카드는 NFC-A 지원이 보장된다. NFC-B까지 함께
            // 요청하면 일부 기기에서 잘못된 기술 선택으로 HCE 응답을 받지 못할 수 있다.
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        onDispose { adapter.disableReaderMode(activity) }
    }

    Column(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("NFC로 보내기", color = pack.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = pack.fontFamily)
        Spacer(Modifier.height(16.dp))
        Text("수신자의 NFC 받기 화면을 연 뒤\n휴대폰 뒷면을 가까이 대세요.", color = pack.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("〰", color = pack.accent, fontSize = 84.sp)
        Text(
            if (!nfcEnabled) "NFC가 꺼져 있어요. 기기 설정에서 NFC를 켠 뒤 다시 시도해 주세요."
            else message ?: if (checking) "수신 세션을 확인하는 중이에요." else "NFC 연결을 기다리고 있어요.",
            color = pack.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text("연결이 되지 않으면 QR로 바로 전송할 수 있어요.", color = theme.primary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        ThemeButton(theme = theme, onClick = onUseBle, modifier = Modifier.fillMaxWidth()) { Text("가까운 사람 찾기") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseQr, modifier = Modifier.fillMaxWidth()) { Text("NFC가 안 되나요? QR로 전송") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) { Text("홈으로") }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ScannerScreen(theme: VisualTheme, onBack: () -> Unit, onUseNfc: () -> Unit, onSessionFound: (ReceiverSession) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val pack = LocalUiThemePack.current
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
    var checking by remember { mutableStateOf(false) }
    // 카메라는 같은 QR을 연속 프레임으로 인식한다. 첫 인식 뒤에는 화면 전환까지 잠근다.
    var scanLocked by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var torchControl by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraRetryKey by remember { mutableStateOf(0) }
    val verify: (String) -> Unit = { id ->
        if (checking || scanLocked) Unit else {
            checking = true
            scanLocked = true
            verifySession(
                id = id,
                theme = theme,
                connectionName = "QR 코드",
                onFound = onSessionFound,
                onError = {
                    scanLocked = false
                    onMessage(it)
                },
                onComplete = { checking = false },
            )
        }
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("QR을 스캔하려면 카메라 권한이 필요해요.", color = pack.ink)
            if (permissionRequested) {
                Text("권한이 꺼져 있으면 앱 설정에서 카메라를 허용해 주세요.", color = pack.muted, fontSize = 14.sp)
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
    Box(Modifier.fillMaxSize().themeAtmosphere(pack)) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 54.dp).fillMaxWidth().background(pack.surface, RoundedCornerShape(pack.corner.dp)).border(1.dp, pack.accent.copy(alpha = .4f), RoundedCornerShape(pack.corner.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("QR 스캔", color = pack.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = pack.fontFamily)
            Text("수신자의 QR을 프레임 안에 맞춰 주세요.", color = pack.muted, fontSize = 14.sp)
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
            Box(Modifier.fillMaxSize().border(3.dp, pack.accent, RoundedCornerShape(28.dp)))
            Image(
                painter = painterResource(pack.iconRes),
                contentDescription = pack.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(34.dp),
            )
            if (cameraError == null) {
                Text("QR을 인식하는 중", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).background(theme.dark.copy(alpha = .7f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        torchControl?.let { control ->
            Button(
                onClick = { torchOn = !torchOn; control(torchOn) },
                // QR 프레임과 하단 전송 패널의 가운데 여백에 둔다.
                modifier = Modifier.align(Alignment.Center).offset(y = 128.dp).height(38.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = pack.accent,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, pack.accent.copy(alpha = .45f)),
            ) {
                Text(if (torchOn) "⚡ 플래시 끄기" else "⚡ 플래시 켜기", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ThemeButton(theme = theme, onClick = onUseNfc, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("NFC로 연결") }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pack.accent),
            ) { Text("홈으로") }
        }
    }
}

/** 연결 이후에는 금액·효과 선택, 반복 전송, 종료 요약을 한 화면 안에서 처리한다. */
@Composable
private fun LegacyLiveTransferSessionScreen(
    receiverName: String,
    connectionMethod: String,
    unitAmount: Long,
    selectedTheme: VisualTheme,
    sentAmount: Long,
    sentCount: Long,
    isSending: Boolean,
    isEnding: Boolean,
    isComplete: Boolean,
    onUnitAmountChange: (Long) -> Unit,
    onThemeChange: (VisualTheme) -> Unit,
    onSendToken: () -> Unit,
    onEnd: () -> Unit,
    onHome: () -> Unit,
) {
    val view = LocalView.current
    var dragOffsetY by remember { mutableStateOf(0f) }
    var swipeConsumed by remember { mutableStateOf(false) }
    var launchKey by remember { mutableStateOf(0) }
    var launchedTheme by remember { mutableStateOf<VisualTheme?>(null) }
    var isLaunchAnimating by remember { mutableStateOf(false) }
    val dragVisualOffset = dragOffsetY.coerceIn(-150f, 0f).dp
    LaunchedEffect(launchKey) {
        if (launchKey > 0) { delay(790); isLaunchAnimating = false }
    }
    if (isComplete) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("전송을 마쳤어요", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text("${String.format(Locale.KOREA, "%,d", sentAmount)}원", color = selectedTheme.primary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("${String.format(Locale.KOREA, "%,d", unitAmount)}원 × ${sentCount}회", color = Color(0xFF64748B))
            Spacer(Modifier.height(32.dp))
            Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = selectedTheme.primary)) { Text("홈으로 돌아가기") }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF7F8FC)).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("$receiverName 님에게 보내기", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF171A24))
                Text("$connectionMethod 연결됨", color = Color(0xFF7B8496), fontSize = 13.sp)
            }
            Box(Modifier.background(selectedTheme.primary.copy(alpha = .12f), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) { Text("LIVE", color = selectedTheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        }
        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = selectedTheme.dark), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text("이번 세션에서 보낸 금액", color = Color.White.copy(alpha = .66f), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("${String.format(Locale.KOREA, "%,d", sentAmount)}원", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${sentCount}회 전송", color = Color.White.copy(alpha = .72f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp)).background(Color.White).border(1.dp, Color(0xFFE9ECF2), RoundedCornerShape(28.dp)).pointerInput(isSending, isEnding) {
                detectVerticalDragGestures(
                    onDragStart = { dragOffsetY = 0f; swipeConsumed = false },
                    onDragEnd = { dragOffsetY = 0f; swipeConsumed = false },
                    onDragCancel = { dragOffsetY = 0f; swipeConsumed = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (!isSending && !isEnding && !swipeConsumed) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtMost(0f)
                            if (dragOffsetY <= -64f) {
                                dragOffsetY = 0f; swipeConsumed = true; launchedTheme = selectedTheme; isLaunchAnimating = true; launchKey += 1
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                onSendToken()
                            }
                        }
                    },
                )
            }, contentAlignment = Alignment.Center,
        ) {
            if (!isLaunchAnimating) {
                Column(Modifier.offset(y = dragVisualOffset), horizontalAlignment = Alignment.CenterHorizontally) {
                    LargeTransferToken(selectedTheme)
                    Spacer(Modifier.height(12.dp))
                    Text("위로 밀어 ${String.format(Locale.KOREA, "%,d", unitAmount)}원 보내기", color = selectedTheme.primary, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            launchedTheme?.let { launched -> if (launchKey > 0) key(launchKey) { SingleTokenLaunchSequence(launched) } }
            if (isSending || isEnding) CircularProgressIndicator(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(22.dp), strokeWidth = 2.dp, color = selectedTheme.primary)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("재화 1개당 금액", fontWeight = FontWeight.SemiBold, color = Color(0xFF303746))
            Text("${String.format(Locale.KOREA, "%,d", unitAmount)}원", color = selectedTheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = TRANSFER_UNIT_OPTIONS.indexOf(unitAmount).coerceAtLeast(0).toFloat(),
            onValueChange = { position -> onUnitAmountChange(TRANSFER_UNIT_OPTIONS[position.roundToInt().coerceIn(0, TRANSFER_UNIT_OPTIONS.lastIndex)]) },
            valueRange = 0f..TRANSFER_UNIT_OPTIONS.lastIndex.toFloat(),
            steps = TRANSFER_UNIT_OPTIONS.size - 2,
            modifier = Modifier.fillMaxWidth().height(34.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = selectedTheme.primary, activeTrackColor = selectedTheme.primary),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TRANSFER_UNIT_OPTIONS.forEach { option -> Text(if (option >= 1_000) "${option / 1_000}천원" else "100원", color = if (option == unitAmount) selectedTheme.primary else Color(0xFF8A93A4), fontSize = 10.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("효과", modifier = Modifier.width(34.dp), fontWeight = FontWeight.SemiBold, color = Color(0xFF303746), fontSize = 13.sp)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                VisualTheme.entries.forEach { candidate ->
                    EffectTokenButton(candidate, candidate == selectedTheme, { onThemeChange(candidate) }, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onEnd, enabled = !isSending && !isEnding, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = selectedTheme.dark)) { Text("전송 종료", fontWeight = FontWeight.Bold) }
    }
}

@Composable
internal fun EffectTokenButton(theme: VisualTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, theme.primary.copy(alpha = if (selected) 1f else .28f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) theme.primary.copy(alpha = .08f) else Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        when (theme) {
            VisualTheme.PURPLE -> PurpleToken(size = 27.dp)
            VisualTheme.GOLD -> GoldCoin(size = 29.dp)
            VisualTheme.ROCKET -> RocketShip(size = 29.dp)
            VisualTheme.FLOWER -> FlowerToken(size = 29.dp)
            VisualTheme.HEART_BALLOON -> HeartBalloon(size = 27.dp)
            VisualTheme.PAPER_PLANE -> PaperPlane(size = 29.dp)
        }
    }
}

@Composable
private fun LargeTransferToken(theme: VisualTheme) {
    when (theme) {
        VisualTheme.PURPLE -> PurpleToken(size = 82.dp)
        VisualTheme.GOLD -> GoldCoin(size = 88.dp)
        VisualTheme.ROCKET -> RocketShip(size = 88.dp)
        VisualTheme.FLOWER -> FlowerToken(size = 88.dp)
        VisualTheme.HEART_BALLOON -> HeartBalloon(size = 82.dp)
        VisualTheme.PAPER_PLANE -> PaperPlane(size = 88.dp)
    }
}

@Composable
private fun AmountScreen(
    receiverName: String,
    connectionMethod: String,
    amount: Int,
    theme: VisualTheme,
    onAmountChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    var input by remember(amount) { mutableStateOf(amount.toString()) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Text("$receiverName 님에게 보낼 재화를 선택하세요")
        Text(
            "연결 확인 · $connectionMethod",
            color = theme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
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
private fun SendGestureScreen(
    amount: Int,
    theme: VisualTheme,
    isSending: Boolean,
    onBack: () -> Unit,
    onSendToken: (isFinalToken: Boolean) -> Unit,
    onLaunchAnimationFinished: () -> Unit,
) {
    val view = LocalView.current
    var completedSwipes by remember { mutableStateOf(0) }
    var launchKey by remember { mutableStateOf(0) }
    var finalLaunchKey by remember { mutableStateOf(0) }
    var swipeConsumed by remember { mutableStateOf(false) }
    // 드래그 이벤트 한 번의 이동량이 아니라 누적 이동량을 사용한다.
    // 손가락 이동을 즉시 그래픽에 반영해 잠금 해제처럼 반응하게 한다.
    var dragOffsetY by remember { mutableStateOf(0f) }
    val dragVisualOffset = dragOffsetY.coerceIn(-140f, 0f).dp
    LaunchedEffect(launchKey) {
        if (launchKey == 0) return@LaunchedEffect
        delay(
            when (theme) {
                VisualTheme.ROCKET -> 900L
                VisualTheme.FLOWER -> 760L
                VisualTheme.HEART_BALLOON -> 760L
                VisualTheme.PAPER_PLANE -> 700L
                else -> 650L
            },
        )
        if (launchKey == finalLaunchKey) onLaunchAnimationFinished()
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        if (completedSwipes < amount) {
            Text("전송 대기 중", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("${amount}개가 준비됐어요. 한 번씩 밀어 보내세요.", color = Color(0xFF64748B))
            Text("↑", color = theme.primary, fontSize = 90.sp)
        }
        Box(
            Modifier.fillMaxWidth().height(260.dp).pointerInput(isSending, completedSwipes) {
                detectVerticalDragGestures(
                    onDragStart = { dragOffsetY = 0f; swipeConsumed = false },
                    onDragEnd = { dragOffsetY = 0f; swipeConsumed = false },
                    onDragCancel = { dragOffsetY = 0f; swipeConsumed = false },
                    onVerticalDrag = { _, dragAmount ->
                        if (!isSending && !swipeConsumed && completedSwipes < amount) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtMost(0f)
                            if (dragOffsetY <= -64f) {
                                dragOffsetY = 0f
                                swipeConsumed = true
                                completedSwipes += 1
                                launchKey += 1
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                // 한 번의 스와이프가 재화 한 개의 실제 전송과 연결된다.
                                // 마지막 재화는 연출이 끝난 다음 수신 완료 상태로 전환한다.
                                val isFinalToken = completedSwipes == amount
                                if (isFinalToken) {
                                    finalLaunchKey = launchKey
                                }
                                onSendToken(isFinalToken)
                            }
                        }
                    },
                )
            }, contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.offset(y = dragVisualOffset),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TokenStack(theme = theme, remaining = amount - completedSwipes)
                Text(
                    "${completedSwipes} / ${amount}개\n위로 밀어 보내기",
                    color = theme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (launchKey > 0) {
                key(launchKey) { SingleTokenLaunchSequence(theme) }
            }
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(22.dp),
                    strokeWidth = 2.dp,
                    color = theme.primary,
                )
            }
        }
        Text(
            "한 번 밀 때마다 재화 1개가 전달돼요.",
            color = Color(0xFF64748B),
            fontSize = 13.sp,
        )
        ThemeButton(theme = theme, onClick = onBack, enabled = !isSending, modifier = Modifier.fillMaxWidth()) { Text("이전") }
    }
}

@Composable
private fun TokenStack(theme: VisualTheme, remaining: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (remaining == 0) {
            Text("모든 재화를 보냈어요", color = Color(0xFF64748B), fontSize = 14.sp)
        } else {
            val tokenCount = remaining.coerceAtMost(MAX_TRANSFER_AMOUNT)
            for (start in 0 until tokenCount step 5) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(minOf(5, tokenCount - start)) {
                        when (theme) {
                            VisualTheme.GOLD -> GoldCoin(size = 34.dp)
                            VisualTheme.ROCKET -> RocketShip(size = 38.dp)
                            VisualTheme.PURPLE -> PurpleToken(size = 38.dp)
                            VisualTheme.FLOWER -> FlowerToken(size = 38.dp)
                            VisualTheme.HEART_BALLOON -> HeartBalloon(size = 36.dp)
                            VisualTheme.PAPER_PLANE -> PaperPlane(size = 40.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleTokenLaunchSequence(theme: VisualTheme) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 모든 재화에 같은 '송금 모션 언어'를 적용한다. 에셋만 움직이는 데모 느낌을 줄이고
        // 사용자가 손가락으로 밀어 올린 순간의 속도와 방향을 화면 전체에서 읽을 수 있게 한다.
        TransferMotionTrail(theme)
        when (theme) {
            VisualTheme.PURPLE -> PurpleTokenLaunchSequence()
            VisualTheme.GOLD -> GoldCoinLaunchSequence(1)
            VisualTheme.ROCKET -> RocketLaunchSequence()
            VisualTheme.FLOWER -> FlowerLaunchSequence()
            VisualTheme.HEART_BALLOON -> HeartBalloonLaunchSequence()
            VisualTheme.PAPER_PLANE -> PaperPlaneLaunchSequence()
        }
    }
}

@Composable
private fun TransferMotionTrail(theme: VisualTheme) {
    var started by remember { mutableStateOf(false) }
    val height by animateDpAsState(if (started) 230.dp else 18.dp, tween(520), label = "transferTrailHeight")
    val width by animateDpAsState(if (started) 12.dp else 62.dp, tween(520), label = "transferTrailWidth")
    val alpha by animateFloatAsState(if (started) 0f else .7f, tween(520), label = "transferTrailAlpha")
    LaunchedEffect(Unit) { started = true }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = width, height = height)
                .background(
                    Brush.verticalGradient(listOf(theme.sparkle.copy(alpha = 0f), theme.primary.copy(alpha = .55f), theme.sparkle.copy(alpha = 0f))),
                    RoundedCornerShape(99.dp),
                )
                .alpha(alpha),
        )
        LottieTransferGlow(modifier = Modifier.size(156.dp).alpha(.8f))
    }
}

/** 자체 제작한 오픈 포맷 Lottie 파일. 외부 네트워크 없이 앱에 포함되어 즉시 재생된다. */
@Composable
private fun LottieTransferGlow(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.transfer_glow))
    val progress by animateLottieCompositionAsState(composition, iterations = 1, restartOnPlay = true)
    LottieAnimation(composition = composition, progress = { progress }, modifier = modifier)
}

@Composable
fun PurpleToken(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Image(painterResource(R.drawable.token_star_balloon_v1), "스타 벌룬 재화", modifier.size(size * 1.12f), contentScale = ContentScale.Fit)
}

@Composable
private fun PurpleTokenLaunchSequence() {
    var launched by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (launched) (-760).dp else 30.dp, tween(650), label = "purpleTokenLaunch")
    LaunchedEffect(Unit) { launched = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PurpleToken(Modifier.offset(y = y), size = 100.dp)
    }
}

@Composable
fun GoldCoin(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp) {
    Image(
        painter = painterResource(R.drawable.coin_premium_3d_v1),
        contentDescription = "입체 별 골드 코인",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size * 1.18f),
    )
}

@Composable
private fun GoldCoinLaunchSequence(count: Int) {
    val coinCount = count.coerceIn(1, MAX_TRANSFER_AMOUNT)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(coinCount) { index ->
            var launched by remember { mutableStateOf(false) }
            val y by animateDpAsState(if (launched) (-760).dp else 34.dp, tween(720), label = "coinLaunch$index")
            val x by animateDpAsState(if (launched) 38.dp else (-12).dp, tween(720), label = "coinLaunchArc$index")
            val rotation by animateFloatAsState(if (launched) 1080f else 0f, tween(720), label = "coinSpin$index")
            val tilt by animateFloatAsState(if (launched) -34f else 22f, tween(720), label = "coinLaunchTilt$index")
            val scale by animateFloatAsState(if (launched) .56f else 1f, tween(720), label = "coinLaunchScale$index")
            LaunchedEffect(Unit) { delay(index * 110L); launched = true }
            val lane = ((index - (coinCount - 1) / 2f) * 18f).dp
            val glowAlpha by animateFloatAsState(if (launched) 0f else .48f, tween(380), label = "coinGlow$index")
            Box(Modifier.offset(x = lane + x, y = y).size(72.dp).background(Color(0xFFFFD66B).copy(alpha = glowAlpha), CircleShape))
            GoldCoin(
                Modifier.offset(x = lane + x, y = y)
                    .rotate(rotation)
                    .graphicsLayer { rotationY = tilt; scaleX = scale; scaleY = scale; cameraDistance = 14f * density }
                    .alpha(if (launched) 1f else 0f),
                size = 64.dp,
            )
        }
    }
}

@Composable
private fun RocketLaunchSequence() {
    var launched by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (launched) (-800).dp else 64.dp, tween(760), label = "rocketLaunch")
    val scale by animateFloatAsState(if (launched) .72f else 1f, tween(760), label = "rocketLaunchScale")
    LaunchedEffect(Unit) { launched = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RocketShip(modifier = Modifier.offset(y = y).graphicsLayer { scaleX = scale; scaleY = scale }, size = 96.dp)
    }
}

@Composable
fun RocketShip(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Image(painterResource(R.drawable.token_rocket_v1), "로켓 재화", modifier.size(size), contentScale = ContentScale.Fit)
}

@Composable
fun HeartBalloon(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Image(painterResource(R.drawable.token_heart_balloon_v1), "하트 풍선 재화", modifier.size(size * .86f, size * 1.22f), contentScale = ContentScale.Fit)
}

@Composable
fun PaperPlane(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Image(painterResource(R.drawable.token_paper_plane_v1), "종이비행기 재화", modifier.size(size * 1.25f, size), contentScale = ContentScale.Fit)
}

@Composable
private fun HeartBalloonLaunchSequence() {
    var launched by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (launched) (-760).dp else 50.dp, tween(760), label = "heartBalloonLaunch")
    val x by animateDpAsState(if (launched) 42.dp else 0.dp, tween(760), label = "heartBalloonDrift")
    val rotation by animateFloatAsState(if (launched) 13f else -9f, tween(760), label = "heartBalloonSway")
    LaunchedEffect(Unit) { launched = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HeartBalloon(Modifier.offset(x = x, y = y).rotate(rotation), size = 88.dp)
    }
}

@Composable
private fun PaperPlaneLaunchSequence() {
    var launched by remember { mutableStateOf(false) }
    val x by animateDpAsState(if (launched) 370.dp else (-44).dp, tween(680), label = "paperPlaneLaunchX")
    val y by animateDpAsState(if (launched) (-560).dp else 64.dp, tween(680), label = "paperPlaneLaunchY")
    val rotation by animateFloatAsState(if (launched) -21f else 10f, tween(680), label = "paperPlaneLaunchRotation")
    LaunchedEffect(Unit) { launched = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val trailAlpha by animateFloatAsState(if (launched) 0f else .52f, tween(480), label = "planeTrailAlpha")
        Box(Modifier.offset(x = x - 76.dp, y = y + 34.dp).size(width = 128.dp, height = 4.dp).rotate(rotation).background(Color(0xFF8EB8FF).copy(alpha = trailAlpha), RoundedCornerShape(99.dp)))
        PaperPlane(Modifier.offset(x = x, y = y).rotate(rotation), size = 100.dp)
    }
}

@Composable
fun FlowerToken(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Image(painterResource(R.drawable.token_flower_single_v1), "꽃 한 송이 재화", modifier.size(size * .82f, size * 1.12f), contentScale = ContentScale.Fit)
}

@Composable
private fun FlowerLaunchSequence() {
    var launched by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (launched) (-740).dp else 42.dp, tween(760), label = "flowerLaunchY")
    val rotation by animateFloatAsState(if (launched) 540f else 0f, tween(760), label = "flowerLaunchRotation")
    LaunchedEffect(Unit) { launched = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(5) { index ->
            val petalY by animateDpAsState(if (launched) (-410 - index * 34).dp else 28.dp, tween(500 + index * 35), label = "flowerPetalY$index")
            val petalX by animateDpAsState(if (launched) ((index - 2) * 30).dp else 0.dp, tween(500 + index * 35), label = "flowerPetalX$index")
            val petalAlpha by animateFloatAsState(if (launched) 0f else .72f, tween(500), label = "flowerPetalAlpha$index")
            Box(Modifier.offset(x = petalX, y = petalY).size(12.dp).background(if (index % 2 == 0) Color(0xFFFFB7CB) else Color(0xFFFFD5E3), CircleShape).alpha(petalAlpha))
        }
        FlowerToken(Modifier.offset(y = y).rotate(rotation), size = 94.dp)
    }
}

@Composable
fun RocketLanding() {
    var descending by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (!descending) (-310).dp else if (!settled) 26.dp else 0.dp, tween(if (settled) 230 else 690), label = "rocketLanding")
    val impactSize by animateDpAsState(if (settled) 116.dp else 16.dp, tween(380), label = "rocketImpact")
    val impactAlpha by animateFloatAsState(if (settled) 0f else 1f, tween(520), label = "rocketImpactAlpha")
    LaunchedEffect(Unit) { descending = true; delay(690); settled = true }
    Box(Modifier.fillMaxSize().padding(bottom = 26.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.size(impactSize).alpha(impactAlpha).border(3.dp, Color(0xFFFBBF24), CircleShape))
        RocketShip(modifier = Modifier.offset(y = y), size = 82.dp)
    }
}

@Composable
private fun TransferCompleteScreen(
    amount: Int,
    theme: VisualTheme,
    isPreparingMore: Boolean,
    onHome: () -> Unit,
    onSendMore: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("전송 완료", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
        CelebrationSparkles(theme)
        Text("✨  ${amount}개를 보냈어요!  ✨", fontSize = 20.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.height(30.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("홈으로") }
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onSendMore, enabled = !isPreparingMore, modifier = Modifier.fillMaxWidth()) {
            Text(if (isPreparingMore) "추가 전송 준비 중…" else "추가로 보내기")
        }
        }
        PremiumCompletionConfetti(theme)
    }
}

/** 외부 오픈소스 파티클을 완료 시점에만 소량 재생한다. 금융 화면을 가리지 않도록 20개로 제한한다. */
@Composable
fun PremiumCompletionConfetti(theme: VisualTheme) {
    val parties = remember(theme) {
        listOf(
            Party(
                speed = 10f,
                maxSpeed = 18f,
                damping = .92f,
                spread = 70,
                colors = listOf(theme.primary.toArgb(), theme.sparkle.toArgb(), Color.White.toArgb()),
                position = Position.Relative(.5, .35),
                emitter = Emitter(duration = 180, TimeUnit.MILLISECONDS).max(20),
            ),
        )
    }
    KonfettiView(modifier = Modifier.fillMaxSize(), parties = parties)
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
        if (theme == VisualTheme.FLOWER) {
            Text("✿", color = theme.sparkle, fontSize = 34.sp, modifier = Modifier.align(Alignment.Center).offset(x = (-34).dp, y = -distance).alpha(alpha))
            Text("✿", color = theme.primary, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center).offset(x = 44.dp, y = distance).alpha(alpha))
        }
    }
}

private fun verifySession(
    id: String,
    theme: VisualTheme,
    connectionName: String,
    onFound: (ReceiverSession) -> Unit,
    onError: (String) -> Unit,
    onComplete: () -> Unit,
) {
    val db = FirebaseFirestore.getInstance()
    val sessionRef = db.collection("transferSessions").document(id)
    val senderId = FirebaseAuth.getInstance().currentUser?.uid
    if (senderId == null) { onError("로그인 정보를 찾을 수 없어요."); onComplete(); return }
    db.runTransaction<String> { tx ->
        val snapshot = tx.get(sessionRef)
        val receiverId = snapshot.getString("receiverId") ?: throw IllegalStateException("유효하지 않은 ${connectionName}이에요.")
        if (snapshot.getString("status") != "active") throw IllegalStateException("이미 사용되었거나 종료된 ${connectionName}이에요. 수신자가 다시 열어 주세요.")
        if (receiverId == senderId) throw IllegalStateException("내 ${connectionName}으로는 보낼 수 없어요.")
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

/** 한 번의 스와이프마다 선택한 금액 단위 하나만 정산한다. */
private fun transferOne(session: ReceiverSession, unitAmount: Long, theme: VisualTheme, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val sender = auth.currentUser ?: run { onError("로그인 정보를 찾을 수 없어요."); return }
    val db = FirebaseFirestore.getInstance()
    val sessionRef = db.collection("transferSessions").document(session.id)
    val senderRef = db.collection("users").document(sender.uid)
    val receiverRef = db.collection("users").document(session.receiverId)
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
    db.runTransaction { tx ->
        val liveSession = tx.get(sessionRef)
        val senderWallet = tx.get(senderRef)
        val receiverWallet = tx.get(receiverRef)
        val active = liveSession.getString("status") == "claimed" &&
            liveSession.getString("senderId") == sender.uid
        val unexpired = liveSession.getTimestamp("expiresAt")?.toDate()?.after(Date()) == true
        if (!active || !unexpired) throw IllegalStateException("세션이 만료되었어요. 다시 QR을 스캔해 주세요.")
        val deliveredCount = liveSession.getLong("deliveredCount") ?: 0L
        val balance = senderWallet.getLong("balance") ?: 0L
        if (balance < unitAmount) throw IllegalStateException("보유 잔액이 부족해요.")
        val singleLimit = senderWallet.getLong("singleSendLimit") ?: DEFAULT_SINGLE_SEND_LIMIT
        if (unitAmount > singleLimit) throw IllegalStateException("개인 설정의 재화 1개당 금액 한도를 초과했어요.")
        val sentToday = if (senderWallet.getString("dailyLimitDate") == today) senderWallet.getLong("dailySentAmount") ?: 0L else 0L
        val dailyLimit = senderWallet.getLong("dailySendLimit") ?: DEFAULT_DAILY_SEND_LIMIT
        if (sentToday + unitAmount > dailyLimit) throw IllegalStateException("개인 설정의 하루 전송 한도를 초과했어요.")
        val sessionAmount = liveSession.getLong("amount") ?: 0L
        tx.set(senderRef, mapOf("balance" to balance - unitAmount, "dailySentAmount" to sentToday + unitAmount, "dailyLimitDate" to today, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        tx.set(receiverRef, mapOf("balance" to (receiverWallet.getLong("balance") ?: 0L) + unitAmount, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        tx.update(
            sessionRef,
            "deliveredCount", deliveredCount + 1,
            "amount", sessionAmount + unitAmount,
            "visualTheme", theme.name,
            "lastDeliveredAt", FieldValue.serverTimestamp(),
        )
        null
    }.addOnSuccessListener { onSuccess() }.addOnFailureListener { error ->
        onError(transferFailureMessage(error))
    }
}

private fun finalizeTransfer(session: ReceiverSession, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val sender = FirebaseAuth.getInstance().currentUser ?: run { onError("로그인 정보를 찾을 수 없어요."); return }
    val db = FirebaseFirestore.getInstance()
    val sessionRef = db.collection("transferSessions").document(session.id)
    val proposedHistoryId = db.collection("transactions").document().id
    db.runTransaction { tx ->
        val liveSession = tx.get(sessionRef)
        val sessionStatus = liveSession.getString("status")
        val active = (sessionStatus == "claimed" || sessionStatus == "used") && liveSession.getString("senderId") == sender.uid
        val deliveredCount = liveSession.getLong("deliveredCount") ?: 0L
        val amount = liveSession.getLong("amount") ?: 0L
        if (!active) throw IllegalStateException("전송 상태를 확인하지 못했어요.")
        val historyId = liveSession.getString("historyId") ?: proposedHistoryId
        tx.update(sessionRef, "status", "used", "usedAt", FieldValue.serverTimestamp(), "historyId", historyId, "cleanupAt", terminalSessionCleanupAt())
        if (deliveredCount > 0 && amount > 0) {
            tx.set(db.collection("transactions").document(historyId), mapOf("senderId" to sender.uid, "receiverId" to session.receiverId, "amount" to amount, "type" to "transfer", "createdAt" to FieldValue.serverTimestamp()))
        }
        null
    }.addOnSuccessListener { onSuccess() }.addOnFailureListener { onError(transferFailureMessage(it)) }
}

/**
 * 완료된 세션을 같은 송신자만 다시 열 수 있게 한다.
 * 수신자는 기존 화면에서 바로 수신 대기 상태로 돌아가므로 재연결 과정이 필요 없다.
 */
private fun reopenTransferSession(session: ReceiverSession, theme: VisualTheme, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val sender = FirebaseAuth.getInstance().currentUser ?: run { onError("로그인 정보를 찾을 수 없어요."); return }
    val db = FirebaseFirestore.getInstance()
    val sessionRef = db.collection("transferSessions").document(session.id)
    db.runTransaction { tx ->
        val liveSession = tx.get(sessionRef)
        val canReopen = (liveSession.getString("status") == "used" || liveSession.getString("status") == "expired" || liveSession.getString("status") == "ready_for_more") &&
            liveSession.getString("senderId") == sender.uid
        if (!canReopen) throw IllegalStateException("추가 전송 세션을 다시 열 수 없어요. 다시 연결해 주세요.")
        tx.update(
            sessionRef,
            "status", "reopened",
            "visualTheme", theme.name,
            "amount", 0,
            "deliveredCount", 0,
            "expiresAt", Timestamp(Date(System.currentTimeMillis() + 60_000)),
            "reopenedAt", FieldValue.serverTimestamp(),
            "historyId", FieldValue.delete(),
            "cleanupAt", FieldValue.delete(),
        )
        null
    }.addOnSuccessListener { onSuccess() }.addOnFailureListener { onError(transferFailureMessage(it)) }
}

private fun transferFailureMessage(error: Exception): String {
    val networkFailure = error as? FirebaseFirestoreException
    return when (networkFailure?.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED ->
            "인터넷 연결이 불안정해 전송 결과를 확인하지 못했어요. 중복 전송을 피하기 위해 다시 보내지 말고, 최근 내역에서 먼저 결과를 확인해 주세요."
        else -> error.message ?: "전송을 완료하지 못했어요. 잔액과 최근 내역을 확인해 주세요."
    }
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
