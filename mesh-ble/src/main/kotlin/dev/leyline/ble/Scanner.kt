package dev.leyline.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.SystemClock
import android.util.Log

/** One advert from another Leyline phone. */
data class Sighting(
    val info: AdvertInfo,
    val device: BluetoothDevice,
    val rssi: Int,
    val seenAt: Long,
)

/**
 * Scans for our service. The filter matters twice: Android stops unfiltered
 * scans when the screen turns off, and it keeps the CPU from waking for every
 * TV and watch in range.
 *
 * Android also blocks an app that starts scanning more than 5 times in 30
 * seconds, so restarts are rate-limited here.
 *
 * Callers must hold BLUETOOTH_SCAN (Android 12+) or location (Android 11 and below).
 */
@SuppressLint("MissingPermission")
internal class Scanner(private val adapter: BluetoothAdapter, private val onSighting: (Sighting) -> Unit) {
    private var running = false
    private val starts = ArrayDeque<Long>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            running = false
        }
    }

    /** [aggressive]: low-latency scanning (foreground, or SOS active); otherwise balanced. */
    fun start(aggressive: Boolean): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        val now = SystemClock.elapsedRealtime()
        while (starts.isNotEmpty() && now - starts.first() > THROTTLE_WINDOW) starts.removeFirst()
        if (starts.size >= MAX_STARTS) {
            Log.w(TAG, "skipping scan restart to avoid Android's scan throttle")
            return false
        }
        if (running) scanner.stopScan(callback)
        val filter = ScanFilter.Builder().setServiceUuid(BleProfile.SERVICE_PARCEL).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (aggressive) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        starts.addLast(now)
        running = true
        return true
    }

    fun stop() {
        if (running) adapter.bluetoothLeScanner?.stopScan(callback)
        running = false
    }

    private fun handle(result: ScanResult) {
        // Null until the scan response arrives; Android merges it into the same record.
        val info = AdvertInfo.decode(result.scanRecord?.getServiceData(BleProfile.SERVICE_PARCEL)) ?: return
        if (logged.add(info.node)) Log.i(TAG, "first sighting of ${info.node} at ${result.rssi} dBm")
        onSighting(Sighting(info, result.device, result.rssi, SystemClock.elapsedRealtime()))
    }

    private val logged = HashSet<dev.leyline.mesh.NodeId>()

    private companion object {
        const val TAG = "LeylineScanner"
        const val MAX_STARTS = 4
        const val THROTTLE_WINDOW = 30_000L
    }
}
