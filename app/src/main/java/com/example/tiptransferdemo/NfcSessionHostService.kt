Exit code: 0
Wall time: 1.9 seconds
Output:
package com.yunsi.tiptransferdemo

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/** 수신 기기가 NFC 카드처럼 임시 전송 세션 ID를 제공하는 데모용 HCE 서비스. */
class NfcSessionHostService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (!isSelectSessionAid(commandApdu)) return STATUS_NOT_FOUND
        val id = activeSessionId ?: return STATUS_NOT_FOUND
        // 송신 기기가 SELECT AID 명령을 보낸 뒤 세션 ID와 성공 상태(0x9000)를 받는다.
        return id.toByteArray(Charsets.UTF_8) + STATUS_OK
    }

    override fun onDeactivated(reason: Int) = Unit

    companion object {
        @Volatile private var activeSessionId: String? = null
        fun setSession(id: String) { activeSessionId = id }
        fun clearSession(id: String? = null) {
            if (id == null || activeSessionId == id) activeSessionId = null
        }

        private fun isSelectSessionAid(apdu: ByteArray?): Boolean =
            apdu?.contentEquals(SELECT_SESSION_AID) == true

        private val SELECT_SESSION_AID = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x06,
            0xF0.toByte(), 0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0x00,
        )
        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val STATUS_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    }
}

