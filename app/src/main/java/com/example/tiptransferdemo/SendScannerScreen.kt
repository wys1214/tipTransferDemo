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
                Button(onClick = { message = null }) { Text("ÌôïÏù∏") }
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
            message = "Ïù¥ Í∏∞Í∏∞ÏóêÏÑúÎäî NFCÎ•º ÏÇ¨Ïö©Ìï† Ïàò ÏóÜÏñ¥Ïöî."
            return@DisposableEffect onDispose { }
        }
        if (!adapter.isEnabled) {
            message = "NFCÎ•º Ïº† Îí§ Îã§Ïãú ÏãúÎèÑÌï¥ Ï£ºÏÑ∏Ïöî."
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
                    activity.runOnUiThread { message = "NFC Ïó∞Í≤∞Ïóê Ïã§Ìå®ÌñàÏñ¥Ïöî. Îëê Í∏∞Í∏∞Î•º Îã§Ïãú Í∞ÄÍπåÏù¥ ÎåÄÏÑ∏Ïöî." }
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
        Text("NFCÎ°ú Î≥¥ÎÇ¥Í∏∞", color = theme.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("ÏàòÏã†ÏûêÏùò NFC Î∞õÍ∏∞ ÌôîÎ©¥ÏùÑ Ïó∞ Îí§\nÌú¥ÎåÄÌè∞ Îí∑Î©¥ÏùÑ Í∞ÄÍπåÏù¥ ÎåÄÏÑ∏Ïöî.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("„Ä∞", color = theme.primary, fontSize = 84.sp)
        Text(message ?: if (checking) "ÏàòÏã† ÏÑ∏ÏÖòÏùÑ ÌôïÏù∏ÌïòÎäî Ï§ëÏù¥ÏóêÏöî." else "NFC Ïó∞Í≤∞ÏùÑ Í∏∞Îã§Î¶¨Í≥† ÏûàÏñ¥Ïöî.", color = Color(0xFF64748B), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        ThemeButton(theme = theme, onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("QR Ïä§Ï∫îÏúºÎ°ú Ï†ÑÌôò") }
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
            Text("QRÏùÑ Ïä§Ï∫îÌïòÎ†§Î©¥ Ïπ¥Î©îÎùº Í∂åÌïúÏù¥ ÌïÑÏöîÌï¥Ïöî.")
            if (permissionRequested) {
                Text("Í∂åÌïúÏù¥ Í∫ºÏ†∏ ÏûàÏúºÎ©¥ Ïï± ÏÑ§Ï†ïÏóêÏÑú Ïπ¥Î©îÎùºÎ•º ÌóàÏö©Ìï¥ Ï£ºÏÑ∏Ïöî.", color = Color(0xFF64748B), fontSize = 14.sp)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            },
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                ) { Text("Ïï± ÏÑ§Ï†ï Ïó¥Í∏∞") }
                ThemeButton(theme = theme, onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Í∂åÌïú Îã§Ïãú ÏöîÏ≤≠") }
            } else {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) { Text("Ïπ¥Î©îÎùº Í∂åÌïú ÌóàÏö©") }
            }
            ThemeButton(theme = theme, onClick = onBack) { Text("ÌôàÏúºÎ°ú") }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color(0xFFF8FAFC))) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 54.dp).fillMaxWidth().background(theme.primary, RoundedCornerShape(24.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("QR Ïä§Ï∫î", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("ÏàòÏã†ÏûêÏùò QRÏùÑ ÌîÑÎ†àÏûÑ ÏïàÏóê ÎßûÏ∂∞ Ï£ºÏÑ∏Ïöî.", color = theme.sparkle, fontSize = 14.sp)
        }
        Box(
            Modifier.align(Alignment.Center).offset(y = (-34).dp).size(250.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black),
        ) {
            if (cameraError == null) {
                key(cameraRetryKey) {
                    QrCamera(
                        onDetected = { value ->
                            val id = value.substringAfter("tiptransfer://session/", "")
                            if (id.isBlank()) onMessage("Ïú†Ìö®ÌïòÏßÄ ÏïäÏùÄ QRÏù¥ÏóêÏöî.") else verify(id)
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
                    ) { Text("Ïπ¥Î©îÎùº Îã§Ïãú Ïó¥Í∏∞") }
                }
            }
            Box(Modifier.fillMaxSize().border(3.dp, theme.primary, RoundedCornerShape(28.dp)))
            Text("‚åÅ", color = theme.sparkle, fontSize = 26.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
      €ﬁ<∂âûÀk∫wµÁ@®Äƒ∏»¡ò§πâÖç≠ù…Ω’πê°Ω±Ω»†¡‡ÃÕÿ·§∞Å•…ç±ïM°Ö¡î§∞(ÄÄÄÄÄÄÄÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»∞(ÄÄÄÄ§ÅÏ(ÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÕ•Èî°Õ•Èî§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê†(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ	…’Õ†π…Öë•Ö±…Öë•ïπ–†(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçΩ±Ω…ÃÄÙÅ±•Õ—=ò°Ω±Ω»†¡·Õ¿§∞ÅΩ±Ω»†¡·		»–§∞ÅΩ±Ω»†¡·–‘Ã¿‰§§∞(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ§∞(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ•…ç±ïM°Ö¡î∞(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâΩ…ëï»†ƒ∏‘πë¿∞ÅΩ±Ω»†¡·›§∞Å•…ç±ïM°Ö¡î§∞(ÄÄÄÄÄÄÄÄÄÄÄÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»∞(ÄÄÄÄÄÄÄÄ§ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÕ•Èî°Õ•ÈîÄ®Ä∏‹…ò§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê°	…’Õ†π…Öë•Ö±…Öë•ïπ–°±•Õ—=ò°Ω±Ω»†¡·›§∞ÅΩ±Ω»†¡·‘Â¡§§§∞Å•…ç±ïM°Ö¡î§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâΩ…ëï»†ƒπë¿∞ÅΩ±Ω»†¡·–‘Ã¿‰§∞Å•…ç±ïM°Ö¡î§∞(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»∞(ÄÄÄÄÄÄÄÄÄÄÄÄ§ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅQï·–†ãäròà∞ÅçΩ±Ω»ÄÙÅΩ±Ω»†¡·‰»–¿¡§∞ÅôΩπ—M•ÈîÄÙÄ°Õ•ÈîπŸÖ±’îÄ®Ä∏–…ò§πÕ¿∞ÅôΩπ—]ï•ù°–ÄÙÅΩπ—]ï•ù°–π	Ω±ê§(ÄÄÄÄÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–πQΩ¡M—Ö…–§π¡Öëë•πú°Õ—Ö…–ÄÙÅÕ•ÈîÄ®Ä∏ƒ·ò∞Å—Ω¿ÄÙÅÕ•ÈîÄ®Ä∏ƒŸò§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπÕ•Èî°Õ•ÈîÄ®Ä∏ƒ—ò§πâÖç≠ù…Ω’πê°Ω±Ω»π]°•—îπçΩ¡‰°Ö±¡°ÑÄÙÄ∏‹’ò§∞Å•…ç±ïM°Ö¡î§∞(ÄÄÄÄÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)¡…•ŸÖ—îÅô’∏ÅΩ±ëΩ•π1Ö’πç°Mï≈’ïπçî°çΩ’π–ËÅ%π–§ÅÏ(ÄÄÄÅŸÖ∞ÅçΩ•πΩ’π–ÄÙÅçΩ’π–πçΩï…çï%∏†ƒ∞Å5a}QI9MI}5=U9P§(ÄÄÄÅ	Ω‡°5Ωë•ô•ï»πô•±±5Ö·M•Èî†§∞ÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»§ÅÏ(ÄÄÄÄÄÄÄÅ…ï¡ïÖ–°çΩ•πΩ’π–§ÅÏÅ•πëï‡Ä¥¯(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ»Å±Ö’πç°ïêÅâ‰Å…ïµïµâï»ÅÏÅµ’—Öâ±ïM—Ö—ï=ò°ôÖ±Õî§ÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å‰Åâ‰ÅÖπ•µÖ—ï¡ÕM—Ö—î°•òÄ°±Ö’πç°ïê§Ä†¥‹»¿§πë¿Åï±ÕîÄ»‡πë¿∞Å—›ïï∏†‹ÿ¿§∞Å±Öâï∞ÄÙÄâçΩ•π1Ö’πç†ë•πëï‡à§(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å…Ω—Ö—•Ω∏Åâ‰ÅÖπ•µÖ—ï±ΩÖ—ÕM—Ö—î°•òÄ°±Ö’πç°ïê§Ä‰¿¡òÅï±ÕîÄ¡ò∞Å—›ïï∏†‹ÿ¿§∞Å±Öâï∞ÄÙÄâçΩ•πM¡•∏ë•πëï‡à§(ÄÄÄÄÄÄÄÄÄÄÄÅ1Ö’πç°ïëôôïç–°Uπ•–§ÅÏÅëï±Ö‰°•πëï‡Ä®Äƒƒ¡0§ÏÅ±Ö’πç°ïêÄÙÅ—…’îÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å±ÖπîÄÙÄ†°•πëï‡Ä¥Ä°çΩ•πΩ’π–Ä¥Äƒ§ÄºÄ…ò§Ä®Äƒ·ò§πë¿(ÄÄÄÄÄÄÄÄÄÄÄÅΩ±ëΩ•∏°5Ωë•ô•ï»πΩôôÕï–°‡ÄÙÅ±Öπî∞Å‰ÄÙÅ‰§π…Ω—Ö—î°…Ω—Ö—•Ω∏§πÖ±¡°Ñ°•òÄ°±Ö’πç°ïê§Ä≈òÅï±ÕîÄ¡ò§∞ÅÕ•ÈîÄÙÄ‘ÿπë¿§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)¡…•ŸÖ—îÅô’∏ÅIΩç≠ï—1Ö’πç°Mï≈’ïπçî†§ÅÏ(ÄÄÄÅŸÖ»Å±Ö’πç°ïêÅâ‰Å…ïµïµâï»ÅÏÅµ’—Öâ±ïM—Ö—ï=ò°ôÖ±Õî§ÅÙ(ÄÄÄÅŸÖ∞Å‰Åâ‰ÅÖπ•µÖ—ï¡ÕM—Ö—î°•òÄ°±Ö’πç°ïê§Ä†¥‹ÿ¿§πë¿Åï±ÕîÄ‘‘πë¿∞Å—›ïï∏†‡‘¿§∞Å±Öâï∞ÄÙÄâ…Ωç≠ï—1Ö’πç†à§(ÄÄÄÅŸÖ∞Åô±ÖµïM•ÈîÅâ‰ÅÖπ•µÖ—ï±ΩÖ—ÕM—Ö—î°•òÄ°±Ö’πç°ïê§Äƒ∏ÕòÅï±ÕîÄ¿∏Ÿò∞Å—›ïï∏†»‘¿§∞Å±Öâï∞ÄÙÄâ…Ωç≠ï—±Öµîà§(ÄÄÄÅ1Ö’πç°ïëôôïç–°Uπ•–§ÅÏÅ±Ö’πç°ïêÄÙÅ—…’îÅÙ(ÄÄÄÅ	Ω‡°5Ωë•ô•ï»πô•±±5Ö·M•Èî†§∞ÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»§ÅÏ(ÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πΩôôÕï–°‰ÄÙÅ‰Ä¨Ä‘»πë¿§πÕ•Èî††‘»Ä®Åô±ÖµïM•Èî§πë¿∞Ä†‡»Ä®Åô±ÖµïM•Èî§πë¿§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê°	…’Õ†πŸï…—•çÖ±…Öë•ïπ–°±•Õ—=ò°Ω±Ω»†¡‡¿¡¿–‹§∞ÅΩ±Ω»†¡·‰‹Ãƒÿ§∞ÅΩ±Ω»†¡‡¿¡––––§§§∞ÅIΩ’πëïëΩ…πï…M°Ö¡î†‘¿§§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅIΩç≠ï—M°•¿°µΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πΩôôÕï–°‰ÄÙÅ‰§∞ÅÕ•ÈîÄÙÄƒ¿–πë¿§(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)ô’∏ÅIΩç≠ï—M°•¿°µΩë•ô•ï»ËÅ5Ωë•ô•ï»ÄÙÅ5Ωë•ô•ï»∞ÅÕ•ÈîËÅÖπë…Ω•ë‡πçΩµ¡ΩÕîπ’§π’π•–π¿ÄÙÄ‹»πë¿§ÅÏ(ÄÄÄÅ	Ω‡°µΩë•ô•ï»πÕ•Èî°Õ•ÈîÄ®Ä∏ÿ·ò∞ÅÕ•Èî§∞ÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–π	Ω——Ωµïπ—ï»§ÅÏ(ÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÕ•Èî°Õ•ÈîÄ®Ä∏»·ò∞ÅÕ•ÈîÄ®Ä∏Ã—ò§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê°	…’Õ†πŸï…—•çÖ±…Öë•ïπ–°±•Õ—=ò°Ω±Ω»†¡·¿–‹§∞ÅΩ±Ω»†¡·‹ƒ‡‘§∞ÅΩ±Ω»†¡‡¿¡‰‹Ãƒÿ§§§∞ÅIΩ’πëïëΩ…πï…M°Ö¡î†‘¿§§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπΩôôÕï–°‰ÄÙÅÕ•ÈîÄ®Ä∏ƒ…ò§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÕ•Èî°Õ•ÈîÄ®Ä∏ÿ¡ò∞ÅÕ•ÈîÄ®Ä∏‹·ò§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê°	…’Õ†πŸï…—•çÖ±…Öë•ïπ–°±•Õ—=ò°Ω±Ω»†¡··§∞ÅΩ±Ω»†¡·	’ƒ§∞ÅΩ±Ω»†¡·‰—Õ‡§§§∞ÅIΩ’πëïëΩ…πï…M°Ö¡î†‘¿§§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâΩ…ëï»†ƒπë¿∞ÅΩ±Ω»†¡·…·¿§∞ÅIΩ’πëïëΩ…πï…M°Ö¡î†‘¿§§∞(ÄÄÄÄÄÄÄÄÄÄÄÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πQΩ¡ïπ—ï»∞(ÄÄÄÄÄÄÄÄ§ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»π¡Öëë•πú°—Ω¿ÄÙÅÕ•ÈîÄ®Ä∏ƒ·ò§πÕ•Èî°Õ•ÈîÄ®Ä∏»…ò§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê°Ω±Ω»†¡·≈Õ·§∞Å•…ç±ïM°Ö¡î§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâΩ…ëï»†»πë¿∞ÅΩ±Ω»†¡·‰Õ’§∞Å•…ç±ïM°Ö¡î§∞(ÄÄÄÄÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–π	Ω——ΩµM—Ö…–§πΩôôÕï–°‡ÄÙÄ†µÕ•ÈîÄ®Ä∏¿›ò§∞Å‰ÄÙÄ†µÕ•ÈîÄ®Ä∏¿—ò§§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπÕ•Èî°Õ•ÈîÄ®Ä∏»…ò∞ÅÕ•ÈîÄ®Ä∏Ã¡ò§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê°Ω±Ω»†¡·»ÿ»ÿ§∞ÅIΩ’πëïëΩ…πï…M°Ö¡î†‡πë¿§§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–π	Ω——Ωµπê§πΩôôÕï–°‡ÄÙÅÕ•ÈîÄ®Ä∏¿›ò∞Å‰ÄÙÄ†µÕ•ÈîÄ®Ä∏¿—ò§§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπÕ•Èî°Õ•ÈîÄ®Ä∏»…ò∞ÅÕ•ÈîÄ®Ä∏Ã¡ò§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπâÖç≠ù…Ω’πê°Ω±Ω»†¡·»ÿ»ÿ§∞ÅIΩ’πëïëΩ…πï…M°Ö¡î†‡πë¿§§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)¡…•ŸÖ—îÅô’∏ÅAÖ…—•ç±ï	’…Õ—1Ö’πç°Mï≈’ïπçî°—°ïµîËÅY•Õ’Ö±Q°ïµî§ÅÏ(ÄÄÄÅŸÖ»Åâ’…Õ–Åâ‰Å…ïµïµâï»ÅÏÅµ’—Öâ±ïM—Ö—ï=ò°ôÖ±Õî§ÅÙ(ÄÄÄÅ1Ö’πç°ïëôôïç–°Uπ•–§ÅÏÅâ’…Õ–ÄÙÅ—…’îÅÙ(ÄÄÄÅ	Ω‡°5Ωë•ô•ï»πô•±±5Ö·M•Èî†§∞ÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»§ÅÏ(ÄÄÄÄÄÄÄÅ…ï¡ïÖ–†ƒÿ§ÅÏÅ•πëï‡Ä¥¯(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å·QÖ…ùï–ÄÙÄ†°•πëï‡ÄîÄ–§Ä¥Äƒ∏’ò§Ä®Ä‘—ò(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅÂQÖ…ùï–ÄÙÄ†°•πëï‡ÄºÄ–§Ä¥Äƒ∏’ò§Ä®Ä‘…òÄ¥Ä‰…ò(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å‡Åâ‰ÅÖπ•µÖ—ï¡ÕM—Ö—î°•òÄ°â’…Õ–§Å·QÖ…ùï–πë¿Åï±ÕîÄ¿πë¿∞Å—›ïï∏†ÿ‡¿§∞Å±Öâï∞ÄÙÄâ¡Ö…—•ç±ï`ë•πëï‡à§(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å‰Åâ‰ÅÖπ•µÖ—ï¡ÕM—Ö—î°•òÄ°â’…Õ–§ÅÂQÖ…ùï–πë¿Åï±ÕîÄ¿πë¿∞Å—›ïï∏†ÿ‡¿§∞Å±Öâï∞ÄÙÄâ¡Ö…—•ç±ïdë•πëï‡à§(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅÖ±¡°ÑÅâ‰ÅÖπ•µÖ—ï±ΩÖ—ÕM—Ö—î°•òÄ°â’…Õ–§Ä∏»·òÅï±ÕîÄ≈ò∞Å—›ïï∏†ÿ‡¿§∞Å±Öâï∞ÄÙÄâ¡Ö…—•ç±ï±¡°Ñë•πëï‡à§(ÄÄÄÄÄÄÄÄÄÄÄÅAÖ…—•ç±ïΩ–†(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πΩôôÕï–°‡ÄÙÅ‡∞Å‰ÄÙÅ‰§πÖ±¡°Ñ°Ö±¡°Ñ§∞(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕ•ÈîÄÙÅ•òÄ°•πëï‡ÄîÄÃÄÙÙÄ¿§Äƒ‘πë¿Åï±ÕîÄƒ¿πë¿∞(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçΩ±Ω»ÄÙÅ•òÄ°•πëï‡ÄîÄ»ÄÙÙÄ¿§Å—°ïµîπÕ¡Ö…≠±îÅï±ÕîÅ—°ïµîπ¡…•µÖ…‰∞(ÄÄÄÄÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)ô’∏ÅAÖ…—•ç±ïΩ–°µΩë•ô•ï»ËÅ5Ωë•ô•ï»ÄÙÅ5Ωë•ô•ï»∞ÅÕ•ÈîËÅÖπë…Ω•ë‡πçΩµ¡ΩÕîπ’§π’π•–π¿ÄÙÄƒ»πë¿∞ÅçΩ±Ω»ËÅΩ±Ω»ÄÙÅΩ±Ω»†¡·ÿ›·‰§§ÅÏ(ÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÅµΩë•ô•ï»πÕ•Èî°Õ•ÈîÄ®Äƒ∏›ò§πâÖç≠ù…Ω’πê°çΩ±Ω»πçΩ¡‰°Ö±¡°ÑÄÙÄ∏ƒ·ò§∞Å•…ç±ïM°Ö¡î§∞(ÄÄÄÄÄÄÄÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»∞(ÄÄÄÄ§ÅÏ(ÄÄÄÄÄÄÄÅ	Ω‡†(ÄÄÄÄÄÄÄÄÄÄÄÅ5Ωë•ô•ï»πÕ•Èî°Õ•Èî§πâÖç≠ù…Ω’πê°	…’Õ†π…Öë•Ö±…Öë•ïπ–°±•Õ—=ò°Ω±Ω»π]°•—î∞ÅçΩ±Ω»§§∞Å•…ç±ïM°Ö¡î§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)ô’∏ÅIΩç≠ï—1Öπë•πú†§ÅÏ(ÄÄÄÅŸÖ»Å±ÖπëïêÅâ‰Å…ïµïµâï»ÅÏÅµ’—Öâ±ïM—Ö—ï=ò°ôÖ±Õî§ÅÙ(ÄÄÄÅŸÖ∞Å‰Åâ‰ÅÖπ•µÖ—ï¡ÕM—Ö—î°•òÄ°±Öπëïê§Ä¿πë¿Åï±ÕîÄ†¥»»¿§πë¿∞Å—›ïï∏†‹‘¿§∞Å±Öâï∞ÄÙÄâ…Ωç≠ï—1Öπë•πúà§(ÄÄÄÅŸÖ∞Å•µ¡Öç—M•ÈîÅâ‰ÅÖπ•µÖ—ï¡ÕM—Ö—î°•òÄ°±Öπëïê§Äƒ¿‡πë¿Åï±ÕîÄ»¿πë¿∞Å—›ïï∏†––¿§∞Å±Öâï∞ÄÙÄâ…Ωç≠ï—%µ¡Öç–à§(ÄÄÄÅŸÖ∞Å•µ¡Öç—±¡°ÑÅâ‰ÅÖπ•µÖ—ï±ΩÖ—ÕM—Ö—î°•òÄ°±Öπëïê§Ä¡òÅï±ÕîÄ≈ò∞Å—›ïï∏†‘ÿ¿§∞Å±Öâï∞ÄÙÄâ…Ωç≠ï—%µ¡Öç—±¡°Ñà§(ÄÄÄÅ1Ö’πç°ïëôôïç–°Uπ•–§ÅÏÅ±ÖπëïêÄÙÅ—…’îÅÙ(ÄÄÄÅ	Ω‡°5Ωë•ô•ï»πô•±±5Ö·M•Èî†§∞ÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»§ÅÏ(ÄÄÄÄÄÄÄÅ	Ω‡°5Ωë•ô•ï»πÕ•Èî°•µ¡Öç—M•Èî§πÖ±¡°Ñ°•µ¡Öç—±¡°Ñ§πâΩ…ëï»†Ãπë¿∞ÅΩ±Ω»†¡·		»–§∞Å•…ç±ïM°Ö¡î§§(ÄÄÄÄÄÄÄÅIΩç≠ï—M°•¿°µΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πΩôôÕï–°‰ÄÙÅ‰§∞ÅÕ•ÈîÄÙÄ‹–πë¿§(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)¡…•ŸÖ—îÅô’∏ÅQ…ÖπÕôï…Ωµ¡±ï—ïMç…ïï∏°ÖµΩ’π–ËÅ%π–∞Å—°ïµîËÅY•Õ’Ö±Q°ïµî∞ÅΩπ!ΩµîËÄ†§Ä¥¯ÅUπ•–∞ÅΩπMïπë5Ω…îËÄ†§Ä¥¯ÅUπ•–§ÅÏ(ÄÄÄÅΩ±’µ∏°5Ωë•ô•ï»πô•±±5Ö·M•Èî†§π¡Öëë•πú†»–πë¿§∞Å°Ω…•ÈΩπ—Ö±±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï…!Ω…•ÈΩπ—Ö±±‰∞ÅŸï…—•çÖ±……Öπùïµïπ–ÄÙÅ……Öπùïµïπ–πïπ—ï»§ÅÏ(ÄÄÄÄÄÄÄÅQï·–†ã≤Ç≤ÑÉ≤fÆé0à∞ÅôΩπ—M•ÈîÄÙÄ»‡πÕ¿∞ÅôΩπ—]ï•ù°–ÄÙÅΩπ—]ï•ù°–π	Ω±ê§(ÄÄÄÄÄÄÄÅÖπë…Ω•ë‡πçΩµ¡ΩÕîπôΩ’πëÖ—•Ω∏π±ÖÂΩ’–πM¡Öçï»°5Ωë•ô•ï»π°ï•ù°–†ƒ‡πë¿§§(ÄÄÄÄÄÄÄÅï±ïâ…Ö—•ΩπM¡Ö…≠±ïÃ°—°ïµî§(ÄÄÄÄÄÄÄÅQï·–†ãär†ÄÄëÌÖµΩ’π—˜™¬sÆñÉÆŒ”Æ#≤Z”≤jPÑÄÉär†à∞ÅôΩπ—M•ÈîÄÙÄ»¿πÕ¿§(ÄÄÄÄÄÄÄÅÖπë…Ω•ë‡πçΩµ¡ΩÕîπôΩ’πëÖ—•Ω∏π±ÖÂΩ’–πM¡Öçï»°5Ωë•ô•ï»π°ï•ù°–†Ã¿πë¿§§(ÄÄÄÄÄÄÄÅ	’——Ω∏°Ωπ±•ç¨ÄÙÅΩπ!Ωµî∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πô•±±5Ö·]•ë—††§∞ÅçΩ±Ω…ÃÄÙÅ	’——ΩπïôÖ’±—Ãπâ’——ΩπΩ±Ω…Ã°çΩπ—Ö•πï…Ω±Ω»ÄÙÅ—°ïµîπ¡…•µÖ…‰§§ÅÏÅQï·–†ã∂f#≤rÛÆÜpà§ÅÙ(ÄÄÄÄÄÄÄÅÖπë…Ω•ë‡πçΩµ¡ΩÕîπôΩ’πëÖ—•Ω∏π±ÖÂΩ’–πM¡Öçï»°5Ωë•ô•ï»π°ï•ù°–†ƒ¿πë¿§§(ÄÄÄÄÄÄÄÅQ°ïµï	’——Ω∏°—°ïµîÄÙÅ—°ïµî∞ÅΩπ±•ç¨ÄÙÅΩπMïπë5Ω…î∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πô•±±5Ö·]•ë—††§§ÅÏÅQï·–†ã≤⁄S™¬ÆÜpÉÆŒ”Æ
”™‚¿à§ÅÙ(ÄÄÄÅÙ)Ù()Ωµ¡ΩÕÖâ±î)ô’∏Åï±ïâ…Ö—•ΩπM¡Ö…≠±ïÃ°—°ïµîËÅY•Õ’Ö±Q°ïµî§ÅÏ(ÄÄÄÅŸÖ»ÅÕ—Ö…—ïêÅâ‰Å…ïµïµâï»ÅÏÅµ’—Öâ±ïM—Ö—ï=ò°ôÖ±Õî§ÅÙ(ÄÄÄÅŸÖ∞Åë•Õ—ÖπçîÅâ‰ÅÖπ•µÖ—ï¡ÕM—Ö—î°•òÄ°Õ—Ö…—ïê§Ä‘»πë¿Åï±ÕîÄ¿πë¿∞Å—›ïï∏†–»¿§∞Å±Öâï∞ÄÙÄâÕ¡Ö…≠±ï•Õ—Öπçîà§(ÄÄÄÅŸÖ∞ÅÖ±¡°ÑÅâ‰ÅÖπ•µÖ—ï±ΩÖ—ÕM—Ö—î°•òÄ°Õ—Ö…—ïê§Ä≈òÅï±ÕîÄ¡ò∞Å—›ïï∏†ƒ‡¿§∞Å±Öâï∞ÄÙÄâÕ¡Ö…≠±ï±¡°Ñà§(ÄÄÄÅ1Ö’πç°ïëôôïç–°Uπ•–§ÅÏ(ÄÄÄÄÄÄÄÅÕ—Ö…—ïêÄÙÅ—…’î(ÄÄÄÄÄÄÄÅëï±Ö‰†ÿ‘¿§(ÄÄÄÄÄÄÄÅÕ—Ö…—ïêÄÙÅôÖ±Õî(ÄÄÄÅÙ(ÄÄÄÅ	Ω‡°5Ωë•ô•ï»πÕ•Èî†ƒ»¿πë¿§∞ÅçΩπ—ïπ—±•ùπµïπ–ÄÙÅ±•ùπµïπ–πïπ—ï»§ÅÏ(ÄÄÄÄÄÄÄÅQï·–†ãäròà∞ÅçΩ±Ω»ÄÙÅ—°ïµîπ¡…•µÖ…‰∞ÅôΩπ—M•ÈîÄÙÄ»‡πÕ¿∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–πïπ—ï»§πΩôôÕï–°‰ÄÙÄµë•Õ—Öπçî§πÖ±¡°Ñ°Ö±¡°Ñ§§(ÄÄÄÄÄÄÄÅQï·–†ãäròà∞ÅçΩ±Ω»ÄÙÅ—°ïµîπÕ¡Ö…≠±î∞ÅôΩπ—M•ÈîÄÙÄ»»πÕ¿∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–πïπ—ï»§πΩôôÕï–°‡ÄÙÅë•Õ—Öπçî∞Å‰ÄÙÄ†¥ƒ‡§πë¿§πÖ±¡°Ñ°Ö±¡°Ñ§§(ÄÄÄÄÄÄÄÅQï·–†ãäròà∞ÅçΩ±Ω»ÄÙÅ—°ïµîπ¡…•µÖ…‰∞ÅôΩπ—M•ÈîÄÙÄ»–πÕ¿∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–πïπ—ï»§πΩôôÕï–°‡ÄÙÄµë•Õ—Öπçî∞Å‰ÄÙÄƒ»πë¿§πÖ±¡°Ñ°Ö±¡°Ñ§§(ÄÄÄÄÄÄÄÅQï·–†ãäròà∞ÅçΩ±Ω»ÄÙÅ—°ïµîπÕ¡Ö…≠±î∞ÅôΩπ—M•ÈîÄÙÄ»¿πÕ¿∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–πïπ—ï»§πΩôôÕï–°‡ÄÙÄ»‡πë¿∞Å‰ÄÙÅë•Õ—Öπçî§πÖ±¡°Ñ°Ö±¡°Ñ§§(ÄÄÄÄÄÄÄÅ•òÄ°—°ïµîÄÙÙÅY•Õ’Ö±Q°ïµîπAIQ%1§ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÅQï·–†ã
‹à∞ÅçΩ±Ω»ÄÙÅ—°ïµîπÕ¡Ö…≠±î∞ÅôΩπ—M•ÈîÄÙÄÃ–πÕ¿∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–πïπ—ï»§πΩôôÕï–°‡ÄÙÄ†¥Ã–§πë¿∞Å‰ÄÙÄµë•Õ—Öπçî§πÖ±¡°Ñ°Ö±¡°Ñ§§(ÄÄÄÄÄÄÄÄÄÄÄÅQï·–†ãärúà∞ÅçΩ±Ω»ÄÙÅ—°ïµîπ¡…•µÖ…‰∞ÅôΩπ—M•ÈîÄÙÄ»‡πÕ¿∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πÖ±•ù∏°±•ùπµïπ–πïπ—ï»§πΩôôÕï–°‡ÄÙÄ––πë¿∞Å‰ÄÙÅë•Õ—Öπçî§πÖ±¡°Ñ°Ö±¡°Ñ§§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÅÙ)Ù()¡…•ŸÖ—îÅô’∏ÅŸï…•ôÂMïÕÕ•Ω∏°•êËÅM—…•πú∞Å—°ïµîËÅY•Õ’Ö±Q°ïµî∞ÅΩπΩ’πêËÄ°Iïçï•Ÿï…MïÕÕ•Ω∏§Ä¥¯ÅUπ•–∞ÅΩπ……Ω»ËÄ°M—…•πú§Ä¥¯ÅUπ•–∞ÅΩπΩµ¡±ï—îËÄ†§Ä¥¯ÅUπ•–§ÅÏ(ÄÄÄÅŸÖ∞ÅëàÄÙÅ•…ïâÖÕï•…ïÕ—Ω…îπùï—%πÕ—Öπçî†§(ÄÄÄÅŸÖ∞ÅÕïÕÕ•ΩπIïòÄÙÅëàπçΩ±±ïç—•Ω∏†â—…ÖπÕôï…MïÕÕ•ΩπÃà§πëΩç’µïπ–°•ê§(ÄÄÄÅŸÖ∞ÅÕïπëï…%êÄÙÅ•…ïâÖÕï’—†πùï—%πÕ—Öπçî†§πç’……ïπ—UÕï»¸π’•ê(ÄÄÄÅ•òÄ°Õïπëï…%êÄÙÙÅπ’±∞§ÅÏÅΩπ……Ω»†ãÆÜs™ﬁ„≤v‡É≤ÇWÆŒ”ÆñÉ≤¬˚≤vÉ≤"`É≤^≤Z”≤jP∏à§ÏÅΩπΩµ¡±ï—î†§ÏÅ…ï—’…∏ÅÙ(ÄÄÄÅëàπ…’πQ…ÖπÕÖç—•Ω∏ÒM—…•πú¯ÅÏÅ—‡Ä¥¯(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕπÖ¡Õ°Ω–ÄÙÅ—‡πùï–°ÕïÕÕ•ΩπIïò§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïçï•Ÿï…%êÄÙÅÕπÖ¡Õ°Ω–πùï—M—…•πú†â…ïçï•Ÿï…%êà§Ä¸ËÅ—°…Ω‹Å%±±ïùÖ±M—Ö—ï·çï¡—•Ω∏†ã≤rÉ∂j£∂Vc≤û É≤V+≤v ÅEK≤v”≤^C≤jP∏à§(ÄÄÄÄÄÄÄÅ•òÄ°ÕπÖ¡Õ°Ω–πùï—M—…•πú†âÕ—Ö—’Ãà§ÄÑÙÄâÖç—•Ÿîà§Å—°…Ω‹Å%±±ïùÖ±M—Ö—ï·çï¡—•Ω∏†ã≤v”Ææ‡É≤
≥≤j§É≤íG≤v”™∆√Æ
`É≤äÆé3ÆBpÅEK≤v”≤^C≤jP∏à§(ÄÄÄÄÄÄÄÅ•òÄ°…ïçï•Ÿï…%êÄÙÙÅÕïπëï…%ê§Å—°…Ω‹Å%±±ïùÖ±M—Ö—ï·çï¡—•Ω∏†ãÆ
–ÅEK≤^CÆ*PÉÆŒ”Æ
É≤"`É≤^≤Z”≤jP∏à§(ÄÄÄÄÄÄÄÅ—‡π’¡ëÖ—î†(ÄÄÄÄÄÄÄÄÄÄÄÅÕïÕÕ•ΩπIïò∞(ÄÄÄÄÄÄÄÄÄÄÄÄâÕ—Ö—’Ãà∞Äâç±Ö•µïêà∞(ÄÄÄÄÄÄÄÄÄÄÄÄâÕïπëï…%êà∞ÅÕïπëï…%ê∞(ÄÄÄÄÄÄÄÄÄÄÄÄâŸ•Õ’Ö±Q°ïµîà∞Å—°ïµîππÖµî∞(ÄÄÄÄÄÄÄÄÄÄÄÄâï·¡•…ïÕ–à∞ÅQ•µïÕ—Öµ¿°Ö—î°MÂÕ—ï¥πç’……ïπ—Q•µï5•±±•Ã†§Ä¨Äÿ¡|¿¿¿§§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅ…ïçï•Ÿï…%ê(ÄÄÄÅÙπÖëë=πM’ççïÕÕ1•Õ—ïπï»ÅÏÅ…ïçï•Ÿï…%êÄ¥¯(ÄÄÄÄÄÄÄÅëàπçΩ±±ïç—•Ω∏†â’Õï…Ãà§πëΩç’µïπ–°…ïçï•Ÿï…%ê§πùï–†§πÖëë=πM’ççïÕÕ1•Õ—ïπï»ÅÏÅ’Õï»Ä¥¯(ÄÄÄÄÄÄÄÄÄÄÄÅΩπΩ’πê°Iïçï•Ÿï…MïÕÕ•Ω∏°•ê∞Å…ïçï•Ÿï…%ê∞Å’Õï»πùï—M—…•πú†âπ•ç≠πÖµîà§Ä¸ËÄã≤Æ2Æ¬§à§§(ÄÄÄÄÄÄÄÅÙπÖëë=πÖ•±’…ï1•Õ—ïπï»ÅÏÅΩπ……Ω»†ã≤"c≤.É≤z@É≤ÇWÆŒ”ÆñÉÆ⁄#Æ~≥≤bì≤û ÉÆ™Ô∂Z#≤Z”≤jP∏à§ÅÙπÖëë=πΩµ¡±ï—ï1•Õ—ïπï»ÅÏÅΩπΩµ¡±ï—î†§ÅÙ(ÄÄÄÅÙπÖëë=πÖ•±’…ï1•Õ—ïπï»ÅÏÅΩπ……Ω»°•–πµïÕÕÖùîÄ¸ËÄã≤„≤c≤vÉ∂fW≤v„∂Vc≤û ÉÆ™Ô∂Z#≤Z”≤jP∏à§ÏÅΩπΩµ¡±ï—î†§ÅÙ)Ù()¡…•ŸÖ—îÅô’∏Å—…ÖπÕôï»°ÕïÕÕ•Ω∏ËÅIïçï•Ÿï…MïÕÕ•Ω∏∞ÅÖµΩ’π–ËÅ%π–∞ÅΩπM’ççïÕÃËÄ†§Ä¥¯ÅUπ•–∞ÅΩπ……Ω»ËÄ°M—…•πú§Ä¥¯ÅUπ•–§ÅÏ(ÄÄÄÅŸÖ∞ÅÖ’—†ÄÙÅ•…ïâÖÕï’—†πùï—%πÕ—Öπçî†§(ÄÄÄÅŸÖ∞ÅÕïπëï»ÄÙÅÖ’—†πç’……ïπ—UÕï»Ä¸ËÅ…’∏ÅÏÅΩπ……Ω»†ãÆÜs™ﬁ„≤v‡É≤ÇWÆŒ”ÆñÉ≤¬˚≤vÉ≤"`É≤^≤Z”≤jP∏à§ÏÅ…ï—’…∏ÅÙ(ÄÄÄÅŸÖ∞ÅëàÄÙÅ•…ïâÖÕï•…ïÕ—Ω…îπùï—%πÕ—Öπçî†§(ÄÄÄÅŸÖ∞ÅÕïÕÕ•ΩπIïòÄÙÅëàπçΩ±±ïç—•Ω∏†â—…ÖπÕôï…MïÕÕ•ΩπÃà§πëΩç’µïπ–°ÕïÕÕ•Ω∏π•ê§(ÄÄÄÅŸÖ∞ÅÕïπëï…IïòÄÙÅëàπçΩ±±ïç—•Ω∏†â’Õï…Ãà§πëΩç’µïπ–°Õïπëï»π’•ê§(ÄÄÄÅŸÖ∞Å…ïçï•Ÿï…IïòÄÙÅëàπçΩ±±ïç—•Ω∏†â’Õï…Ãà§πëΩç’µïπ–°ÕïÕÕ•Ω∏π…ïçï•Ÿï…%ê§(ÄÄÄÅŸÖ∞Å°•Õ—Ω…ÂIïòÄÙÅëàπçΩ±±ïç—•Ω∏†â—…ÖπÕÖç—•ΩπÃà§πëΩç’µïπ–†§(ÄÄÄÅŸÖ∞Å—ΩëÖ‰ÄÙÅM•µ¡±ïÖ—ïΩ…µÖ–†âÂÂÂ‰µ54µëêà∞Å1ΩçÖ±îπUL§πÖ¡¡±‰ÅÏÅ—•µïiΩπîÄÙÅQ•µïiΩπîπùï—Q•µïiΩπî†âÕ•ÑΩMïΩ’∞à§ÅÙπôΩ…µÖ–°Ö—î†§§(ÄÄÄÅëàπ…’πQ…ÖπÕÖç—•Ω∏ÅÏÅ—‡Ä¥¯(ÄÄÄÄÄÄÄÅŸÖ∞Å±•ŸïMïÕÕ•Ω∏ÄÙÅ—‡πùï–°ÕïÕÕ•ΩπIïò§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕïπëï…]Ö±±ï–ÄÙÅ—‡πùï–°Õïπëï…Iïò§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïçï•Ÿï…]Ö±±ï–ÄÙÅ—‡πùï–°…ïçï•Ÿï…Iïò§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖç—•ŸîÄÙÅ±•ŸïMïÕÕ•Ω∏πùï—M—…•πú†âÕ—Ö—’Ãà§ÄÙÙÄâç±Ö•µïêàÄòòÅ±•ŸïMïÕÕ•Ω∏πùï—M—…•πú†âÕïπëï…%êà§ÄÙÙÅÕïπëï»π’•ê(ÄÄÄÄÄÄÄÅŸÖ∞Å’πï·¡•…ïêÄÙÅ±•ŸïMïÕÕ•Ω∏πùï—Q•µïÕ—Öµ¿†âï·¡•…ïÕ–à§¸π—ΩÖ—î†§¸πÖô—ï»°Ö—î†§§ÄÙÙÅ—…’î(ÄÄÄÄÄÄÄÅ•òÄ†ÖÖç—•ŸîÅÒÄÖ’πï·¡•…ïê§Å—°…Ω‹Å%±±ïùÖ±M—Ö—ï·çï¡—•Ω∏†ã≤„≤c≤v–ÉÆû3Æé3ÆBc≤^#≤Z”≤jP∏ÉÆ.ì≤.pÅEK≤vÉ≤*ì≤ÍS∂V–É≤éÛ≤„≤jP∏à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâÖ±ÖπçîÄÙÅÕïπëï…]Ö±±ï–πùï—1Ωπú†ââÖ±Öπçîà§Ä¸ËÄ¡0(ÄÄÄÄÄÄÄÅ•òÄ°âÖ±ÖπçîÄÅÖµΩ’π–§Å—°…Ω‹Å%±±ïùÖ±M—Ö—ï·çï¡—•Ω∏†ãÆŒ”≤rÄÉ≤z≥∂fS™¬ ÉÆ⁄≤Ü«∂V”≤jP∏à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕïπ—QΩëÖ‰ÄÙÅ•òÄ°Õïπëï…]Ö±±ï–πùï—M—…•πú†âëÖ•±Â1•µ•—Ö—îà§ÄÙÙÅ—ΩëÖ‰§ÅÕïπëï…]Ö±±ï–πùï—1Ωπú†âëÖ•±ÂMïπ—µΩ’π–à§Ä¸ËÄ¡0Åï±ÕîÄ¡0(ÄÄÄÄÄÄÄÅ•òÄ°Õïπ—QΩëÖ‰Ä¨ÅÖµΩ’π–Ä¯Å%1e}M9}1%5%P§Å—°…Ω‹Å%±±ïùÖ±M—Ö—ï·çï¡—•Ω∏†ã∂VcÆé£≤^@ÉÆŒ”Æ
É≤"`É≤z#Æ*PÉ≤÷sÆ2 É≤"cÆ~'≤v–É≤“#™ŒÛÆBc≤^#≤Z”≤jP∏à§(ÄÄÄÄÄÄÄÅ—‡πÕï–°Õïπëï…Iïò∞ÅµÖ¡=ò†ââÖ±ÖπçîàÅ—ºÅâÖ±ÖπçîÄ¥ÅÖµΩ’π–∞ÄâëÖ•±ÂMïπ—µΩ’π–àÅ—ºÅÕïπ—QΩëÖ‰Ä¨ÅÖµΩ’π–∞ÄâëÖ•±Â1•µ•—Ö—îàÅ—ºÅ—ΩëÖ‰∞Äâ’¡ëÖ—ïë–àÅ—ºÅ•ï±ëYÖ±’îπÕï…Ÿï…Q•µïÕ—Öµ¿†§§∞ÅMï—=¡—•ΩπÃπµï…ùî†§§(ÄÄÄÄÄÄÄÅ—‡πÕï–°…ïçï•Ÿï…Iïò∞ÅµÖ¡=ò†ââÖ±ÖπçîàÅ—ºÄ°…ïçï•Ÿï…]Ö±±ï–πùï—1Ωπú†ââÖ±Öπçîà§Ä¸ËÄ¡0§Ä¨ÅÖµΩ’π–∞Äâ’¡ëÖ—ïë–àÅ—ºÅ•ï±ëYÖ±’îπÕï…Ÿï…Q•µïÕ—Öµ¿†§§∞ÅMï—=¡—•ΩπÃπµï…ùî†§§(ÄÄÄÄÄÄÄÅ—‡π’¡ëÖ—î†(ÄÄÄÄÄÄÄÄÄÄÄÅÕïÕÕ•ΩπIïò∞(ÄÄÄÄÄÄÄÄÄÄÄÄâÕ—Ö—’Ãà∞Äâ’Õïêà∞(ÄÄÄÄÄÄÄÄÄÄÄÄâ’Õïë–à∞Å•ï±ëYÖ±’îπÕï…Ÿï…Q•µïÕ—Öµ¿†§∞(ÄÄÄÄÄÄÄÄÄÄÄÄâç±ïÖπ’¡–à∞Å—ï…µ•πÖ±MïÕÕ•Ωπ±ïÖπ’¡–†§∞(ÄÄÄÄÄÄÄÄÄÄÄÄâÕïπëï…%êà∞ÅÕïπëï»π’•ê∞(ÄÄÄÄÄÄÄÄÄÄÄÄâÖµΩ’π–à∞ÅÖµΩ’π–∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅ—‡πÕï–°°•Õ—Ω…ÂIïò∞ÅµÖ¡=ò†âÕïπëï…%êàÅ—ºÅÕïπëï»π’•ê∞Äâ…ïçï•Ÿï…%êàÅ—ºÅÕïÕÕ•Ω∏π…ïçï•Ÿï…%ê∞ÄâÖµΩ’π–àÅ—ºÅÖµΩ’π–∞Äâ—Â¡îàÅ—ºÄâ—…ÖπÕôï»à∞Äâç…ïÖ—ïë–àÅ—ºÅ•ï±ëYÖ±’îπÕï…Ÿï…Q•µïÕ—Öµ¿†§§§(ÄÄÄÄÄÄÄÅπ’±∞(ÄÄÄÅÙπÖëë=πM’ççïÕÕ1•Õ—ïπï»ÅÏÅΩπM’ççïÕÃ†§ÅÙπÖëë=πÖ•±’…ï1•Õ—ïπï»ÅÏÅï……Ω»Ä¥¯ÅΩπ……Ω»°ï……Ω»πµïÕÕÖùîÄ¸ËÄã≤Ç≤á≤vÉ≤fÆé3∂Vc≤û ÉÆ™Ô∂Z#≤Z”≤jP∏à§ÅÙ)Ù()Ωµ¡ΩÕÖâ±î)=¡—%∏°·¡ï…•µïπ—Ö±ï—%µÖùîËÈç±ÖÕÃ§)¡…•ŸÖ—îÅô’∏ÅE…Öµï…Ñ†(ÄÄÄÅΩπï—ïç—ïêËÄ°M—…•πú§Ä¥¯ÅUπ•–∞(ÄÄÄÅΩπÖµï…ÖIïÖë‰ËÄ†°	ΩΩ±ïÖ∏§Ä¥¯ÅUπ•–§Ä¥¯ÅUπ•–∞(ÄÄÄÅΩπ……Ω»ËÄ°M—…•πú§Ä¥¯ÅUπ•–∞(§ÅÏ(ÄÄÄÅŸÖ∞ÅΩ›πï»ÄÙÅ1ΩçÖ±1•ôïçÂç±ï=›πï»πç’……ïπ–(ÄÄÄÅπë…Ω•ëY•ï‹°ôÖç—Ω…‰ÄÙÅÏÅç—‡Ä¥¯(ÄÄÄÄÄÄÄÅA…ïŸ•ï›Y•ï‹°ç—‡§πÖ±ÕºÅÏÅŸ•ï‹Ä¥¯(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å¡…ΩŸ•ëï»ÄÙÅA…ΩçïÕÕÖµï…ÖA…ΩŸ•ëï»πùï—%πÕ—Öπçî°ç—‡§(ÄÄÄÄÄÄÄÄÄÄÄÅ¡…ΩŸ•ëï»πÖëë1•Õ—ïπï»°Ï(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—…‰ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅçÖµï…ÖA…ΩŸ•ëï»ÄÙÅ¡…ΩŸ•ëï»πùï–†§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å¡…ïŸ•ï‹ÄÙÅÖπë…Ω•ë‡πçÖµï…ÑπçΩ…îπA…ïŸ•ï‹π	’•±ëï»†§πâ’•±ê†§πÖ±ÕºÅÏÅ•–πÕ’…ôÖçïA…ΩŸ•ëï»ÄÙÅŸ•ï‹πÕ’…ôÖçïA…ΩŸ•ëï»ÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅÕçÖππï»ÄÙÅ	Ö…çΩëïMçÖππ•πúπùï—±•ïπ–†§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅÖπÖ±ÂÕ•ÃÄÙÅ%µÖùïπÖ±ÂÕ•Ãπ	’•±ëï»†§πÕï—	Öç≠¡…ïÕÕ’…ïM—…Ö—ïù‰°%µÖùïπÖ±ÂÕ•ÃπMQIQe}-A}=91e}1QMP§πâ’•±ê†§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÖπÖ±ÂÕ•ÃπÕï—πÖ±ÂÈï»°Ωπ—ï·—Ωµ¡Ö–πùï—5Ö•π·ïç’—Ω»°ç—‡§§ÅÏÅ•µÖùîÄ¥¯(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Åµïë•ÑÄÙÅ•µÖùîπ•µÖùî(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ•òÄ°µïë•ÑÄÙÙÅπ’±∞§ÅÏÅ•µÖùîπç±ΩÕî†§ÏÅ…ï—’…πÕï—πÖ±ÂÈï»ÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕçÖππï»π¡…ΩçïÕÃ°çΩ¥πùΩΩù±îπµ±≠•–πŸ•Õ•Ω∏πçΩµµΩ∏π%π¡’—%µÖùîπô…Ωµ5ïë•Ö%µÖùî°µïë•Ñ∞Å•µÖùîπ•µÖùï%πôºπ…Ω—Ö—•Ωπïù…ïïÃ§§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπÖëë=πM’ççïÕÕ1•Õ—ïπï»ÅÏÅçΩëïÃÄ¥¯ÅçΩëïÃπô•…Õ—=…9’±∞ÅÏÅ•–πôΩ…µÖ–ÄÙÙÅ	Ö…çΩëîπ=I5Q}EI}=ÅÙ¸π…Ö›YÖ±’î¸π±ï–°Ωπï—ïç—ïê§ÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπÖëë=πΩµ¡±ï—ï1•Õ—ïπï»ÅÏÅ•µÖùîπç±ΩÕî†§ÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçÖµï…ÖA…ΩŸ•ëï»π’πâ•πë±∞†§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅçÖµï…ÑÄÙÅçÖµï…ÖA…ΩŸ•ëï»πâ•πëQΩ1•ôïçÂç±î°Ω›πï»∞ÅÖµï…ÖMï±ïç—Ω»πU1Q}	-}5I∞Å¡…ïŸ•ï‹∞ÅÖπÖ±ÂÕ•Ã§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅΩπÖµï…ÖIïÖë‰ÅÏÅïπÖâ±ïêÄ¥¯ÅçÖµï…ÑπçÖµï…ÖΩπ—…Ω∞πïπÖâ±ïQΩ…ç†°ïπÖâ±ïê§ÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÙÅçÖ—ç†Ä°|ËÅ·çï¡—•Ω∏§ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅΩπ……Ω»†ã≤Ê”Æ¶SÆvÛÆñÉ≤^”≤û ÉÆ™Ô∂Z#≤Z”≤jP∏ÉÆ.ìÆñ‡É≤V«≤^C≤pÉ≤Ê”Æ¶SÆvÛÆñÉ≤
≥≤j§É≤íG≤v„≤û É∂fW≤v„∂V–É≤éÛ≤„≤jP∏à§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÄÄÄÄÅÙ∞ÅΩπ—ï·—Ωµ¡Ö–πùï—5Ö•π·ïç’—Ω»°ç—‡§§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÅÙ∞ÅµΩë•ô•ï»ÄÙÅ5Ωë•ô•ï»πô•±±5Ö·M•Èî†§§)Ù(