package dev.leyline.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.util.Log

/**
 * Connectable advert carrying our [AdvertInfo]. Uses the AdvertisingSet API
 * (Android 8+) so the flags can change without stopping the advert, which would
 * briefly make us invisible.
 *
 * Callers must hold BLUETOOTH_ADVERTISE (Android 12+).
 */
@SuppressLint("MissingPermission")
internal class Advertiser(private val adapter: BluetoothAdapter) {
    private var set: AdvertisingSet? = null
    private var callback: AdvertisingSetCallback? = null
    private var pending: AdvertInfo? = null

    fun start(info: AdvertInfo, urgent: Boolean) {
        stop()
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "no advertiser: Bluetooth off or peripheral mode unsupported")
            return
        }
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true) // 31-byte advert every phone can scan
            .setConnectable(true)
            .setScannable(true)
            // SOS beacon mode: ~100 ms interval and high power so we're found fast.
            .setInterval(if (urgent) AdvertisingSetParameters.INTERVAL_LOW else AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(
                if (urgent) AdvertisingSetParameters.TX_POWER_HIGH else AdvertisingSetParameters.TX_POWER_MEDIUM,
            )
            .build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    set = advertisingSet
                    pending?.let { update(it) }
                    pending = null
                } else {
                    Log.w(TAG, "advertising failed: status $status")
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                if (set == advertisingSet) set = null
            }
        }
        callback = cb
        advertiser.startAdvertisingSet(parameters, ADVERT, scanResponse(info), null, null, cb)
    }

    /** Changes the advertised flags in place. */
    fun update(info: AdvertInfo) {
        val current = set
        if (current == null) pending = info else current.setScanResponseData(scanResponse(info))
    }

    fun stop() {
        callback?.let { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(it) }
        callback = null
        set = null
        pending = null
    }

    private fun scanResponse(info: AdvertInfo) = AdvertiseData.Builder()
        .setIncludeDeviceName(false) // would not fit, and names identify people
        .setIncludeTxPowerLevel(false)
        .addServiceData(BleProfile.SERVICE_PARCEL, info.encode())
        .build()

    private companion object {
        const val TAG = "LeylineAdvertiser"

        val ADVERT: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(BleProfile.SERVICE_PARCEL)
            .build()
    }
}
