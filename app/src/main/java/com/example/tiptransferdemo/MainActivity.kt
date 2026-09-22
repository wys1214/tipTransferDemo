package com.yunsi.tiptransferdemo

import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.yunsi.tiptransferdemo.ui.theme.TipTransferDemoTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val STARTING_BALANCE = 50_000L
private const val DEFAULT_DAILY_SEND_LIMIT = 300_000L
private const val DEFAULT_SINGLE_SEND_LIMIT = 10_000L
private const val PREFERENCES_NAME = "tip_transfer_demo_preferences"
private const val THEME_PREFERENCE_KEY = "visual_theme"
private const val UI_THEME_PREFERENCE_KEY = "ui_theme_pack"

data class WalletUiState(
    val isLoading: Boolean = true,
    val nickname: String = "",
    val balance: Long = 0,
    val error: String? = null,
    val isToppingUp: Boolean = false,
    val userId: String = "",
    val dailySentAmount: Long = 0L,
    val dailySendLimit: Long = DEFAULT_DAILY_SEND_LIMIT,
    val singleSendLimit: Long = DEFAULT_SINGLE_SEND_LIMIT,
    val history: List<TransferHistoryItem> = emptyList(),
)

data class TransferHistoryItem(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val amount: Long,
    val type: String,
    val createdAt: Timestamp?,
)

class MainActivity : ComponentActivity() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var uiState by mutableStateOf(WalletUiState())
    private val historyListeners = mutableListOf<ListenerRegistration>()
    // 송신/수신 쿼리의 현재 스냅샷을 분리 보관한다. 삭제된 문서가 로컬 목록에
    // 영구적으로 남지 않도록 매 스냅샷마다 해당 쿼리 결과 전체를 교체한다.
    private val historyByParticipant = mutableMapOf<String, Map<String, TransferHistoryItem>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initialiseWallet()
        setContent {
            TipTransferDemoTheme {
                App(
                    uiState = uiState,
                    onTopUp = ::topUp,
                    onUpdateNickname = ::updateNickname,
                    onUpdateTransferLimits = ::updateTransferLimits,
                    onClearError = { uiState = uiState.copy(error = null) },
                )
            }
        }
    }

    private fun initialiseWallet() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            auth.signInAnonymously().addOnSuccessListener { initialiseWallet() }
                .addOnFailureListener { showError("익명 로그인을 시작하지 못했어요.") }
            return
        }
        val userRef = db.collection("users").document(currentUser.uid)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(userRef)
            if (!snapshot.exists()) {
                val suffix = currentUser.uid.takeLast(4).uppercase()
                transaction.set(userRef, mapOf(
                    "nickname" to "사용자 $suffix",
                    "balance" to STARTING_BALANCE,
                    "dailySentAmount" to 0L,
                    "dailyLimitDate" to "",
                    "dailySendLimit" to DEFAULT_DAILY_SEND_LIMIT,
                    "singleSendLimit" to DEFAULT_SINGLE_SEND_LIMIT,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                ))
            }
            null
        }.addOnSuccessListener {
            startHistoryListeners(currentUser.uid)
            userRef.addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    showError("재화 정보를 불러오지 못했어요.")
                    return@addSnapshotListener
                }
                uiState = uiState.copy(
                    isLoading = false,
                    userId = currentUser.uid,
                    nickname = snapshot.getString("nickname") ?: "사용자",
                    balance = snapshot.getLong("balance") ?: STARTING_BALANCE,
                    dailySentAmount = if (snapshot.getString("dailyLimitDate") == koreaToday()) {
                        snapshot.getLong("dailySentAmount") ?: 0L
                    } else {
                        0L
                    },
                    dailySendLimit = snapshot.getLong("dailySendLimit") ?: DEFAULT_DAILY_SEND_LIMIT,
                    singleSendLimit = snapshot.getLong("singleSendLimit") ?: DEFAULT_SINGLE_SEND_LIMIT,
                    error = null,
                )
            }
        }.addOnFailureListener { showError("테스트 재화를 준비하지 못했어요.") }
    }

    private fun startHistoryListeners(uid: String) {
        if (historyListeners.isNotEmpty()) return
        historyByParticipant.clear()
        uiState = uiState.copy(history = emptyList())
        listOf("senderId", "receiverId").forEach { participantField ->
            historyListeners += db.collection("transactions")
                .whereEqualTo(participantField, uid)
                .addSnapshotListener { snapshot, _ ->
                    val currentQueryItems = snapshot?.documents.orEmpty().associate { document ->
                        document.id to TransferHistoryItem(
                            id = document.id,
                            senderId = document.getString("senderId") ?: "",
                            receiverId = document.getString("receiverId") ?: "",
                            amount = document.getLong("amount") ?: 0L,
                            type = document.getString("type") ?: "transfer",
                            createdAt = document.getTimestamp("createdAt"),
                        )
                    }
                    historyByParticipant[participantField] = currentQueryItems
                    val mergedById = historyByParticipant.values
                        .flatMap { it.values }
                        .associateBy { it.id }
                    uiState = uiState.copy(
                        history = mergedById.values
                            .filter { it.amount > 0L }
                            .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                            .toList(),
                    )
                }
        }
    }

    override fun onDestroy() {
        historyListeners.forEach { it.remove() }
        historyListeners.clear()
        historyByParticipant.clear()
        super.onDestroy()
    }

    private fun topUp(amount: Long) {
        val user = auth.currentUser ?: return
        uiState = uiState.copy(isToppingUp = true)
        val userRef = db.collection("users").document(user.uid)
        val historyRef = db.collection("transactions").document()
        db.runTransaction { firestoreTransaction ->
            val currentBalance = firestoreTransaction.get(userRef).getLong("balance") ?: STARTING_BALANCE
            firestoreTransaction.set(userRef, mapOf(
                "balance" to currentBalance + amount,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            ), SetOptions.merge())
            firestoreTransaction.set(historyRef, mapOf(
                "senderId" to user.uid,
                "receiverId" to user.uid,
                "amount" to amount,
                "type" to "test_top_up",
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            ))
            null
        }.addOnFailureListener { showError("테스트 재화를 충전하지 못했어요.") }
            .addOnCompleteListener { uiState = uiState.copy(isToppingUp = false) }
    }

    private fun updateNickname(nickname: String) {
        val user = auth.currentUser ?: return
        val value = nickname.trim()
        if (value.length !in 2..12) {
            showError("이름은 2~12자로 입력해 주세요.")
            return
        }
        db.collection("users").document(user.uid).update(
            "nickname", value,
            "updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp(),
        ).addOnFailureListener { showError("이름을 변경하지 못했어요.") }
    }

    /** 데모 환경의 개인 한도값. 실제 서비스에서는 서버에서 검증·관리한다. */
    private fun updateTransferLimits(singleLimit: Long, dailyLimit: Long) {
        val user = auth.currentUser ?: return
        if (singleLimit < 100L || dailyLimit < singleLimit) {
            showError("재화 1개당 한도는 100원 이상이고, 하루 한도보다 작아야 해요.")
            return
        }
        db.collection("users").document(user.uid).set(
            mapOf(
                "singleSendLimit" to singleLimit,
                "dailySendLimit" to dailyLimit,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).addOnFailureListener { showError("개인 전송 한도를 저장하지 못했어요.") }
    }

    private fun showError(message: String) {
        uiState = uiState.copy(isLoading = false, isToppingUp = false, error = message)
    }
}

@Composable
private fun App(
    uiState: WalletUiState,
    onTopUp: (Long) -> Unit,
    onUpdateNickname: (String) -> Unit,
    onUpdateTransferLimits: (Long, Long) -> Unit,
    onClearError: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val networkAvailable by rememberNetworkAvailable()
    var showTopUpDialog by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var receiveEndedNotice by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showThemeGallery by remember { mutableStateOf(false) }
    var visualTheme by remember { mutableStateOf(loadSavedTheme(context)) }
    var uiThemePack by remember { mutableStateOf(loadSavedUiTheme(context)) }
    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (showSend) {
        CompositionLocalProvider(LocalUiThemePack provides uiThemePack) {
            SendScannerScreen(theme = uiThemePack.fixedVisualTheme, onBack = { showSend = false })
        }
    } else if (showReceive) {
        CompositionLocalProvider(LocalUiThemePack provides uiThemePack) {
            ReceiveScreen(theme = uiThemePack.fixedVisualTheme, onBack = { showReceive = false }, onBackgroundExit = { showReceive = false; receiveEndedNotice = true })
        }
    } else if (showHistory) {
        CompositionLocalProvider(LocalUiThemePack provides uiThemePack) {
            HistoryScreen(state = uiState, theme = visualTheme, onBack = { showHistory = false })
        }
    } else {
        CompositionLocalProvider(LocalUiThemePack provides uiThemePack) {
        Scaffold(containerColor = uiThemePack.backgroundBottom) { padding ->
            HomeScreen(
                state = uiState,
                modifier = Modifier.padding(padding),
                onTopUpClick = { showTopUpDialog = true },
                onSendClick = { showSend = true },
                onReceiveClick = { showReceive = true },
                theme = visualTheme,
                onHistoryClick = { showHistory = true },
                onNicknameClick = { showNicknameDialog = true },
                onSettingsClick = { showSettingsDialog = true },
                onThemeGalleryClick = { showThemeGallery = true },
            )
        }
        }
    }
    if (showTopUpDialog) {
        TopUpDialog(
            theme = visualTheme,
            isLoading = uiState.isToppingUp,
            onDismiss = { if (!uiState.isToppingUp) showTopUpDialog = false },
            onTopUp = { amount ->
                onTopUp(amount)
                showTopUpDialog = false
            },
        )
    }
    if (showNicknameDialog) {
        NicknameDialog(
            initialValue = uiState.nickname,
            onDismiss = { showNicknameDialog = false },
            onConfirm = { nickname ->
                onUpdateNickname(nickname)
                showNicknameDialog = false
            },
        )
    }
    if (showSettingsDialog) {
        TransferLimitSettingsDialog(
            singleLimit = uiState.singleSendLimit,
            dailyLimit = uiState.dailySendLimit,
            onDismiss = { showSettingsDialog = false },
            onSave = { single, daily ->
                onUpdateTransferLimits(single, daily)
                showSettingsDialog = false
            },
        )
    }
    if (showThemeGallery) {
        UiThemeGalleryDialog(
            selected = uiThemePack,
            onDismiss = { showThemeGallery = false },
            onSelect = { selected ->
                uiThemePack = selected
                saveUiTheme(context, selected)
                showThemeGallery = false
            },
        )
    }
    uiState.error?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            confirmButton = { TextButton(onClick = onClearError) { Text("확인") } },
            title = { Text("잠시 문제가 생겼어요") },
            text = { Text(message) },
        )
    }
    if (receiveEndedNotice) AlertDialog(onDismissRequest = { receiveEndedNotice = false }, title = { Text("수신 대기가 종료되었어요") }, text = { Text("QR을 다시 열면 재화를 받을 수 있어요.") }, confirmButton = { TextButton(onClick = { receiveEndedNotice = false }) { Text("확인") } })
    if (!networkAvailable) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("인터넷 연결이 필요해요") },
            text = { Text("재화 전송과 수신은 인터넷에 연결된 상태에서만 이용할 수 있어요. 연결되면 자동으로 계속할 수 있어요.") },
            confirmButton = {},
        )
    }
}

@Composable
private fun rememberNetworkAvailable(): androidx.compose.runtime.State<Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val available = remember { mutableStateOf(connectivityManager.hasValidatedInternet()) }
    DisposableEffect(connectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available.value = connectivityManager.hasValidatedInternet() }
            override fun onLost(network: Network) { available.value = connectivityManager.hasValidatedInternet() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                available.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }
    return available
}

private fun ConnectivityManager.hasValidatedInternet(): Boolean =
    activeNetwork?.let { network ->
        getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } ?: false

private fun loadSavedTheme(context: Context): VisualTheme {
    val name = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(THEME_PREFERENCE_KEY, VisualTheme.PURPLE.name)
    return VisualTheme.entries.firstOrNull { it.name == name && it in selectableVisualThemes }
        ?: VisualTheme.PURPLE
}

private fun saveTheme(context: Context, theme: VisualTheme) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(THEME_PREFERENCE_KEY, theme.name)
        .apply()
}

private fun loadSavedUiTheme(context: Context): UiThemePack {
    val name = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(UI_THEME_PREFERENCE_KEY, UiThemePack.STAR_LIVE.name)
    return UiThemePack.entries.firstOrNull { it.name == name } ?: UiThemePack.STAR_LIVE
}

private fun saveUiTheme(context: Context, theme: UiThemePack) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .edit().putString(UI_THEME_PREFERENCE_KEY, theme.name).apply()
}

@Composable
private fun HomeScreen(
    state: WalletUiState,
    modifier: Modifier = Modifier,
    onTopUpClick: () -> Unit,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    theme: VisualTheme,
    onHistoryClick: () -> Unit,
    onNicknameClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onThemeGalleryClick: () -> Unit,
) {
    val pack = LocalUiThemePack.current
    Column(
        modifier = modifier.fillMaxSize()
            .themeAtmosphere(pack)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(pack.accent, pack.secondary))),
                contentAlignment = Alignment.Center,
            ) {
                Text("P!", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = (-1).sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.clickable(onClick = onNicknameClick)) {
                Text("TIPPOP", color = pack.ink, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.sp, fontFamily = pack.fontFamily)
                Text("${state.nickname}님, 반가워요", color = pack.muted, fontSize = 12.sp, fontFamily = pack.fontFamily)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(pack.surface)
                        .border(1.dp, pack.accent.copy(alpha = .35f), CircleShape).clickable(onClick = onThemeGalleryClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(pack.iconRes),
                        contentDescription = "${pack.displayName} 테마",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(pack.surface)
                        .border(1.dp, pack.accent.copy(alpha = .22f), CircleShape).clickable(onClick = onSettingsClick),
                    contentAlignment = Alignment.Center,
                ) { Text("⚙", color = pack.muted, fontSize = 19.sp) }
            }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(pack.corner.dp))
                .background(Brush.linearGradient(listOf(pack.surface, pack.surface.copy(alpha = .82f))))
                .border(if (pack == UiThemePack.ARCADE) 2.dp else 1.dp, pack.accent.copy(alpha = if (pack == UiThemePack.ARCADE) .78f else .3f), RoundedCornerShape(pack.corner.dp)),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("팁팝 잔액", color = pack.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = pack.fontFamily)
                    Text("TIPPOP WALLET", color = pack.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(pack.accent.copy(alpha = .12f), RoundedCornerShape(8.dp)).padding(8.dp, 4.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(won(state.balance), color = pack.ink, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-1).sp, fontFamily = pack.fontFamily)
                Spacer(Modifier.height(8.dp))
                Text("오늘 ${won((state.dailySendLimit - state.dailySentAmount).coerceAtLeast(0))} 더 보낼 수 있어요", color = pack.muted, fontSize = 12.sp)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onSendClick, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape((pack.corner - 8).coerceAtLeast(8).dp), colors = ButtonDefaults.buttonColors(containerColor = pack.accent)) {
                        Text("보내기", fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = onReceiveClick, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = pack.secondary.copy(alpha = .24f), contentColor = pack.ink)) {
                        Text("받기", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Card(onClick = onTopUpClick, colors = CardDefaults.cardColors(containerColor = pack.surface), shape = RoundedCornerShape(pack.corner.dp), border = androidx.compose.foundation.BorderStroke(if (pack == UiThemePack.ARCADE) 2.dp else 1.dp, pack.accent.copy(alpha = if (pack == UiThemePack.ARCADE) .72f else .28f))) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+", color = pack.accent, fontSize = 26.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("팁팝 충전", color = pack.ink, fontWeight = FontWeight.SemiBold, fontFamily = pack.fontFamily)
                    Text("데모 잔액을 채우고 팁을 보내 보세요", color = pack.muted, fontSize = 12.sp)
                }
                Text("›", color = pack.accent, fontSize = 24.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("최근 내역  ›", modifier = Modifier.clickable(onClick = onHistoryClick), color = pack.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = pack.fontFamily)
        }
        if (state.history.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = pack.surface), shape = RoundedCornerShape(pack.corner.dp), border = androidx.compose.foundation.BorderStroke(1.dp, pack.accent.copy(alpha = .22f))) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(theme.primary.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) { Text("✦", color = theme.primary, fontSize = 20.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column { Text("아직 거래 내역이 없어요", color = pack.ink, fontWeight = FontWeight.SemiBold); Text("가까운 사람에게 첫 전송을 시작해 보세요.", color = pack.muted, fontSize = 12.sp) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.history.take(3).forEach { item ->
                    val isTopUp = item.type == "test_top_up"
                    val isSent = !isTopUp && item.senderId == state.userId
                    val title = when {
                        isTopUp -> "팁팝 충전"
                        isSent -> "팁 보냄"
                        else -> "팁 받음"
                    }
                    val amountLabel = if (isSent) "-${String.format(Locale.KOREA, "%,d", item.amount)}원" else "+${String.format(Locale.KOREA, "%,d", item.amount)}원"
                    Card(
                        colors = CardDefaults.cardColors(containerColor = pack.surface),
                        shape = RoundedCornerShape(pack.corner.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, pack.accent.copy(alpha = .22f)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(title, fontWeight = FontWeight.SemiBold, color = pack.ink, fontFamily = pack.fontFamily)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(amountLabel, color = if (isSent) Color(0xFF64748B) else theme.primary, fontWeight = FontWeight.Bold)
                                Text(formatHistoryTime(item.createdAt), color = pack.muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 홈에서는 현재 적용된 효과를 작은 상징 아이콘으로 보여 준다. */
@Composable
private fun ThemeQuickButton(theme: VisualTheme, onClick: () -> Unit) {
    Card(
        modifier = Modifier.size(46.dp).clickable(onClick = onClick),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = theme.primary.copy(alpha = .10f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.primary.copy(alpha = .38f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ThemeSymbol(theme = theme, size = 28.dp)
        }
    }
}

/** 텍스트 설명 대신 실제 전송 연출과 같은 상징물을 테마 선택기에 표시한다. */
@Composable
private fun ThemeChoiceCard(
    theme: VisualTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(108.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) theme.primary.copy(alpha = .13f) else Color.White,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = theme.primary.copy(alpha = if (selected) .95f else .28f),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ThemeSymbol(theme = theme, size = 58.dp)
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(21.dp)
                        .background(theme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ThemeSymbol(theme: VisualTheme, size: androidx.compose.ui.unit.Dp) {
    when (theme) {
        VisualTheme.PURPLE -> PurpleToken(size = size)
        VisualTheme.GOLD -> GoldCoin(size = size * .92f)
        VisualTheme.ROCKET -> RocketShip(size = size)
        VisualTheme.FLOWER -> FlowerToken(size = size)
        VisualTheme.HEART_BALLOON -> HeartBalloon(size = size * .88f)
        VisualTheme.PAPER_PLANE -> PaperPlane(size = size)
    }
}

@Composable
private fun HistoryScreen(state: WalletUiState, theme: VisualTheme, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 24.dp),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("전체 거래 내역", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("최근 거래부터 표시돼요.", color = Color(0xFF64748B), fontSize = 14.sp)
            if (state.history.isEmpty()) {
                Text("아직 거래 내역이 없어요.", color = Color(0xFF64748B))
            } else {
                state.history.forEach { item ->
                    val isTopUp = item.type == "test_top_up"
                    val isSent = !isTopUp && item.senderId == state.userId
                    val title = when {
                        isTopUp -> "팁팝 충전"
                        isSent -> "팁 보냄"
                        else -> "팁 받음"
                    }
                    val amountLabel = if (isSent) "-${String.format(Locale.KOREA, "%,d", item.amount)}원" else "+${String.format(Locale.KOREA, "%,d", item.amount)}원"
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(title, fontWeight = FontWeight.Medium)
                                Text(formatHistoryTime(item.createdAt), color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            Text(amountLabel, color = if (isSent) Color(0xFF64748B) else theme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        ThemeButton(
            theme = theme,
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        ) { Text("홈으로") }
    }
}

private fun formatHistoryTime(timestamp: Timestamp?): String = timestamp?.toDate()?.let {
    SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(it)
} ?: "방금 전"

private fun koreaToday(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("Asia/Seoul")
}.format(Date())

@Composable
private fun ActionButton(label: String, modifier: Modifier, onClick: () -> Unit, color: Color) {
    Button(
        onClick = onClick, modifier = modifier.height(64.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Text(if (label == "보내기") "↑  보내기" else "↓  받기", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun HomeActionTile(symbol: String, title: String, subtitle: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(116.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F1F5)),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(32.dp).background(accent.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Text(symbol, color = accent, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            Column {
                Text(title, color = Color(0xFF282D38), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(subtitle, color = Color(0xFF9299A8), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TopUpDialog(theme: VisualTheme, isLoading: Boolean, onDismiss: () -> Unit, onTopUp: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("팁팝 충전") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("데모에서 사용할 팁팝 잔액을 충전해 보세요.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10_000L, 30_000L, 50_000L).forEach { amount ->
                        ThemeButton(theme = theme, onClick = { onTopUp(amount) }, enabled = !isLoading) { Text("+${String.format(Locale.KOREA, "%,d", amount)}원") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !isLoading) { Text("닫기") } },
    )
}

@Composable
private fun NicknameDialog(initialValue: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var nickname by remember(initialValue) { mutableStateOf(initialValue) }
    val valid = nickname.trim().length in 2..12
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2~12자로 입력해 주세요.", color = Color(0xFF64748B), fontSize = 14.sp)
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(12) },
                    label = { Text("표시 이름") },
                    singleLine = true,
                    isError = nickname.isNotBlank() && !valid,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(nickname) }, enabled = valid) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun UiThemeGalleryDialog(
    selected: UiThemePack,
    onDismiss: () -> Unit,
    onSelect: (UiThemePack) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("팁팝 스타일") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("배경, 카드 형태, 버튼과 송수신 무대가 함께 바뀌어요.", color = Color(0xFF6B7684), fontSize = 12.sp)
                UiThemePack.entries.toList().chunked(2).forEach { rowThemes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowThemes.forEach { candidate ->
                            val active = candidate == selected
                            Box(
                                Modifier.weight(1f).height(112.dp)
                                    .clip(RoundedCornerShape(candidate.corner.coerceAtMost(24).dp))
                                    .background(Brush.verticalGradient(listOf(candidate.backgroundTop, candidate.backgroundBottom)))
                                    .border(if (active) 3.dp else 1.dp, candidate.accent.copy(alpha = if (active) 1f else .4f), RoundedCornerShape(candidate.corner.coerceAtMost(24).dp))
                                    .clickable { onSelect(candidate) }
                                    .padding(12.dp),
                            ) {
                                Image(
                                    painter = painterResource(candidate.iconRes),
                                    contentDescription = candidate.displayName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.align(Alignment.TopEnd).size(42.dp),
                                )
                                Column(Modifier.align(Alignment.BottomStart)) {
                                    Text(candidate.displayName, color = candidate.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(candidate.subtitle, color = candidate.muted, fontSize = 9.sp, maxLines = 2)
                                }
                            }
                        }
                        if (rowThemes.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun TransferLimitSettingsDialog(
    singleLimit: Long,
    dailyLimit: Long,
    onDismiss: () -> Unit,
    onSave: (Long, Long) -> Unit,
) {
    var singleInput by remember(singleLimit) { mutableStateOf(singleLimit.toString()) }
    var dailyInput by remember(dailyLimit) { mutableStateOf(dailyLimit.toString()) }
    val single = singleInput.toLongOrNull() ?: 0L
    val daily = dailyInput.toLongOrNull() ?: 0L
    val valid = single >= 100L && daily >= single
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("개인 전송 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("내 기기에서 적용되는 테스트용 전송 한도예요.", color = Color(0xFF64748B), fontSize = 13.sp)
                OutlinedTextField(
                    value = singleInput,
                    onValueChange = { singleInput = it.filter(Char::isDigit).take(9) },
                    label = { Text("재화 1개당 보낼 수 있는 금액 (원)") },
                    singleLine = true,
                    isError = singleInput.isNotBlank() && single < 100L,
                )
                OutlinedTextField(
                    value = dailyInput,
                    onValueChange = { dailyInput = it.filter(Char::isDigit).take(9) },
                    label = { Text("하루에 보낼 수 있는 금액 (원)") },
                    singleLine = true,
                    isError = dailyInput.isNotBlank() && daily < single,
                )
                Text("스와이프 1회 = 재화 1개 전송으로 적용돼요.", color = Color(0xFF64748B), fontSize = 12.sp)
                if (!valid) Text("재화 1개당 한도는 100원 이상, 하루 한도 이하로 설정해 주세요.", color = Color(0xFFDC2626), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(single, daily) }, enabled = valid) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
