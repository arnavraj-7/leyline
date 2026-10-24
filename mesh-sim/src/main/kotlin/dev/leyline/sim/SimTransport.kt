package dev.leyline.sim

import dev.leyline.mesh.LinkEvent
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** One node's view of the [VirtualNetwork]: the same contract the BLE transport fulfils on a phone. */
internal class SimTransport(private val self: NodeId, private val network: VirtualNetwork) : Transport {
    val inbox = Channel<LinkEvent>(Channel.UNLIMITED)
    override val events = inbox.receiveAsFlow()

    override suspend fun send(neighbour: NodeId, bytes: ByteArray): Boolean = network.write(self, neighbour, bytes)
}
