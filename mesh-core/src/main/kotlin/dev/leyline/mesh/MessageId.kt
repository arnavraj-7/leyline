package dev.leyline.mesh

/**
 * Random 64-bit identity of one message. Relays copy it unchanged, so every
 * node can recognise a packet it has already handled.
 */
@JvmInline
value class MessageId(val value: Long) {
    override fun toString(): String = "%016X".format(value)
}
