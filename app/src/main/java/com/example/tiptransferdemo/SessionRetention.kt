package com.yunsi.tiptransferdemo

import com.google.firebase.Timestamp
import java.util.Date

/**
 * 종료된 QR 세션은 전송 완료 화면을 다시 열거나 콘솔에서 확인할 수 있도록 잠시 보관한다.
 * Firestore TTL 정책이 cleanupAt 필드를 기준으로 실제 문서를 서버에서 삭제한다.
 */
const val TERMINAL_SESSION_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L

fun terminalSessionCleanupAt(): Timestamp =
    Timestamp(Date(System.currentTimeMillis() + TERMINAL_SESSION_RETENTION_MILLIS))
