package net.astrorbits.differangle.media

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpatialAudioTest {
    private fun pcm(vararg samples: Int): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2)
        .order(ByteOrder.nativeOrder()).also { buffer -> samples.forEach { buffer.putShort(it.toShort()) } }.flip()
    private fun samples(buffer: ByteBuffer) = buffer.duplicate().order(ByteOrder.nativeOrder()).let { input ->
        List(input.remaining() / 2) { input.short.toInt() }
    }

    @Test fun `splits channels losslessly including full scale and silence`() {
        val split = StereoPcm()
        split.split(pcm(-32768, 32767, 1234, -2345, 0, 0))
        assertEquals(listOf(-32768, 1234, 0), samples(split.left))
        assertEquals(listOf(32767, -2345, 0), samples(split.right))
    }
    @Test fun `honors decoder buffer range without consuming it`() {
        val input = pcm(999, 999, 1, 2, 3, 4, 999, 999)
        input.position(4); input.limit(12)
        val split = StereoPcm()
        split.split(input)
        assertEquals(listOf(1, 3), samples(split.left))
        assertEquals(listOf(2, 4), samples(split.right))
        assertEquals(4, input.position())
        assertEquals(12, input.limit())
    }
    @Test fun `retrying a frame does not change its samples`() {
        val input = pcm(1234, -2345)
        val split = StereoPcm()
        repeat(3) {
            split.split(input)
            assertEquals(listOf(1234), samples(split.left))
            assertEquals(listOf(-2345), samples(split.right))
        }
    }
    @Test fun `reused buffers do not include stale samples from longer frames`() {
        val split = StereoPcm()
        split.split(pcm(1, 2, 3, 4))
        split.split(pcm(5, 6))
        assertEquals(listOf(5), samples(split.left))
        assertEquals(listOf(6), samples(split.right))
        assertTrue(split.left.isDirect && split.right.isDirect)
    }
    @Test fun `supports empty buffers`() {
        val split = StereoPcm()
        split.split(pcm())
        assertEquals(0, split.left.remaining())
        assertEquals(0, split.right.remaining())
    }
    @Test fun `rejects incomplete stereo frames`() {
        assertThrows(IllegalArgumentException::class.java) { StereoPcm().split(pcm(1)) }
    }
}
