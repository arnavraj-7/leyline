package dev.leyline.sim

import dev.leyline.mesh.NodeId
import dev.leyline.mesh.PacketCodec
import dev.leyline.mesh.PacketType
import dev.leyline.mesh.Priority
import dev.leyline.mesh.Router
import dev.leyline.mesh.SeenCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The simulator's own link model: if this is wrong, every scenario number is wrong. */
class VirtualNetworkTest {
    private val bigSos = sosBody("SOS ".repeat(70))

    @Test
    fun `a 280-char SOS is chunked into 20-byte frames and reassembled`() {
        staticNetwork(1, Stack.LEYLINE, Topology.line(2), profile = LinkProfile(frameSize = 20)).use { net ->
            val sos = net.send(1, bigSos, Priority.SOS)
            net.runFor(5_000)
            assertNotNull(net.recorder.delivery(sos.id, NodeId(2)))
            val frames = (PacketCodec.encodedSize(sos) + 14) / 15
            assertTrue(net.traffic.of(NodeId(1)).frames >= frames, "expected at least $frames frames")
        }
    }

    @Test
    fun `a write in progress fails when the link drops`() {
        VirtualNetwork(1).use { net ->
            net.add(1, Stack.NAIVE)
            net.add(2, Stack.NAIVE)
            net.connect(1, 2, LinkProfile(frameSize = 20))
            val packet = Router(NodeId(1), SeenCache(10, 1_000, net.clock), net.clock)
                .originate(bigSos, priority = Priority.SOS, type = PacketType.MESSAGE)
            var result: Boolean? = null
            net.scope.launch { result = net.write(NodeId(1), NodeId(2), PacketCodec.encode(packet)) }
            net.runFor(60) // a few of ~22 frames
            net.disconnect(1, 2)
            net.runFor(1_000)
            assertEquals(false, result)
            assertEquals(1, net.traffic.of(NodeId(1)).failedWrites)
        }
    }

    @Test
    fun `the same seed gives the same run`() {
        assertEquals(churn(3).runs, churn(3).runs)
        assertEquals(storm(3).runs, storm(3).runs)
    }

    @Test
    fun `a link flapping every few frames never crashes a node and the SOS still arrives`() {
        staticNetwork(1, Stack.LEYLINE, Topology.line(3), profile = LinkProfile(frameSize = 20)).use { net ->
            val flapping = net.scope.launch {
                while (true) {
                    delay(37)
                    net.disconnect(2, 3)
                    delay(11)
                    net.connect(2, 3, LinkProfile(frameSize = 20))
                }
            }
            val sos = net.send(1, bigSos, Priority.SOS)
            net.runFor(10_000)
            flapping.cancel()
            net.connect(2, 3, LinkProfile(frameSize = 20))
            net.runFor(30_000)
            assertTrue(net.failures.isEmpty(), "node crashed: ${net.failures}")
            assertNotNull(net.recorder.delivery(sos.id, NodeId(3)))
            assertEquals(0, net.recorder.repeatDeliveries)
        }
    }

    @Test
    fun `mobility links nodes only while in range`() {
        VirtualNetwork(1).use { net ->
            net.add(1)
            net.add(2)
            val mobility = Mobility(net, range = 50.0)
            mobility.place(1, Point(0.0, 0.0))
            mobility.place(2, Point(100.0, 0.0), Patrol(listOf(Point(0.0, 0.0), Point(100.0, 0.0)), speed = 10.0))
            mobility.start()
            net.runFor(1_000)
            assertFalse(net.isLinked(1, 2))
            net.runFor(7_000) // now ~20 m apart
            assertTrue(net.isLinked(1, 2))
            net.runFor(8_000) // walking back out
            assertFalse(net.isLinked(1, 2))
        }
    }
}
