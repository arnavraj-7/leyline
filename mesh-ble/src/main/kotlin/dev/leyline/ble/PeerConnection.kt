package dev.leyline.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.content.Context
import android.util.Log
import dev.leyline.mesh.LinkFrameCodec
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import no.nordicsemi.android.ble.ktx.suspend

/**
 * One GATT connection to one neighbour, in either role.
 *
 * - [Role.CLIENT]: we connected. We write frames to their RX and receive theirs
 *   as notifications on their TX.
 * - [Role.SERVER]: they connected to our [MeshGattServer]. Their frames arrive as
 *   writes on our RX; we send ours as notifications on our TX.
 *
 * Nordic's BleManager queues every GATT operation, which gives us the rule
 * Android needs but doesn't enforce: one operation in flight per connection.
 */
internal class PeerConnection(
    context: Context,
    val role: Role,
    private val onFrame: (PeerConnection, ByteArray) -> Unit,
) : BleManager(context) {
    enum class Role { CLIENT, SERVER }

    private var remoteRx: BluetoothGattCharacteristic? = null
    private var remoteTx: BluetoothGattCharacteristic? = null
    private var localRx: BluetoothGattCharacteristic? = null
    private var localTx: BluetoothGattCharacteristic? = null

    /** Largest frame for this link: negotiated MTU minus the 3-byte ATT header, capped at 180. */
    val frameSize: Int
        get() = (mtu - 3).coerceIn(LinkFrameCodec.MIN_FRAME_SIZE, BleProfile.MAX_FRAME)

    val address: String? get() = bluetoothDevice?.address

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(BleProfile.SERVICE) ?: return false
        remoteRx = service.getCharacteristic(BleProfile.RX)
        remoteTx = service.getCharacteristic(BleProfile.TX)
        return remoteRx != null && remoteTx != null
    }

    override fun onServerReady(server: BluetoothGattServer) {
        val service = server.getService(BleProfile.SERVICE) ?: return
        localRx = service.getCharacteristic(BleProfile.RX)
        localTx = service.getCharacteristic(BleProfile.TX)
    }

    override fun initialize() {
        when (role) {
            Role.CLIENT -> {
                // Android 14+ answers 517 whatever we ask; older phones settle on what both support.
                requestMtu(BleProfile.REQUESTED_MTU).enqueue()
                // Shorter connection interval while linked: frames go out every ~7.5-15 ms.
                requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH).enqueue()
                setNotificationCallback(remoteTx).with { _, data -> data.value?.let { onFrame(this, it) } }
                enableNotifications(remoteTx).enqueue()
            }
            Role.SERVER -> {
                setWriteCallback(localRx).with { _, data -> data.value?.let { onFrame(this, it) } }
            }
        }
    }

    override fun onServicesInvalidated() {
        remoteRx = null
        remoteTx = null
    }

    /** Sends one frame and suspends until the stack confirms it. Throws if the link is gone. */
    suspend fun writeFrame(frame: ByteArray) {
        when (role) {
            // With-response write: the peer's stack acknowledges every frame, which is our flow control.
            Role.CLIENT -> writeCharacteristic(remoteRx, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
            Role.SERVER -> sendNotification(localTx, frame).suspend()
        }
    }

    override fun getMinLogPriority(): Int = Log.INFO

    override fun log(priority: Int, message: String) {
        Log.println(priority, "LeylinePeer", "[$role ${address ?: "?"}] $message")
    }
}
