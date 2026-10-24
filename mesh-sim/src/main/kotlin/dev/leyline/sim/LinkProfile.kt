package dev.leyline.sim

import dev.leyline.mesh.LinkFrameCodec
import kotlin.random.Random

/**
 * How one simulated BLE link behaves.
 *
 * [frameSize] is the negotiated MTU minus 3: about 180 on a modern phone, 20 on
 * an old one stuck at the default ATT MTU. Each frame takes [frameMillis] ±
 * [jitterMillis] of air time and is lost with probability [frameLoss]; a lost
 * frame fails the whole packet write, as a failed write-with-response does.
 */
data class LinkProfile(
    val frameSize: Int = 180,
    val frameMillis: Long = 15,
    val jitterMillis: Long = 10,
    val frameLoss: Double = 0.0,
) {
    init {
        require(frameSize >= LinkFrameCodec.MIN_FRAME_SIZE) { "frame size $frameSize below the BLE minimum" }
        require(jitterMillis in 0..frameMillis) { "jitter must keep frame time non-negative" }
        require(frameLoss in 0.0..1.0) { "frame loss is a probability" }
    }

    fun frameTime(random: Random): Long = frameMillis - jitterMillis + random.nextLong(2 * jitterMillis + 1)
}
