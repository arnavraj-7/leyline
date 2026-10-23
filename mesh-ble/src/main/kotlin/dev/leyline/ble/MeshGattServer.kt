package dev.leyline.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleServerManager

/**
 * Our GATT server: one service with an RX characteristic peers write frames to
 * and a TX characteristic we notify frames on. Shared by every incoming link;
 * each connected client gets its own [PeerConnection] in server role.
 *
 * No characteristic needs encryption, so Android never shows a pairing dialog.
 */
internal class MeshGattServer(context: Context) : BleServerManager(context) {
    override fun initializeServer(): List<BluetoothGattService> = listOf(
        service(
            BleProfile.SERVICE,
            characteristic(
                BleProfile.RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
            characteristic(
                BleProfile.TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
                cccd(),
            ),
        ),
    )

    override fun getMinLogPriority(): Int = Log.INFO

    override fun log(priority: Int, message: String) {
        Log.println(priority, "LeylineGattServer", message)
    }
}
