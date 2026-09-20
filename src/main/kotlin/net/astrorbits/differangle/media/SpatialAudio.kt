package net.astrorbits.differangle.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioEmitter(val x: Double, val y: Double, val z: Double, val available: Boolean = true)

/** Raw, listener-independent mono PCM. Positions and gains are applied at playback by OpenAL. */
class StereoPcm {
    var left: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()); private set
    var right: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()); private set

    fun split(buffer: ByteBuffer) {
        require(buffer.remaining() % 4 == 0) { "Expected complete S16 stereo frames" }
        val size = buffer.remaining() / 2
        if (left.capacity() < size) {
            left = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            right = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        left.clear(); right.clear()
        val input = buffer.duplicate().order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) {
            left.putShort(input.short)
            right.putShort(input.short)
        }
        left.flip(); right.flip()
    }
}
