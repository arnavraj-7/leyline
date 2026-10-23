package dev.leyline.ble

import android.os.ParcelUuid
import dev.leyline.mesh.NodeId
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The GATT layout every Leyline phone exposes, and what it puts in its advert.
 *
 * One connection carries both directions: the central writes frames into [RX],
 * the peripheral sends frames back as notifications on [TX]. So a link needs only
 * one connection, whichever phone opened it.
 */
object BleProfile {
    val SERVICE: UUID = UUID.fromString("6c65796c-696e-4510-9a3e-5d0c0000a001")
    val RX: UUID = UUID.fromString("6c65796c-696e-4510-9a3e-5d0c0000a002")
    val TX: UUID = UUID.fromString("6c65796c-696e-4510-9a3e-5d0c0000a003")
    val SERVICE_PARCEL = ParcelUuid(SERVICE)

    /** Android 14+ always negotiates 517; older phones accept what we ask for. */
    const val REQUESTED_MTU = 517

    /**
     * Frames stay at or under this even on a 517 MTU link. The PS asks for ~180 B
     * chunks, and smaller writes survive interference better on old phones.
     */
    const val MAX_FRAME = 180
}

/**
 * Service data in our scan response: 4-byte node id + 1 flag byte.
 *
 * The advert itself carries only the service UUID (3 + 18 = 21 of 31 bytes),
 * because filtering on a service UUID works on every phone's Bluetooth chip;
 * filtering on service data silently matched nothing on an Android 9 Vivo.
 * Active scanning (Android's default) fetches the scan response, where this
 * sits as 128-bit service data (2 + 16 + 5 = 23 of 31 bytes). Having the node id
 * here means a phone knows who it sees before connecting, despite address rotation.
 */
data class AdvertInfo(val node: NodeId, val carryingSos: Boolean, val relaying: Boolean) {
    fun encode(): ByteArray {
        var flags = 0
        if (carryingSos) flags = flags or FLAG_SOS
        if (relaying) flags = flags or FLAG_RELAY
        return ByteBuffer.allocate(SIZE).putInt(node.value).put(flags.toByte()).array()
    }

    companion object {
        const val SIZE = 5
        private const val FLAG_SOS = 0x01
        private const val FLAG_RELAY = 0x02

        fun decode(bytes: ByteArray?): AdvertInfo? {
            if (bytes == null || bytes.size < SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            val node = NodeId(buf.getInt())
            val flags = buf.get().toInt()
            return AdvertInfo(node, flags and FLAG_SOS != 0, flags and FLAG_RELAY != 0)
        }
    }
}
