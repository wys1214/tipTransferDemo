package com.yunsi.tiptransferdemo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** NFC 설정 변경을 즉시 화면 상태에 반영한다. */
@Composable
fun rememberNfcEnabled(context: Context, adapter: NfcAdapter?): Boolean {
    var enabled by remember(adapter) { mutableStateOf(adapter?.isEnabled == true) }
    DisposableEffect(context, adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    enabled = adapter?.isEnabled == true
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }
    // 일부 제조사 기기에서는 설정 화면에서 NFC를 전환해도 상태 브로드캐스트가
    // 지연될 수 있다. 수신/송신 화면이 열린 동안 짧게 폴링해 UI를 확실히 동기화한다.
    androidx.compose.runtime.LaunchedEffect(adapter) {
        while (true) {
            enabled = adapter?.isEnabled == true
            delay(500)
        }
    }
    return enabled
}
