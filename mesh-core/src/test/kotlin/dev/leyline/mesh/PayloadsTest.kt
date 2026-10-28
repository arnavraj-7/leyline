package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PayloadsTest {
    @Test
    fun `text only body round trips`() {
        val body = MessageBody("Need water, 3 people, Hall 4 ground floor")
        assertEquals(body, MessageBody.decode(body.encode()))
    }

    @Test
    fun `body with location round trips and costs 11 bytes over the text`() {
        val location = Location.of(25.4920, 81.8639, 12.6f) // MNNIT
        val body = MessageBody("SOS trapped", location)
        val encoded = body.encode()

        assertEquals(1 + 10 + "SOS trapped".length, encoded.size)
        val decoded = MessageBody.decode(encoded)
        assertEquals(body, decoded)
        assertEquals(25.4920, decoded.location!!.latitude, 1e-7)
        assertEquals(12, decoded.location.accuracyMeters)
    }

    @Test
    fun `presence beacon round trips and stays distinct from chat`() {
        val body = MessageBody("", Location.of(25.4920, 81.8639, 8f), "Ayush", presence = true)
        val decoded = MessageBody.decode(body.encode())
        assertEquals(body, decoded)
        assertEquals(true, decoded.presence)
        assertEquals(false, MessageBody.decode(MessageBody("hi").encode()).presence)
    }

    @Test
    fun `non ascii text survives`() {
        val body = MessageBody("मदद चाहिए 🆘")
        assertEquals(body, MessageBody.decode(body.encode()))
    }

    @Test
    fun `280 character sos with location fits one packet payload`() {
        val body = MessageBody("x".repeat(MessageBody.SOS_MAX_CHARS), Location(0, 0, 0))
        assert(body.encode().size <= Packet.MAX_PAYLOAD)
    }

    @Test
    fun `ack round trips and rejects wrong sizes`() {
        val ack = AckBody(MessageId(Long.MIN_VALUE), hops = 3, route = listOf(NodeId(7), NodeId(8), NodeId(1)))
        assertEquals(ack, AckBody.decode(ack.encode()))
        assertEquals(10 + 12, ack.encode().size)
        assertThrows<MalformedPacketException> { AckBody.decode(ByteArray(8)) }
        assertThrows<MalformedPacketException> { AckBody.decode(ack.encode().copyOf(21)) }

        val named = ack.copy(name = "Abhay")
        assertEquals(named, AckBody.decode(named.encode()))
        assertThrows<MalformedPacketException> { AckBody.decode(named.encode().copyOf(25)) }
    }

    @Test
    fun `sender name round trips and long names are cut at a character boundary`() {
        val body = MessageBody("on my way", senderName = "Arnav")
        assertEquals(body, MessageBody.decode(body.encode()))

        val long = MessageBody("hi", senderName = "अ".repeat(20)) // 3 bytes each
        val decoded = MessageBody.decode(long.encode())
        assertEquals("अ".repeat(10), decoded.senderName)
    }

    @Test
    fun `sos details, responses and ring requests round trip`() {
        val location = Location.of(25.4920, 81.8639, 12.6f)
        val sos = MessageBody("Trapped under stairs", location, "Asha", SosDetails(SosKind.TRAPPED, people = 3, injured = true))
        assertEquals(sos, MessageBody.decode(sos.encode()))
        // Triage costs 3 bytes, so a default SOS still fits one 180-byte frame.
        assertEquals(MessageBody("Trapped under stairs", location, "Asha").encode().size + 3, sos.encode().size)

        val reply = MessageBody("On my way", location, "Ayush", response = Response(MessageId(42), ResponseStatus.ON_THE_WAY))
        assertEquals(reply, MessageBody.decode(reply.encode()))
        val ring = MessageBody("", ring = MessageId(-7))
        assertEquals(ring, MessageBody.decode(ring.encode()))
        assertEquals(9, ring.encode().size)
    }

    @Test
    fun `unknown sos kinds read as general and unknown response statuses are rejected`() {
        val body = MessageBody("x", details = SosDetails(SosKind.FIRE)).encode()
        body[1] = 99
        assertEquals(SosKind.GENERAL, MessageBody.decode(body).details!!.kind)

        val reply = MessageBody("x", response = Response(MessageId(1), ResponseStatus.FOUND)).encode()
        reply[9] = 7
        assertThrows<MalformedPacketException> { MessageBody.decode(reply) }
    }

    @Test
    fun `truncated location is rejected`() {
        assertThrows<MalformedPacketException> { MessageBody.decode(byteArrayOf(1, 0, 0)) }
    }
}
