package dev.leyline.app.data

import dev.leyline.mesh.KeyPins
import dev.leyline.mesh.NodeId

/** [KeyPins] in SQLite, so an impostor can't wait for a restart to be trusted. */
class RoomKeyPins(private val dao: KeyPinDao, private val clock: () -> Long = System::currentTimeMillis) : KeyPins {
    override suspend fun check(node: NodeId, publicKey: ByteArray): Boolean {
        // Insert-or-ignore then read: the first key stored wins, even with concurrent callers.
        dao.pin(KeyPinEntity(node.value, publicKey, clock()))
        return dao.key(node.value)?.contentEquals(publicKey) == true
    }
}
