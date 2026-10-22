package dev.leyline.mesh

/**
 * Identity of a mesh node, generated once per install and persisted.
 *
 * BLE addresses rotate every few minutes for privacy, so adverts and packets
 * carry this ID instead. Dedup, hop telemetry and topology mode all key on it.
 */
@JvmInline
value class NodeId(val value: Int) {
    override fun toString(): String = "%08X".format(value)

    companion object {
        /** Destination meaning "every node". */
        val BROADCAST = NodeId(-1)
    }
}
