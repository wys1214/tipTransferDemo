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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
            if (status == "active" || status == "claimed") {
                db.collection("transferSessions").document(id).update(
                    "status", "cancelled",
                    "cancelledAt", Timestamp.now(),
                    "cleanupAt", terminalSessionCleanupAt(),
                )
            }
        }
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
        sessionTheme = null
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { error = "사용자 정보를 찾지 못했어요."; return@LaunchedEffect }
        val oldSessions = db.collection("transferSessions").whereEqualTo("receiverId", uid).get().await()
        oldSessions.documents.filter { it.getString("status") == "active" || it.getString("status") == "claimed" }
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
            snapshot.getString("visualTheme")?.let { savedTheme ->
                sessionTheme = VisualTheme.entries.firstOrNull { it.name == savedTheme }
            }
            if (newStatus == "claimed") {
                val expiresAt = snapshot.getTimestamp("expiresAt")?.toDate()?.time ?: (System.currentTimeMillis() + 60_000)
                secondsLeft = ((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000).toInt()
            }
        }
        onDispose { listener.remove() }
    }

    LaunchedEffect(status, sessionId) {
        if (status != "claimed") return@LaunchedEffect
        while (secondsLeft > 0 && status == "claimed") { delay(1_000); secondsLeft -= 1 }
        if (status == "claimed" && sessionId != null) {
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
        "claimed" -> ReceivingWaitScreen(theme = displayedTheme, secondsLeft = secondsLeft, onCancel = { cancelSession(); refreshKey += 1 })
        "used" -> ReceiveCompleteScreen(theme = displayedTheme, amount = receivedAmount ?: 0L, onHome = onBack, onReceiveMore = { refreshKey += 1 })
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
}

@Composable
private fun QrReadyScreen(theme: VisualTheme, sessionId: String?, error: String?, onUseNfc: () -> Unit, onRefresh: () -> Unit, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("재화 받기", color = theme.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("QR을 보여주면 연결을 시작할 수 있어요.", color = Color(0xFF64748B), textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Box(Modifier.padding(24.dp).size(240.dp), contentAlignment = Alignment.Center) {
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    sessionId == null -> CircularProgressIndicator()
                    else -> Image(remember(sessionId) { createQrBitmap("tiptransfer://session/$sessionId") }.asImageBitmap(), "수신용 QR 코드", Modifier.fillMaxSize())
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("상대방이 QR을 스캔하면 60초 수신 대기가 시작돼요.", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(26.dp))
        ThemeButton(theme = theme, onClick = onUseNfc, modifier = Modifier.fillMaxWidth()) { Text("NFC로 받기") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("QR 새로고침") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("홈으로") }
    }
}

@Composable
private fun NfcReadyScreen(theme: VisualTheme, sessionId: String?, error: String?, onUseBle: () -> Unit, onUseQr: () -> Unit, onHome: () -> Unit) {
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
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("NFC로 받기", color = theme.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("휴대폰 뒷면을 상대방 휴대폰과 가까이 대세요.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("〰", color = theme.primary, fontSize = 84.sp)
        Text(deviceStatus, color = Color(0xFF64748B), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("연결이 되지 않으면 QR로 바로 받을 수 있어요.", color = theme.primary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        ThemeButton(theme = theme, onClick = onUseBle, modifier = Modifier.fillMaxWidth()) { Text("가까운 사람과 연결") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseQr, modifier = Modifier.fillMaxWidth()) { Text("NFC가 안 되나요? QR로 받기") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("홈으로") }
    }
}

@Composable
private fun BleReadyScreen(theme: VisualTheme, sessionId: String?, onUseNfc: () -> Unit, onUseQr: () -> Unit, onHome: () -> Unit) {
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
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("주변에서 받기", color = theme.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("상대방이 가까운 사람 찾기를 누르면 자동으로 연결돼요.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("⌁", color = theme.primary, fontSize = 84.sp)
        Text(if (granted) message else "주변 기기 권한을 허용해 주세요.", color = Color(0xFF64748B), textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        ThemeButton(theme = theme, onClick = { retryKey += 1 }, modifier = Modifier.fillMaxWidth()) { Text("주변 연결 다시 시작") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseNfc, modifier = Modifier.fillMaxWidth()) { Text("NFC로 받기") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseQr, modifier = Modifier.fillMaxWidth()) { Text("QR로 받기") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("홈으로") }
    }
}

@Composable
private fun ReceivingWaitScreen(theme: VisualTheme, secondsLeft: Int, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("수신 대기 중", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Text("↓", color = theme.primary, fontSize = 90.sp)
        Text("상대방이 재화를 보내는 중이에요.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("남은 시간 00:${secondsLeft.toString().padStart(2, '0')}", color = Color(0xFF64748B))
        Spacer(Modifier.height(30.dp))
        ThemeButton(theme = theme, onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("수신 취소") }
    }
}

@Composable
private fun ReceiveCompleteScreen(theme: VisualTheme, amount: Long, onHome: () -> Unit, onReceiveMore: () -> Unit) {
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
            } else if (theme == VisualTheme.PARTICLE) {
                ParticleGatheringSequence(theme)
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
        Text("✨  ${amount}개를 받았어요!  ✨", fontSize = 20.sp)
        Spacer(Modifier.height(30.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("홈으로") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onReceiveMore, modifier = Modifier.fillMaxWidth()) { Text("추가로 받기") }
    }
}

@Composable
private fun ParticleGatheringSequence(theme: VisualTheme) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(16) { index ->
            var gathered by remember { mutableStateOf(false) }
            val startX = ((index % 4) - 1.5f) * 48f
            val startY = ((index / 4) - 1.5f) * 42f
            val x by animateDpAsState(if (gathered) 0.dp else startX.dp, tween(620), label = "gatherX$index")
            val y by animateDpAsState(if (gathered) 0.dp else startY.dp, tween(620), label = "gatherY$index")
            val alpha by animateFloatAsState(if (gathered) 1f else .3f, tween(520), label = "gatherAlpha$index")
            LaunchedEffect(Unit) { delay(index * 32L); gathered = true }
            ParticleDot(
                modifier = Modifier.offset(x = x, y = y).alpha(alpha),
                size = if (index % 3 == 0) 16.dp else 11.dp,
                color = if (index % 2 == 0) theme.sparkle else theme.primary,
            )
        }
        Box(
            Modifier.size(70.dp).background(theme.primary.copy(alpha = .14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ParticleDot(size = 28.dp, color = theme.sparkle)
        }
    }
}

@Composable
private fun GoldCoinLandingSequence(count: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(count.coerceAtMost(10)) { index ->
            var landed by remember { mutableStateOf(false) }
            val y by animateDpAsState(if (landed) 0.dp else (-260).dp, tween(720), label = "coinLand$index")
            val rotation by animateFloatAsState(if (landed) 900f else 0f, tween(720), label = "coinLandSpin$index")
            LaunchedEffect(Unit) { delay(index * 110L); landed = true }
            val lane = ((index - (count.coerceAtMost(10) - 1) / 2f) * 14f).dp
            GoldCoin(Modifier.offset(x = lane, y = y).rotate(rotation), size = 50.dp)
        }
    }
}

private fun createQrBitmap(content: String): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
    return Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until 512) for (y in 0 until 512) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
