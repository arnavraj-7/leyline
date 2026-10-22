package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.interfaces.ECPublicKey

class SealingTest {
    private val alice = Sealing.generate()
    private val bob = Sealing.generate()
    private val alicePub = P256.compress(alice.public as ECPublicKey)
    private val bobPub = P256.compress(bob.public as ECPublicKey)
    private val a = NodeId(1)
    private val b = NodeId(2)
    private val plain = MessageBody("Meet at the water tank, bring the first aid kit").encode()

    @Test
    fun `only the recipient can open a sealed message`() {
        val sealed = Sealing.seal(plain, alice.private, bobPub, a, b)
        assertEquals(plain.size + Sealing.OVERHEAD, sealed.size)
        assertArrayEquals(plain, Sealing.open(sealed, bob.private, alicePub, a, b))

        // A relay with its own key, or one pretending the message came from it, gets nothing.
        val mallory = Sealing.generate()
        val malloryPub = P256.compress(mallory.public as ECPublicKey)
        assertNull(Sealing.open(sealed, mallory.private, alicePub, a, b))
        assertNull(Sealing.open(sealed, bob.private, malloryPub, a, b))
    }

    @Test
    fun `tampering or rerouting is detected`() {
        val sealed = Sealing.seal(plain, alice.private, bobPub, a, b)
        val flipped = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(Sealing.open(flipped, bob.private, alicePub, a, b))
        // The ids are bound in, so the same bytes can't be passed off as addressed elsewhere.
        assertNull(Sealing.open(sealed, bob.private, alicePub, a, NodeId(3)))
        assertNull(Sealing.open(byteArrayOf(1, 2, 3), bob.private, alicePub, a, b))
    }

    @Test
    fun `the same text sealed twice looks different`() {
        val one = Sealing.seal(plain, alice.private, bobPub, a, b)
        val two = Sealing.seal(plain, alice.private, bobPub, a, b)
        assert(!one.contentEquals(two))
    }

    @Test
    fun `a sealed body with its sender's key round trips through the payload format`() {
        val sealed = Sealing.seal(plain, alice.private, bobPub, a, b)
        val outer = MessageBody("", senderName = "Asha", boxKey = alicePub, sealed = sealed)
        val decoded = MessageBody.decode(outer.encode())
        assertEquals(outer, decoded)
        assertEquals(MessageBody.decode(plain), MessageBody.decode(Sealing.open(decoded.sealed!!, bob.private, decoded.boxKey!!, a, b)!!))
    }
}
