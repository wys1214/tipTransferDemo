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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private const val STARTING_BALANCE = 100L
private const val DAILY_SEND_LIMIT = 100L
private const val PREFERENCES_NAME = "tip_transfer_demo_preferences"
private const val THEME_PREFERENCE_KEY = "visual_theme"

data class WalletUiState(
    val isLoading: Boolean = true,
    val nickname: String = "",
    val balance: Long = 0,
    val error: String? = null,
    val isToppingUp: Boolean = false,
    val userId: String = "",
    val dailySentAmount: Long = 0L,
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
    private val historyById = mutableMapOf<String, TransferHistoryItem>()

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
                    error = null,
                )
            }
        }.addOnFailureListener { showError("테스트 재화를 준비하지 못했어요.") }
    }

    private fun startHistoryListeners(uid: String) {
        if (historyListeners.isNotEmpty()) return
        listOf("senderId", "receiverId").forEach { participantField ->
            historyListeners += db.collection("transactions")
                .whereEqualTo(participantField, uid)
                .addSnapshotListener { snapshot, _ ->
                    snapshot?.documents?.forEach { document ->
                        historyById[document.id] = TransferHistoryItem(
                            id = document.id,
                            senderId = document.getString("senderId") ?: "",
                            receiverId = document.getString("receiverId") ?: "",
                            amount = document.getLong("amount") ?: 0L,
                            type = document.getString("type") ?: "transfer",
                            createdAt = document.getTimestamp("createdAt"),
                        )
                    }
                    uiState = uiState.copy(
                        history = historyById.values
                            .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                            .toList(),
                    )
                }
        }
    }

    override fun onDestroy() {
        historyListeners.forEach { it.remove() }
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

    private fun showError(message: String) {
        uiState = uiState.copy(isLoading = false, isToppingUp = false, error = message)
    }
}

@Composable
private fun App(
    uiState: WalletUiState,
    onTopUp: (Long) -> Unit,
    onUpdateNickname: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val networkAvailable by rememberNetworkAvailable()
    var showTopUpDialog by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var receiveEndedNotice by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var visualTheme by remember { mutableStateOf(loadSavedTheme(context)) }
    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (showSend) {
        SendScannerScreen(theme = visualTheme, onBack = { showSend = false })
    } else if (showReceive) {
        ReceiveScreen(theme = visualTheme, onBack = { showReceive = false }, onBackgroundExit = { showReceive = false; receiveEndedNotice = true })
    } else if (showHistory) {
        HistoryScreen(state = uiState, theme = visualTheme, onBack = { showHistory = false })
    } else {
        Scaffold { padding ->
            HomeScreen(
                state = uiState,
                modifier = Modifier.padding(padding),
                onTopUpClick = { showTopUpDialog = true },
                onSendClick = { showSend = true },
                onReceiveClick = { showReceive = true },
                theme = visualTheme,
                onThemeClick = { showThemeDialog = true },
                onHistoryClick = { showHistory = true },
                onNicknameClick = { showNicknameDialog = true },
            )
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
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("전송 효과 테마") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    VisualTheme.entries.toList().chunked(3).forEach { rowThemes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowThemes.forEach { candidate ->
                                ThemeChoiceCard(
                                    theme = candidate,
                                    selected = candidate == visualTheme,
                                    onClick = {
                                        visualTheme = candidate
                                        saveTheme(context, candidate)
                                        showThemeDialog = false
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - rowThemes.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("닫기") } },
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
    return VisualTheme.entries.firstOrNull { it.name == name } ?: VisualTheme.PURPLE
}

private fun saveTheme(context: Context, theme: VisualTheme) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(THEME_PREFERENCE_KEY, theme.name)
        .apply()
}

@Composable
private fun HomeScreen(
    state: WalletUiState,
    modifier: Modifier = Modifier,
    onTopUpClick: () -> Unit,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    theme: VisualTheme,
    onThemeClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onNicknameClick: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(theme.primary), contentAlignment = Alignment.Center) {
                Text(state.nickname.take(1), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.clickable(onClick = onNicknameClick)) {
                Text("안녕하세요", color = Color(0xFF64748B), fontSize = 14.sp)
                Text(state.nickname, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            Spacer(Modifier.weight(1f))
            ThemeQuickButton(theme = theme, onClick = onThemeClick)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = theme.dark),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(26.dp)) {
                Text("내 테스트 재화", color = Color(0xFFC7D2FE), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("${state.balance}개", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 42.sp)
                Spacer(Modifier.height(8.dp))
                Text("가까운 사람에게 재화를 보내 보세요.", color = Color(0xFFE0E7FF), fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "오늘 전송 가능 ${DAILY_SEND_LIMIT - state.dailySentAmount}개 / ${DAILY_SEND_LIMIT}개",
                    color = Color(0xFFC7D2FE),
                    fontSize = 13.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("보내기", Modifier.weight(1f), onSendClick, theme.primary)
            ActionButton("받기", Modifier.weight(1f), onReceiveClick, theme.primary)
        }
        ThemeButton(theme = theme, onClick = onTopUpClick, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("테스트 재화 충전")
        }
        Text(
            "최근 내역",
            modifier = Modifier.clickable(onClick = onHistoryClick),
            color = theme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        if (state.history.isEmpty()) {
            Text("아직 주고받은 재화가 없어요.", color = Color(0xFF64748B), fontSize = 14.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.history.take(3).forEach { item ->
                    val isTopUp = item.type == "test_top_up"
                    val isSent = !isTopUp && item.senderId == state.userId
                    val title = when {
                        isTopUp -> "테스트 재화 충전"
                        isSent -> "재화 보냄"
                        else -> "재화 받음"
                    }
                    val amountLabel = if (isSent) "-${item.amount}개" else "+${item.amount}개"
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(title, fontWeight = FontWeight.Medium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(amountLabel, color = if (isSent) Color(0xFF64748B) else theme.primary, fontWeight = FontWeight.Bold)
                                Text(formatHistoryTime(item.createdAt), color = Color(0xFF94A3B8), fontSize = 11.sp)
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
                        isTopUp -> "테스트 재화 충전"
                        isSent -> "재화 보냄"
                        else -> "재화 받음"
                    }
                    val amountLabel = if (isSent) "-${item.amount}개" else "+${item.amount}개"
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
        onClick = onClick, modifier = modifier.height(58.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
private fun TopUpDialog(theme: VisualTheme, isLoading: Boolean, onDismiss: () -> Unit, onTopUp: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("테스트 재화 충전") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("테스트 환경에서만 재화를 충전할 수 있어요.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10L, 50L, 100L).forEach { amount ->
                        ThemeButton(theme = theme, onClick = { onTopUp(amount) }, enabled = !isLoading) { Text("+$amount") }
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
