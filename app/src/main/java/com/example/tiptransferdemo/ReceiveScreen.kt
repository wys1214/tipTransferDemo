package com.yunsi.tiptransferdemo

import android.graphics.Bitmap
import android.Manifest
import android.app.Activity
import android.os.Build
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.content.ComponentName
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

@Composable
fun ReceiveScreen(theme: VisualTheme, onBack: () -> Unit, onBackgroundExit: () -> Unit) {
    var sessionId by remember { mutableStateOf<String?>(null) }
    var bleToken by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("creating") }
    var secondsLeft by remember { mutableIntStateOf(60) }
    var receivedAmount by remember { mutableStateOf<Long?>(null) }
    var deliveredCount by remember { mutableStateOf(0L) }
    var sessionTheme by remember { mutableStateOf<VisualTheme?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // 기본 수신 방식은 NFC이며, 필요할 때 주변 BLE/QR로 전환한다.
    var nfcMode by remember { mutableStateOf(true) }
    var bleMode by remember { mutableStateOf(false) }
    val db = FirebaseFirestore.getInstance()
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    fun cancelSession() {
        sessionId?.let { id ->
            NfcSessionHostService.clearSession(id)
            if (status == "active" || status == "claimed" || status == "reopened" || status == "ready_for_more") {
                db.collection("transferSessions").document(id).update(
                    "status", "cancelled",
                    "cancelledAt", Timestamp.now(),
                    "cleanupAt", terminalSessionCleanupAt(),
                )
            }
        }
    }
    fun prepareAdditionalReceive() {
        val id = sessionId ?: run { error = "기존 수신 세션을 찾지 못했어요. 다시 받아 주세요."; return }
        db.runTransaction { tx ->
            val liveSession = tx.get(db.collection("transferSessions").document(id))
            if (liveSession.getString("status") != "used" && liveSession.getString("status") != "expired") {
                throw IllegalStateException("추가 수신 세션을 준비할 수 없어요. 다시 시도해 주세요.")
            }
            tx.update(
                db.collection("transferSessions").document(id),
                "status", "ready_for_more",
                "amount", 0,
                "deliveredCount", 0,
                "expiresAt", Timestamp(Date(System.currentTimeMillis() + 60_000)),
                "receiverReadyAt", Timestamp.now(),
                "cleanupAt", com.google.firebase.firestore.FieldValue.delete(),
            )
            null
        }.addOnSuccessListener { status = "ready_for_more" }
            .addOnFailureListener { error = it.message ?: "추가 수신을 준비하지 못했어요." }
    }
    fun finishReceive() {
        val id = sessionId ?: run { error = "수신 세션을 찾지 못했어요."; return }
        val sessionRef = db.collection("transferSessions").document(id)
        val proposedHistoryId = db.collection("transactions").document().id
        db.runTransaction { tx ->
            val liveSession = tx.get(sessionRef)
            val senderId = liveSession.getString("senderId") ?: ""
            val receiverId = liveSession.getString("receiverId") ?: ""
            val amount = liveSession.getLong("amount") ?: 0L
            val count = liveSession.getLong("deliveredCount") ?: 0L
            val historyId = liveSession.getString("historyId") ?: proposedHistoryId
            tx.update(
                sessionRef,
                "status", "used",
                "usedAt", Timestamp.now(),
                "historyId", historyId,
                "cleanupAt", terminalSessionCleanupAt(),
            )
            if (count > 0L && amount > 0L && senderId.isNotBlank() && receiverId.isNotBlank()) {
                tx.set(db.collection("transactions").document(historyId), mapOf(
                    "senderId" to senderId,
                    "receiverId" to receiverId,
                    "amount" to amount,
                    "type" to "transfer",
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                ))
            }
            null
        }.addOnFailureListener { error = it.message ?: "수신을 종료하지 못했어요." }
    }
    BackHandler { cancelSession(); onBack() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionId, status) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { cancelSession(); onBackgroundExit() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        sessionId = null
        bleToken = null
        status = "creating"
        receivedAmount = null
        deliveredCount = 0L
        sessionTheme = null
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { error = "사용자 정보를 찾지 못했어요."; return@LaunchedEffect }
        val oldSessions = db.collection("transferSessions").whereEqualTo("receiverId", uid).get().await()
        oldSessions.documents.filter {
            it.getString("status") == "active" || it.getString("status") == "claimed" ||
                it.getString("status") == "reopened" || it.getString("status") == "ready_for_more"
        }
            .forEach {
                it.reference.update(
                    "status", "cancelled",
                    "cancelledAt", Timestamp.now(),
                    "cleanupAt", terminalSessionCleanupAt(),
                )
        }
        val id = UUID.randomUUID().toString()
        val newBleToken = UUID.randomUUID().toString().replace("-", "").take(16)
        // BLE 목록에서는 users 문서를 추가로 읽지 않고, 활성 수신 세션에 저장된
        // 표시 이름을 사용한다. 연결 전에 필요한 조회를 한 번으로 제한한다.
        val receiverNickname = db.collection("users").document(uid).get().await()
            .getString("nickname")
            ?.takeIf { it.isNotBlank() }
            ?: "사용자"
        db.collection("transferSessions").document(id).set(mapOf(
            "receiverId" to uid,
            "receiverNickname" to receiverNickname,
            "bleToken" to newBleToken,
            "status" to "active",
            "createdAt" to Timestamp.now(),
        )).addOnSuccessListener { sessionId = id; bleToken = newBleToken; status = "active" }
            .addOnFailureListener { error = "QR을 준비하지 못했어요. 다시 시도해 주세요." }
    }

    DisposableEffect(sessionId) {
        val id = sessionId ?: return@DisposableEffect onDispose { }
        val listener = db.collection("transferSessions").document(id).addSnapshotListener { snapshot, _ ->
            val newStatus = snapshot?.getString("status") ?: return@addSnapshotListener
            status = newStatus
            receivedAmount = snapshot.getLong("amount")
            deliveredCount = snapshot.getLong("deliveredCount") ?: 0L
            snapshot.getString("visualTheme")?.let { savedTheme ->
                sessionTheme = VisualTheme.entries.firstOrNull { it.name == savedTheme }
            }
            if (newStatus == "claimed" || newStatus == "reopened" || newStatus == "ready_for_more") {
                val expiresAt = snapshot.getTimestamp("expiresAt")?.toDate()?.time ?: (System.currentTimeMillis() + 60_000)
                secondsLeft = ((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000).toInt()
            }
        }
        onDispose { listener.remove() }
    }

    LaunchedEffect(status, sessionId) {
        if (status != "claimed" && status != "reopened" && status != "ready_for_more") return@LaunchedEffect
        while (secondsLeft > 0 && (status == "claimed" || status == "reopened" || status == "ready_for_more")) { delay(1_000); secondsLeft -= 1 }
        if ((status == "claimed" || status == "reopened" || status == "ready_for_more") && sessionId != null) {
            db.collection("transferSessions").document(sessionId!!).update(
                "status", "expired",
                "expiredAt", Timestamp.now(),
                "cleanupAt", terminalSessionCleanupAt(),
            )
        }
    }
    LaunchedEffect(sessionId, nfcMode) {
        if (nfcMode) sessionId?.let(NfcSessionHostService::setSession)
    }
    LaunchedEffect(status, sessionId) {
        if (status == "used" || status == "expired" || status == "cancelled") NfcSessionHostService.clearSession(sessionId)
        if (status == "used") view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    val displayedTheme = sessionTheme ?: theme
    when (status) {
        "claimed", "reopened", "ready_for_more", "used", "expired" -> if (status != "expired" || deliveredCount > 0L) {
            ReceiveLiveSessionScreen(
                theme = displayedTheme,
                secondsLeft = secondsLeft,
                deliveredCount = deliveredCount,
                receivedAmount = receivedAmount ?: 0L,
                isComplete = status == "used" || status == "expired",
                completedByExpiry = status == "expired",
                onEnd = { finishReceive() },
                onAdditional = { prepareAdditionalReceive() },
                onHome = onBack,
            )
        } else Unit
        else -> if (bleMode) {
            BleReadyScreen(
                theme = displayedTheme,
                sessionId = bleToken,
                onUseNfc = { bleMode = false; nfcMode = true },
                onUseQr = { bleMode = false; nfcMode = false },
                onHome = { cancelSession(); onBack() },
            )
        } else if (nfcMode) {
            NfcReadyScreen(theme = displayedTheme, sessionId = sessionId, error = error, onUseBle = { NfcSessionHostService.clearSession(sessionId); nfcMode = false; bleMode = true }, onUseQr = { NfcSessionHostService.clearSession(sessionId); nfcMode = false }, onHome = { cancelSession(); onBack() })
        } else {
            QrReadyScreen(theme = displayedTheme, sessionId = sessionId, error = error, onUseNfc = { sessionId?.let(NfcSessionHostService::setSession); nfcMode = true }, onRefresh = { cancelSession(); refreshKey += 1 }, onHome = { cancelSession(); onBack() })
        }
    }
    if (status == "expired" && deliveredCount == 0L) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("세션이 만료되었어요") },
            text = { Text("60초 동안 받은 재화가 없어 수신 세션을 종료했어요.") },
            confirmButton = { TextButton(onClick = onBack) { Text("홈으로") } },
        )
    }
}

@Composable
private fun QrReadyScreen(theme: VisualTheme, sessionId: String?, error: String?, onUseNfc: () -> Unit, onRefresh: () -> Unit, onHome: () -> Unit) {
    val pack = LocalUiThemePack.current
    Column(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("팁 받기", color = pack.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = pack.fontFamily)
        Spacer(Modifier.height(8.dp))
        Text("QR을 보여주면 연결을 시작할 수 있어요.", color = pack.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Card(shape = RoundedCornerShape(pack.corner.dp), colors = CardDefaults.cardColors(containerColor = pack.surface)) {
            Box(Modifier.padding(24.dp).size(240.dp), contentAlignment = Alignment.Center) {
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    sessionId == null -> CircularProgressIndicator()
                    else -> Image(remember(sessionId) { createQrBitmap("tiptransfer://session/$sessionId") }.asImageBitmap(), "수신용 QR 코드", Modifier.fillMaxSize())
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("상대방이 QR을 스캔하면 60초 수신 대기가 시작돼요.", color = pack.ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(26.dp))
        ThemeButton(theme = theme, onClick = onUseNfc, modifier = Modifier.fillMaxWidth()) { Text("NFC로 받기") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("QR 새로고침") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) { Text("홈으로") }
    }
}

@Composable
private fun NfcReadyScreen(theme: VisualTheme, sessionId: String?, error: String?, onUseBle: () -> Unit, onUseQr: () -> Unit, onHome: () -> Unit) {
    val pack = LocalUiThemePack.current
    val context = LocalContext.current
    val activity = context.findActivity()
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val nfcEnabled = rememberNfcEnabled(context, adapter)
    DisposableEffect(adapter, activity, sessionId) {
        if (adapter != null && activity != null && sessionId != null) {
            try {
                CardEmulation.getInstance(adapter).setPreferredService(
                    activity,
                    ComponentName(context, NfcSessionHostService::class.java),
                )
            } catch (_: Exception) {
                // 기본 AID 라우팅이 가능한 기기에서는 우선 지정 실패 후에도 HCE가 동작할 수 있다.
            }
        }
        onDispose {
            if (adapter != null && activity != null) {
                try { CardEmulation.getInstance(adapter).unsetPreferredService(activity) } catch (_: Exception) { }
            }
        }
    }
    val hasHce = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    }
    val deviceStatus = when {
        adapter == null -> "이 기기는 NFC를 지원하지 않아요. QR로 받아 주세요."
        !nfcEnabled -> "NFC가 꺼져 있어요. 기기 설정에서 NFC를 켜 주세요."
        !hasHce -> "이 기기는 NFC 수신(HCE)을 지원하지 않아요. QR로 받아 주세요."
        sessionId == null || error != null -> error ?: "NFC 세션을 준비 중이에요."
        else -> "NFC 수신을 기다리고 있어요."
    }
    Column(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("NFC로 받기", color = pack.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = pack.fontFamily)
        Spacer(Modifier.height(16.dp))
        Text("휴대폰 뒷면을 상대방 휴대폰과 가까이 대세요.", color = pack.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("〰", color = pack.accent, fontSize = 84.sp)
        Text(deviceStatus, color = pack.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("연결이 되지 않으면 QR로 바로 받을 수 있어요.", color = theme.primary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        ThemeButton(theme = theme, onClick = onUseBle, modifier = Modifier.fillMaxWidth()) { Text("가까운 사람과 연결") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseQr, modifier = Modifier.fillMaxWidth()) { Text("NFC가 안 되나요? QR로 받기") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) { Text("홈으로") }
    }
}

@Composable
private fun BleReadyScreen(theme: VisualTheme, sessionId: String?, onUseNfc: () -> Unit, onUseQr: () -> Unit, onHome: () -> Unit) {
    val pack = LocalUiThemePack.current
    val context = LocalContext.current
    val host = remember { BleSessionHost(context) }
    var message by remember { mutableStateOf("주변 송신자를 기다리고 있어요.") }
    var granted by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result[Manifest.permission.BLUETOOTH_ADVERTISE] == true && result[Manifest.permission.BLUETOOTH_CONNECT] == true
    }
    var retryKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted, sessionId, retryKey) {
        if (!granted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else if (sessionId != null) {
            message = "주변 연결을 준비하는 중이에요."
            host.start(sessionId, onReady = { message = "BLE 수신 광고가 시작됐어요. 주변 송신자를 기다리고 있어요." }, onError = { message = it })
        }
    }
    DisposableEffect(Unit) { onDispose { host.stop() } }
    Column(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("주변에서 받기", color = pack.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = pack.fontFamily)
        Spacer(Modifier.height(16.dp))
        Text("상대방이 가까운 사람 찾기를 누르면 자동으로 연결돼요.", color = pack.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("⌁", color = pack.accent, fontSize = 84.sp)
        Text(if (granted) message else "주변 기기 권한을 허용해 주세요.", color = pack.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        ThemeButton(theme = theme, onClick = { retryKey += 1 }, modifier = Modifier.fillMaxWidth()) { Text("주변 연결 다시 시작") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseNfc, modifier = Modifier.fillMaxWidth()) { Text("NFC로 받기") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseQr, modifier = Modifier.fillMaxWidth()) { Text("QR로 받기") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) { Text("홈으로") }
    }
}

@Composable
private fun ReceiveLiveSessionScreen(
    theme: VisualTheme,
    secondsLeft: Int,
    deliveredCount: Long,
    receivedAmount: Long,
    isComplete: Boolean,
    completedByExpiry: Boolean = false,
    onEnd: () -> Unit,
    onAdditional: () -> Unit,
    onHome: () -> Unit,
) {
    val pack = LocalUiThemePack.current
    if (isComplete) {
        ReceiveSessionCompleteScreen(
            pack = pack,
            theme = theme,
            amount = receivedAmount,
            count = deliveredCount,
            completedByExpiry = completedByExpiry,
            onAdditional = onAdditional,
            onHome = onHome,
        )
        return
    }
    Column(
        Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TransferHeader(
            if (isComplete) "잘 받았어요" else "${pack.displayName} 재화를 받는 중",
            if (completedByExpiry) "60초 세션 종료 · 최종 수신 내역" else if (isComplete) "전송이 완료되었어요" else "${pack.displayName} 테마 · 상대방과 연결되어 있어요",
            isComplete,
        )
        TransferAmountCard("지금까지 받은 금액", receivedAmount, deliveredCount)
        Card(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(pack.corner.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(if (pack == UiThemePack.ARCADE) 2.dp else 1.dp, pack.accent.copy(alpha = .48f))) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(pack.backgroundTop.copy(alpha = .96f), pack.backgroundBottom.copy(alpha = .98f)),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                ReceivingTokenAssetScene(theme, deliveredCount, Modifier.fillMaxSize())
                // 모션 파일 하나가 도착·팡파레·마무리 타이밍을 일관되게 담당한다.
                ThemeReceiveCelebration(pack, deliveredCount, Modifier.fillMaxSize())
                if (deliveredCount == 0L) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("↓", color = pack.accent, fontSize = 42.sp, fontFamily = pack.fontFamily)
                    Spacer(Modifier.height(12.dp))
                    Text("${pack.displayName} 재화를 기다리고 있어요", color = pack.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = pack.fontFamily)
                    Text("도착하는 재화가 아래에 하나씩 모여요", color = pack.muted, fontSize = 13.sp, fontFamily = pack.fontFamily)
                }
                if (isComplete) PremiumCompletionConfetti(theme)
                if (deliveredCount > 12) Text("최근 12개 표시 · 총 ${deliveredCount}개 받음", color = pack.muted,
                    fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(14.dp))
            }
        }
        Text(if (isComplete) "${won(receivedAmount)} 수신 완료" else "연결 유지  ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}",
            color = pack.muted, fontSize = 13.sp)
        Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(pack.corner.dp), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) {
            Text("수신 종료", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ReceiveSessionCompleteScreen(
    pack: UiThemePack,
    theme: VisualTheme,
    amount: Long,
    count: Long,
    completedByExpiry: Boolean,
    onAdditional: () -> Unit,
    onHome: () -> Unit,
) {
    val view = LocalView.current
    LaunchedEffect(Unit) { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    Box(Modifier.fillMaxSize().themeAtmosphere(pack).safeDrawingPadding()) {
        // 화면 전환과 동시에 수신 순간과 같은 테마 팡파레를 다시 재생한다.
        ThemeReceiveCelebration(pack, count.coerceAtLeast(1L), Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Card(
                shape = RoundedCornerShape(pack.corner.dp),
                colors = CardDefaults.cardColors(containerColor = pack.surface.copy(alpha = .94f)),
                border = BorderStroke(2.dp, pack.accent.copy(alpha = .62f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(pack.iconRes), "${pack.displayName} 재화", Modifier.size(92.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        if (completedByExpiry) "세션이 종료되었어요" else "수신을 완료했어요",
                        color = pack.muted,
                        fontFamily = pack.fontFamily,
                        fontSize = 15.sp,
                    )
                    Text("${won(amount)} 받았어요", color = pack.ink, fontFamily = pack.fontFamily, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("총 ${count}개 재화", color = pack.accent, fontFamily = pack.fontFamily, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAdditional,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(pack.corner.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pack.accent),
            ) { Text("추가로 받기", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onHome,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(pack.corner.dp),
                border = BorderStroke(1.dp, pack.secondary),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = pack.surface.copy(alpha = .88f), contentColor = pack.ink),
            ) { Text("홈 화면으로", fontWeight = FontWeight.SemiBold) }
        }
    }
}
/** 수신할 때마다 오브젝트를 하나 더 보존해, 테마별로 쌓이는 기본 경험을 만든다. */
@Composable
private fun ReceivedTokenPile(theme: VisualTheme, count: Long) {
    var settledCount by remember(theme) { mutableStateOf(count) }
    var isIncoming by remember(theme) { mutableStateOf(false) }
    LaunchedEffect(count) {
        if (count > settledCount) {
            isIncoming = true
            // 재화가 중앙에서 한 번 멈추고 누적 자리로 자연스럽게 내려갈 시간을 보장한다.
            delay(820)
            settledCount = count
            isIncoming = false
        } else if (count < settledCount) {
            settledCount = count
            isIncoming = false
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 26.dp), contentAlignment = Alignment.BottomCenter) {
        if (theme == VisualTheme.GOLD) {
            repeat(settledCount.coerceAtMost(8).toInt()) { index ->
                key(index) { CoinPileItem(index) }
            }
        } else if (theme == VisualTheme.FLOWER) {
            FlowerBouquetPile(settledCount)
        } else repeat(settledCount.coerceAtMost(8).toInt()) { index ->
            val x = ((index % 4) - 1.5f) * 34
            val y = (-((index / 4) * 24)).dp
            Box(Modifier.offset(x = x.dp, y = y)) {
                when (theme) {
                    VisualTheme.ROCKET -> RocketShip(size = 46.dp)
                    VisualTheme.FLOWER -> FlowerToken(size = 48.dp)
                    VisualTheme.HEART_BALLOON -> HeartBalloon(size = 46.dp)
                    VisualTheme.PAPER_PLANE -> PaperPlane(size = 48.dp)
                    VisualTheme.PURPLE -> PurpleToken(size = 48.dp)
                    VisualTheme.GOLD -> Unit
                }
            }
        }
        // 도착 오브젝트의 목표 위치를 다음 누적 슬롯으로 전달한다.
        // 도착 애니메이션이 끝나면 같은 자리에 정적 에셋만 남아 겹침이 생기지 않는다.
        if (isIncoming) key(count) { IncomingTokenSequence(theme, settledCount.coerceAtMost(7).toInt()) }
    }
}

/** 한 송이씩 도착한 꽃이 기존 꽃 사이에 추가되어 작은 꽃다발이 된다. */
@Composable
private fun FlowerBouquetPile(count: Long) {
    val visible = count.coerceAtMost(8).toInt()
    repeat(visible) { index ->
        val column = index % 3
        val row = index / 3
        val x = ((column - 1) * 34).dp
        val y = (-row * 28 - if (column == 1) 8 else 0).dp
        val angle = if (column == 0) -14f else if (column == 2) 14f else 0f
        key(index) { FlowerToken(Modifier.offset(x = x, y = y).rotate(angle), size = 58.dp) }
    }
}

/** 이미 도착한 동전은 고정된 3D 정지 상태로만 남긴다. 매 재구성 시 다시 튀는 모션은 사용하지 않는다. */
@Composable
private fun CoinPileItem(index: Int) {
    val column = index % 3
    val row = index / 3
    val targetX = ((column - 1) * 62).dp
    val targetY = (-row * 27).dp
    GoldCoin(
        Modifier.offset(x = targetX, y = targetY)
            .graphicsLayer { rotationY = 62f + column * 7f; rotationZ = -8f + column * 8f; cameraDistance = 16f * density },
        size = 58.dp,
    )
}

@Composable
private fun IncomingTokenSequence(theme: VisualTheme, landingIndex: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 도착 순간에는 모든 테마에 동일한 파동과 짧은 확대 반응을 준다.
        // 수신자는 서버 응답을 기다리는 느낌보다 '방금 받았다'는 피드백을 즉시 인지한다.
        ArrivalPulse(theme)
        // 매 수신마다 작지만 분명한 축하 반짝임을 더한다. 완료 시에만 쓰는 Konfetti와 역할을 분리한다.
        CelebrationSparkles(theme)
        when (theme) {
            VisualTheme.GOLD -> GoldCoinLandingSequence(1)
            VisualTheme.ROCKET -> RocketLanding()
            VisualTheme.FLOWER -> FlowerLanding(landingIndex)
            VisualTheme.HEART_BALLOON -> HeartBalloonLanding()
            VisualTheme.PAPER_PLANE -> PaperPlaneLanding(landingIndex)
            VisualTheme.PURPLE -> PurpleToken(size = 92.dp)
        }
    }
}

@Composable
private fun ArrivalPulse(theme: VisualTheme) {
    var arrived by remember { mutableStateOf(false) }
    val outerSize by animateDpAsState(if (arrived) 176.dp else 42.dp, tween(560), label = "arrivalPulseSize")
    val innerSize by animateDpAsState(if (arrived) 108.dp else 24.dp, tween(420), label = "arrivalInnerPulseSize")
    val outerAlpha by animateFloatAsState(if (arrived) 0f else .34f, tween(560), label = "arrivalPulseAlpha")
    val innerAlpha by animateFloatAsState(if (arrived) 0f else .48f, tween(420), label = "arrivalInnerPulseAlpha")
    LaunchedEffect(Unit) { arrived = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LottieArrivalGlow(Modifier.size(190.dp).alpha(.76f))
        Box(Modifier.size(outerSize).background(theme.primary.copy(alpha = .22f), CircleShape).alpha(outerAlpha))
        Box(Modifier.size(innerSize).background(theme.sparkle.copy(alpha = .36f), CircleShape).alpha(innerAlpha))
    }
}

@Composable
private fun LottieArrivalGlow(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.transfer_glow))
    val progress by animateLottieCompositionAsState(composition, iterations = 1, restartOnPlay = true)
    LottieAnimation(composition = composition, progress = { progress }, modifier = modifier)
}

@Composable
private fun ReceiveCompleteScreen(theme: VisualTheme, amount: Long, onHome: () -> Unit) {
    var hasDropped by remember { mutableStateOf(false) }
    val coinOffset by animateDpAsState(
        targetValue = if (hasDropped) 0.dp else (-260).dp,
        animationSpec = tween(durationMillis = 520),
        label = "receiveCoinDrop",
    )
    val coinRotation by animateFloatAsState(
        targetValue = if (hasDropped && theme == VisualTheme.GOLD) 720f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "goldCoinDropRotation",
    )
    val particleScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 520),
        label = "particleDropScale",
    )
    LaunchedEffect(Unit) { hasDropped = true }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("수신 완료", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            CelebrationSparkles(theme)
            if (theme == VisualTheme.GOLD) {
                GoldCoinLandingSequence(amount.toInt())
            } else if (theme == VisualTheme.ROCKET) {
                RocketLanding()
            } else if (theme == VisualTheme.FLOWER) {
                FlowerLanding()
            } else if (theme == VisualTheme.HEART_BALLOON) {
                HeartBalloonLanding()
            } else if (theme == VisualTheme.PAPER_PLANE) {
                PaperPlaneLanding()
            } else {
                Column(
                    Modifier.offset(y = coinOffset),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PurpleToken(
                        modifier = Modifier.rotate(coinRotation).graphicsLayer {
                            scaleX = particleScale
                            scaleY = particleScale
                        },
                        size = 82.dp,
                    )
                }
            }
        }
        Text("✨  ${String.format(java.util.Locale.KOREA, "%,d", amount)}원을 받았어요!  ✨", fontSize = 20.sp)
        Spacer(Modifier.height(30.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("수신 종료") }
    }
}

@Composable
private fun HeartBalloonLanding() {
    var landed by remember { mutableStateOf(false) }
    val y by animateDpAsState(if (landed) 0.dp else (-240).dp, tween(760), label = "heartBalloonLandingY")
    val x by animateDpAsState(if (landed) 0.dp else 38.dp, tween(760), label = "heartBalloonLandingX")
    LaunchedEffect(Unit) { landed = true }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HeartBalloon(Modifier.offset(x = x, y = y), size = 88.dp)
    }
}

@Composable
private fun PaperPlaneLanding(landingIndex: Int = 0) {
    var phase by remember { mutableIntStateOf(0) }
    val column = landingIndex % 4
    val row = landingIndex / 4
    val targetX = ((column - 1.5f) * 34).dp
    val targetY = (145 - row * 24).dp
    val x by animateDpAsState(if (phase == 2) targetX else if (phase == 1) 0.dp else (-280).dp, tween(if (phase == 2) 390 else 570), label = "paperPlaneLandingX")
    val y by animateDpAsState(if (phase == 2) targetY else if (phase == 1) 0.dp else (-170).dp, tween(if (phase == 2) 390 else 570), label = "paperPlaneLandingY")
    val rotation by animateFloatAsState(if (phase == 2) -8f else if (phase == 1) 0f else 18f, tween(if (phase == 2) 390 else 570), label = "paperPlaneLandingRotation")
    LaunchedEffect(Unit) { phase = 1; delay(180); phase = 2 }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PaperPlane(Modifier.offset(x = x, y = y).rotate(rotation), size = 72.dp)
    }
}

@Composable
private fun FlowerLanding(landingIndex: Int = 0) {
    var phase by remember { mutableIntStateOf(0) }
    val column = landingIndex % 3
    val row = landingIndex / 3
    val targetX = ((column - 1) * 34).dp
    val targetY = (145 - row * 28 - if (column == 1) 8 else 0).dp
    val x by animateDpAsState(if (phase == 2) targetX else 0.dp, tween(if (phase == 2) 430 else 590), label = "flowerLandingX")
    val y by animateDpAsState(if (phase == 2) targetY else if (phase == 1) 0.dp else (-240).dp, tween(if (phase == 2) 430 else 590), label = "flowerLandingY")
    val rotation by animateFloatAsState(if (phase == 2) 0f else if (phase == 1) 0f else (-420f), tween(if (phase == 2) 430 else 590), label = "flowerLandingRotation")
    LaunchedEffect(Unit) { phase = 1; delay(180); phase = 2 }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FlowerToken(Modifier.offset(x = x, y = y).rotate(rotation), size = 72.dp)
    }
}

@Composable
private fun GoldCoinLandingSequence(count: Int, landingIndex: Int = 0) {
    val column = landingIndex % 3
    val row = landingIndex / 3
    val targetX = ((column - 1) * 62).dp
    val targetY = (145 - row * 27).dp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(count.coerceAtMost(10)) { index ->
            var phase by remember { mutableIntStateOf(0) }
            val y by animateDpAsState(if (phase == 2) targetY else if (phase == 1) 0.dp else (-290).dp, tween(if (phase == 2) 410 else 560), label = "coinLand$index")
            val x by animateDpAsState(if (phase == 2) targetX else 0.dp, tween(if (phase == 2) 410 else 560), label = "coinLandX$index")
            val rotation by animateFloatAsState(if (phase == 2) 810f + column * 8f else if (phase == 1) 720f else 0f, tween(if (phase == 2) 410 else 560), label = "coinLandSpin$index")
            val tilt by animateFloatAsState(if (phase == 2) 70f else if (phase == 1) 15f else -28f, tween(if (phase == 2) 410 else 560), label = "coinLandTilt$index")
            LaunchedEffect(Unit) { delay(index * 80L); phase = 1; delay(180); phase = 2 }
            GoldCoin(Modifier.offset(x = x, y = y).rotate(rotation).graphicsLayer { rotationY = tilt; cameraDistance = 14f * density }, size = 58.dp)
        }
    }
}

private fun createQrBitmap(content: String): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
    return Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until 512) for (y in 0 until 512) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
