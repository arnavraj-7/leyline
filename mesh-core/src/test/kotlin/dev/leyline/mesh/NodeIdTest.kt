package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NodeIdTest {
    @Test
    fun `renders as eight hex digits`() {
        assertEquals("000000FF", NodeId(255).toString())
        assertEquals("FFFFFFFF", NodeId(-1).toString())
    }
}
