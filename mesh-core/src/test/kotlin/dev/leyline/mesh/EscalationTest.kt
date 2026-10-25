@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.leyline.mesh

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EscalationTest {
    private val a = NodeId(1)
    private val b = NodeId(2)
    private val data = byteArrayOf(2, 1, 2, 3) // starts like a packet

    /**
     * Two phones on fake radios. Bluetooth moves 5 bytes per ms (5 KB/s); a Wi-Fi
     * group takes 2 s to make and 3 s to join, then writes take 1 ms.
     */
    private class Phones(private val test: TestScope, private val config: EscalationConfig = EscalationConfig()) {
        val phones = HashMap<NodeId, Phone>()

        inner class Bluetooth(private val self: NodeId) : Transport {
            val inbox = Channel<LinkEvent>(Channel.UNLIMITED)
            override val events = inbox.receiveAsFlow()
            val sent = mutableListOf<ByteArray>()

            override suspend fun send(neighbour: NodeId, bytes: ByteArray): Boolean {
                delay(1L + bytes.size / 5)
                sent += bytes
                phones.getValue(neighbour).bluetooth.inbox.send(LinkEvent.Received(self, bytes))
                return true
            }
        }

        inner class Wifi(private val self: NodeId) : BulkLink {
            val inbox = Channel<BulkEvent>(Channel.UNLIMITED)
            override val events = inbox.receiveAsFlow()
            val sent = mutableListOf<ByteArray>()
            var hostable = true
            var joinable = true
            var joinWorks = true
            var failSends = false
            var hosting: NodeId? = null
            var linked: NodeId? = null
            var groupsMade = 0

            private fun idle() = hosting == null && linked == null

            override fun canHost() = hostable && idle()

            override fun canJoin() = joinable && idle()

            override suspend fun host(peer: NodeId): BulkOffer? {
                if (!canHost()) return null
                delay(2_000)
                hosting = peer
                groupsMade++
                return BulkOffer("DIRECT-${self.value}", "passphrase", 4_000)
            }

            override suspend fun join(peer: NodeId, offer: BulkOffer): Boolean {
                if (!canJoin()) return false
                delay(3_000)
                val owner = phones.getValue(peer).wifi
                if (!joinWorks || owner.hosting != self || offer.network != "DIRECT-${peer.value}") return false
                linked = peer
                owner.linked = self
                owner.inbox.send(BulkEvent.Up(self))
                inbox.send(BulkEvent.Up(peer))
                return true
            }

            override suspend fun send(peer: NodeId, bytes: ByteArray): Boolean {
                if (linked != peer || failSends) return false
                delay(1)
                sent += bytes
                phones.getValue(peer).wifi.inbox.send(BulkEvent.Received(self, bytes))
                return true
            }

            // Like Wi-Fi Direct: when the group goes, both sides lose the link.
            override fun close(peer: NodeId) {
                if (hosting == peer) hosting = null
                if (linked != peer) return
                linked = null
                inbox.trySend(BulkEvent.Down(peer))
                phones.getValue(peer).wifi.let {
                    it.linked = null
                    if (it.hosting == self) it.hosting = null
                    it.inbox.trySend(BulkEvent.Down(self))
                }
            }
        }

        inner class Phone(val id: NodeId) {
            val bluetooth = Bluetooth(id)
            val wifi = Wifi(id)
            val transport = EscalatingTransport(id, bluetooth, wifi, test.backgroundScope, { test.testScheduler.currentTime }, config)
            val received = mutableListOf<ByteArray>()

            /** Ask control messages this phone sent. */
            val asks get() = bluetooth.sent.count { EscalationControl.isControl(it) && it[1] == 1.toByte() }
        }

        fun phone(id: NodeId, collect: Boolean = true) = Phone(id).also { phone ->
            phones[id] = phone
            if (collect) {
                test.backgroundScope.launch {
                    phone.transport.events.collect { if (it is LinkEvent.Received) phone.received += it.bytes }
                }
            }
        }

        fun run(millis: Long) {
            test.testScheduler.advanceTimeBy(millis)
            test.testScheduler.runCurrent()
        }
    }

    private fun TestScope.linkedPair(config: EscalationConfig = EscalationConfig()): Triple<Phones, Phones.Phone, Phones.Phone> {
        val phones = Phones(this, config)
        val pa = phones.phone(a)
        val pb = phones.phone(b)
        phones.run(0)
        return Triple(phones, pa, pb)
    }

    @Test
    fun `a small backlog stays on bluetooth`() = runTest {
        val (phones, pa, _) = linkedPair()
        pa.transport.backlog(b, 10_000)
        phones.run(10_000)

        assertEquals(0, pa.asks)
        assertTrue(pa.transport.bulkPeers.value.isEmpty())
    }

    @Test
    fun `a big backlog moves the neighbour to wi-fi, and control messages never reach the mesh`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pa.transport.backlog(b, 30_000)
        phones.run(10_000)

        assertEquals(setOf(b), pa.transport.bulkPeers.value)
        assertEquals(setOf(a), pb.transport.bulkPeers.value)
        assertEquals(a, pb.wifi.hosting) // B hosted for A, A joined: one round trip fewer
        assertTrue(pb.received.isEmpty())

        val before = pa.bluetooth.sent.size
        assertTrue(pa.transport.send(b, data))
        phones.run(10)
        assertEquals(before, pa.bluetooth.sent.size)
        assertArrayEquals(data, pb.received.single())
    }

    @Test
    fun `a phone that can't join (Android 9) hosts instead`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pa.wifi.joinable = false
        pa.transport.backlog(b, 30_000)
        phones.run(10_000)

        assertEquals(setOf(b), pa.transport.bulkPeers.value)
        assertEquals(b, pa.wifi.hosting)
        assertNull(pb.wifi.hosting)
    }

    @Test
    fun `no wi-fi on the other phone means declined, and not asked again until the cooldown ends`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pb.wifi.hostable = false
        pb.wifi.joinable = false
        pa.transport.backlog(b, 30_000)
        phones.run(1_000)
        pa.transport.backlog(b, 30_000)
        phones.run(1_000)

        assertEquals(1, pa.asks)
        assertTrue(pa.transport.bulkPeers.value.isEmpty())
        assertTrue(pa.transport.send(b, data)) // still works over bluetooth

        phones.run(EscalationConfig().cooldownMillis)
        pa.transport.backlog(b, 30_000)
        phones.run(1_000)
        assertEquals(2, pa.asks)
    }

    @Test
    fun `a failed wi-fi write falls back to bluetooth`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pa.transport.backlog(b, 30_000)
        phones.run(10_000)
        pa.wifi.failSends = true

        assertTrue(pa.transport.send(b, data))
        phones.run(100)
        assertArrayEquals(data, pb.received.single())
        assertTrue(pa.transport.bulkPeers.value.isEmpty())
        assertTrue(pb.transport.bulkPeers.value.isEmpty())
    }

    @Test
    fun `losing the bluetooth link ends the wi-fi link`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pa.transport.backlog(b, 30_000)
        phones.run(10_000)

        pa.bluetooth.inbox.send(LinkEvent.Disconnected(b))
        phones.run(10)
        assertTrue(pa.transport.bulkPeers.value.isEmpty())
        assertTrue(pb.transport.bulkPeers.value.isEmpty())
        assertNull(pb.wifi.hosting)
    }

    @Test
    fun `an idle wi-fi link closes`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pa.transport.backlog(b, 30_000)
        phones.run(10_000)
        assertEquals(setOf(b), pa.transport.bulkPeers.value)

        phones.run(EscalationConfig().idleMillis + 5_000)
        assertTrue(pa.transport.bulkPeers.value.isEmpty())
        assertTrue(pb.transport.bulkPeers.value.isEmpty())
    }

    @Test
    fun `both phones asking at once make one group`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pa.transport.backlog(b, 30_000)
        pb.transport.backlog(a, 30_000)
        phones.run(10_000)

        assertEquals(setOf(b), pa.transport.bulkPeers.value)
        assertEquals(setOf(a), pb.transport.bulkPeers.value)
        assertEquals(1, pa.wifi.groupsMade + pb.wifi.groupsMade)
    }

    @Test
    fun `a join that fails leaves no group behind`() = runTest {
        val (phones, pa, pb) = linkedPair()
        pa.wifi.joinWorks = false
        pa.transport.backlog(b, 30_000)
        phones.run(6_000) // well before the setup timeout

        assertTrue(pa.transport.bulkPeers.value.isEmpty())
        assertNull(pb.wifi.hosting)
        assertTrue(pa.wifi.canJoin() && pb.wifi.canHost())
    }

    @Test
    fun `a carried backlog crosses mostly over wi-fi and arrives once`() = runTest {
        val phones = Phones(this)
        val pa = phones.phone(a, collect = false)
        val pb = phones.phone(b, collect = false)
        val clock = { testScheduler.currentTime }
        val nodes = listOf(pa, pb).map { phone ->
            MeshNode(phone.id, phone.transport, InMemoryOutbox(), clock).also { it.start(backgroundScope) }
        }
        val delivered = mutableListOf<MeshEvent.Delivered>()
        backgroundScope.launch { nodes[1].events.collect { if (it is MeshEvent.Delivered) delivered += it } }
        phones.run(0)
        val sent = List(60) { nodes[0].send(ByteArray(1_000) { i -> (it + i).toByte() }) }

        // B walks up to A, who has been carrying 60 messages (~62 KB, ~12 s over bluetooth).
        pa.bluetooth.inbox.send(LinkEvent.Connected(b))
        pb.bluetooth.inbox.send(LinkEvent.Connected(a))
        phones.run(30_000)

        assertEquals(sent.map { it.id }.toSet(), delivered.map { it.packet.id }.toSet())
        assertEquals(60, delivered.size)
        assertTrue(pa.wifi.sent.size > 30) { "only ${pa.wifi.sent.size} of 60 went over wi-fi" }
    }

    @Test
    fun `control messages round-trip and reject garbage`() {
        val offer = BulkOffer("DIRECT-xY-Leyline", "8h2kQ9pa", 40_123)
        for (control in listOf(Control.Ask(true, false), Control.Offer(offer), Control.Host, Control.Decline)) {
            assertEquals(control, EscalationControl.decode(EscalationControl.encode(control)))
        }
        assertTrue(EscalationControl.isControl(EscalationControl.encode(Control.Host)))
        assertTrue(!EscalationControl.isControl(data))
        assertThrows(MalformedPacketException::class.java) { EscalationControl.decode(byteArrayOf(0xB1.toByte(), 9)) }
        assertThrows(MalformedPacketException::class.java) {
            EscalationControl.decode(EscalationControl.encode(Control.Offer(offer)).copyOf(10))
        }
    }
}
