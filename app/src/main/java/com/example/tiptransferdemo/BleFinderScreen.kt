package com.yunsi.tiptransferdemo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay

@Composable
fun BleFinderScreen(
    theme: VisualTheme,
    onSessionId: (String) -> Unit,
    onUseNfc: () -> Unit,
    onUseQr: () -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val pack = LocalUiThemePack.current
    val adapter = remember { BluetoothAdapter.getDefaultAdapter() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val candidates = remember { mutableStateListOf<ScanResult>() }
    val receiverNames = remember { mutableStateMapOf<String, String>() }
    var message by remember { mutableStateOf("가까운 사람을 찾는 중이에요.") }
    var retryKey by remember { mutableIntStateOf(0) }
    var rawSignalCount by remember { mutableIntStateOf(0) }
    var showQrFallback by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        } else {
            result[Manifest.permission.BLUETOOTH_SCAN] == true && result[Manifest.permission.BLUETOOTH_CONNECT] == true
        }
    }
    DisposableEffect(adapter, granted, retryKey) {
        if (!granted) {
            val permissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionLauncher.launch(permissions)
        }
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val scanner = adapter?.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                mainHandler.post {
                    rawSignalCount += 1
                    val record = result.scanRecord
                    val payload = record
                        ?.getManufacturerSpecificData(BleSessionHost.MANUFACTURER_ID)
                    val token = payload?.let(::decodeAdvertisedToken)
                    // 최신 Android는 광고 주소를 주기적으로 바꿀 수 있으므로 MAC 주소가 아닌
                    // 앱이 광고한 연결 토큰으로 중복을 제거한다.
                    if (token != null && candidates.none {
                            it.scanRecord
                                ?.getManufacturerSpecificData(BleSessionHost.MANUFACTURER_ID)
                                ?.let(::decodeAdvertisedToken) == token
                        }) {
                        candidates += result
                        receiverNames[token] = "이름 확인 중"
                        loadReceiverName(token) { name ->
                            mainHandler.post { receiverNames[token] = name }
                        }
                        message = "가까운 수신자를 찾았어요. 연결할 사용자를 선택해 주세요."
                    }
                }
            }
            override fun onScanFailed(code: Int) {
                val reason = when (code) {
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "블루투스 검색 등록에 실패했어요. 앱을 다시 열어 주세요."
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "이 기기는 BLE 검색을 지원하지 않아요."
                    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "블루투스 하드웨어 자원이 부족해 검색하지 못했어요."
                    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "검색을 너무 자주 실행했어요. 잠시 후 다시 시도해 주세요."
                    else -> "주변 기기를 찾지 못했어요. 블루투스를 확인해 주세요."
                }
                mainHandler.post { message = reason }
            }
        }
        if (!hasBle) {
            message = "이 기기는 BLE 근처 기기 검색을 지원하지 않아요. NFC 또는 QR로 연결해 주세요."
        } else if (granted && adapter?.isEnabled == true && scanner != null) {
            // 일부 제조사에서 128비트 서비스 UUID 필터 검색이 누락되는 문제를 피하기 위해
            // 전체 BLE 결과를 받은 뒤 앱에서 TipTransfer 서비스 UUID만 판별한다.
            try {
                // 제조사별 필터 처리 차이를 피하기 위해 전체 광고를 직접 수신한 뒤
                // 앱 전용 manufacturer marker만 코드에서 판별한다.
                scanner.startScan(
                    null,
                    ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build(),
                    callback,
                )
            } catch (_: SecurityException) {
                message = "근처 기기 검색 권한이 적용되지 않았어요. 앱 권한에서 '주변 기기'를 허용한 뒤 다시 시도해 주세요."
            } catch (_: IllegalStateException) {
                message = "BLE 검색기를 시작하지 못했어요. 블루투스를 껐다 켠 뒤 다시 시도해 주세요."
            }
        } else if (granted && adapter?.isEnabled == true) {
            message = "이 기기에서 BLE 검색기를 사용할 수 없어요. NFC 또는 QR로 연결해 주세요."
        } else if (granted) message = "블루투스를 켠 뒤 다시 시도해 주세요."
        else message = "주변 기기 권한을 허용해 주세요."
        onDispose { try { scanner?.stopScan(callback) } catch (_: Exception) {} }
    }
    LaunchedEffect(retryKey, granted) {
        if (!granted || adapter?.isEnabled != true) return@LaunchedEffect
        delay(5_000)
        if (candidates.isEmpty()) {
            message = "가까운 수신자를 찾는 중이에요. 잠시만 기다려 주세요."
        }
        // BLE는 기기 설정과 주변 환경 영향을 받을 수 있으므로, 기다림이 길어지면
        // 사용자가 막히지 않도록 QR 연결을 우선 제안한다.
        delay(7_000)
        if (candidates.isEmpty()) {
            showQrFallback = true
            message = "주변 연결이 지연되고 있어요. QR로 전환하면 바로 연결할 수 있어요."
        }
    }
    fun connect(result: ScanResult) {
        message = "연결하는 중이에요."
        result.device.connectGatt(context, false, object : BluetoothGattCallback() {
            private var readCompleted = false
            private fun finishRead(gatt: BluetoothGatt, value: ByteArray?, status: Int) {
                if (readCompleted) return
                readCompleted = true
                val id = value?.let(::decodeSessionId).orEmpty()
                gatt.close()
                mainHandler.post {
                    if (status == BluetoothGatt.GATT_SUCCESS && id.isNotBlank()) onSessionId(id) else message = "수신 세션을 읽지 못했어요. 다시 찾아 주세요."
                }
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices() else if (status != BluetoothGatt.GATT_SUCCESS) mainHandler.post { message = "연결하지 못했어요. 다시 찾아 주세요." }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val characteristic = gatt.getService(BleSessionHost.SERVICE_UUID)?.getCharacteristic(BleSessionHost.SESSION_CHARACTERISTIC_UUID)
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) gatt.readCharacteristic(characteristic) else { mainHandler.post { message = "상대방의 수신 세션을 찾지 못했어요." }; gatt.close() }
            }
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic, status: Int) {
                finishRead(gatt, characteristic.value, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                finishRead(gatt, value, status)
            }
        })
    }
    Column(Modifier.fillMaxSize().themeAtmosphere(pack).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("가까운 사람 찾기", color = pack.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = pack.fontFamily)
        Spacer(Modifier.height(12.dp)); Text(message, color = pack.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        if (candidates.isEmpty()) Text("수신자가 ‘주변에서 받기’를 열면 여기에 표시돼요.", color = pack.ink, textAlign = TextAlign.Center)
        candidates.forEachIndexed { index, candidate ->
            val candidateToken = candidate.scanRecord
                ?.getManufacturerSpecificData(BleSessionHost.MANUFACTURER_ID)
                ?.let(::decodeAdvertisedToken)
            val receiverName = candidateToken?.let { receiverNames[it] }
                ?: "가까운 사용자 ${index + 1}"
            ThemeButton(
                theme = theme,
                onClick = {
                    val payload = candidate.scanRecord
                        ?.getManufacturerSpecificData(BleSessionHost.MANUFACTURER_ID)
                    val advertisedToken = payload?.let(::decodeAdvertisedToken)
                    if (advertisedToken != null) {
                        message = "수신 세션을 확인하는 중이에요."
                        onSessionId(advertisedToken)
                    } else {
                        // 새 버전 이전의 광고를 만난 경우에만 GATT 읽기로 호환한다.
                        connect(candidate)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { Text("$receiverName · 연결") }
        }
        Spacer(Modifier.height(18.dp))
        ThemeButton(theme = theme, onClick = {
            candidates.clear()
            rawSignalCount = 0
            showQrFallback = false
            message = "가까운 사람을 다시 찾는 중이에요."
            retryKey += 1
        }, modifier = Modifier.fillMaxWidth()) { Text("주변 기기 다시 찾기") }
        Spacer(Modifier.height(10.dp))
        ThemeButton(theme = theme, onClick = onUseNfc, modifier = Modifier.fillMaxWidth()) { Text("NFC로 보내기") }
        Spacer(Modifier.height(10.dp))
        if (showQrFallback) {
            Button(
                onClick = onUseQr,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = pack.accent),
            ) { Text("QR로 바로 연결") }
        } else {
            ThemeButton(theme = theme, onClick = onUseQr, modifier = Modifier.fillMaxWidth()) { Text("QR로 보내기") }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) { Text("홈으로") }
    }
}

private fun loadReceiverName(token: String, onResult: (String) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    db.collection("transferSessions")
        .whereEqualTo("bleToken", token)
        .limit(1)
        .get()
        .addOnSuccessListener { sessions ->
            val session = sessions.documents.firstOrNull()
            if (session == null) {
                onResult("현재 사용할 수 없는 수신자")
                return@addOnSuccessListener
            }
            // 새 세션은 생성 시점의 수신자 이름을 함께 저장한다.
            // 이전 세션도 지원하도록 값이 없을 때만 users 문서를 보조 조회한다.
            val sessionName = session.getString("receiverNickname")?.takeIf { it.isNotBlank() }
            if (sessionName != null) {
                onResult(sessionName)
            } else {
                val receiverId = session.getString("receiverId")
                if (receiverId == null) {
                    onResult("가까운 사용자")
                } else {
                    db.collection("users").document(receiverId).get()
                        .addOnSuccessListener { user ->
                            onResult(user.getString("nickname")?.takeIf { it.isNotBlank() } ?: "가까운 사용자")
                        }
                        .addOnFailureListener { onResult("가까운 사용자") }
                }
            }
        }
        .addOnFailureListener { onResult("가까운 사용자") }
}

private fun decodeSessionId(value: ByteArray): String = String(value, Charsets.UTF_8).trim()

private fun decodeAdvertisedToken(payload: ByteArray): String? {
    val marker = BleSessionHost.ADVERTISEMENT_MARKER
    if (!payload.startsWith(marker)) return null
    val token = decodeSessionId(payload.copyOfRange(marker.size, payload.size))
    return token.takeIf { it.length == 16 && it.all(Char::isLetterOrDigit) }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
