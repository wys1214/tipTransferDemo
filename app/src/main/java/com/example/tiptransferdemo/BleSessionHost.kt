package com.yunsi.tiptransferdemo

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 페어링 없이 일회용 세션 ID만 제공하는 BLE GATT 주변기기. */
class BleSessionHost(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var sessionId: String? = null

    fun start(id: String, onReady: () -> Unit, onError: (String) -> Unit) {
        stop()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { mainHandler.post { onError("블루투스를 켠 뒤 다시 시도해 주세요.") }; return }
        if (!adapter.isMultipleAdvertisementSupported) { mainHandler.post { onError("이 기기는 주변 연결 광고를 지원하지 않아요. QR로 받아 주세요.") }; return }
        sessionId = id
        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid == SERVICE_UUID && status == BluetoothGatt.GATT_SUCCESS) startAdvertising(onReady, onError)
                else if (service.uuid == SERVICE_UUID) mainHandler.post { onError("주변 연결 준비에 실패했어요. 다시 시도해 주세요.") }
            }

            override fun onCharacteristicReadRequest(device: android.bluetooth.BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                val fullValue = if (characteristic.uuid == SESSION_CHARACTERISTIC_UUID) sessionPayload() else ByteArray(0)
                // BLE 연결 토큰은 기본 MTU보다 작아 한 번에 안전하게 전달된다.
                val value = if (offset in 0..fullValue.size) fullValue.copyOfRange(offset, fullValue.size) else ByteArray(0)
                val status = if (offset <= fullValue.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
                gattServer?.sendResponse(device, requestId, status, offset, value)
            }
        }
        gattServer = manager.openGattServer(context, callback)
        if (gattServer == null) { mainHandler.post { onError("주변 연결 서버를 열지 못했어요. 블루투스 권한을 확인해 주세요.") }; return }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(SESSION_CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        }
        if (gattServer?.addService(service) != true) { mainHandler.post { onError("주변 연결 준비에 실패했어요. 다시 시도해 주세요.") }; return }
    }

    private fun startAdvertising(onReady: () -> Unit, onError: (String) -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) { mainHandler.post { onError("이 기기는 BLE 광고를 지원하지 않아요. QR로 받아 주세요.") }; return }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build()
        val data = AdvertiseData.Builder()
            // 128비트 UUID를 광고에 넣으면 일부 기기에서 스캔 레코드에 누락되거나
            // 패킷 한도를 넘길 수 있어, 짧은 앱 전용 마커로 수신자를 식별한다.
            // 광고 패킷은 31바이트 제한 안에 marker(4) + UUID(16)를 담을 수 있다.
            // 세션 ID를 GATT 읽기 대신 광고에서 바로 전달해 기기별 MTU 차이를 피한다.
            .addManufacturerData(MANUFACTURER_ID, advertisementPayload())
            .setIncludeDeviceName(false)
            .build()
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { mainHandler.post(onReady) }
            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "광고 데이터가 너무 커서 시작하지 못했어요."
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "다른 앱의 주변 연결 사용량이 많아 시작하지 못했어요."
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "이 기기는 주변 연결 광고를 지원하지 않아요."
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "기기 블루투스 오류로 시작하지 못했어요."
                    else -> "주변 연결을 시작하지 못했어요. 블루투스 권한을 확인해 주세요."
                }
                mainHandler.post { onError(reason) }
            }
        }
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    fun stop() {
        try { advertiseCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) { }
        try { gattServer?.close() } catch (_: Exception) { }
        advertiser = null; advertiseCallback = null; gattServer = null; sessionId = null
    }

    private fun sessionPayload(): ByteArray =
        (sessionId ?: "").toByteArray(StandardCharsets.UTF_8)

    private fun advertisementPayload(): ByteArray =
        ADVERTISEMENT_MARKER + sessionPayload()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6c0c7e10-7fad-4d7c-9e76-4b9f8f7a5b01")
        val SESSION_CHARACTERISTIC_UUID: UUID = UUID.fromString("6c0c7e11-7fad-4d7c-9e76-4b9f8f7a5b01")
        const val MANUFACTURER_ID = 0x1234
        val ADVERTISEMENT_MARKER = byteArrayOf(0x54, 0x49, 0x50, 0x02) // TIP + protocol v2
    }
}
