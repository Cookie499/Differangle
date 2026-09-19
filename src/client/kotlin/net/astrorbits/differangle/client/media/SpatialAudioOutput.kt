package net.astrorbits.differangle.client.media

import net.astrorbits.differangle.media.AudioMix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.Supplier

/** Implemented on individual WaterMedia outputs; other players retain their original behavior. */
interface SpatialAudioOutput {
    fun differangleSpatial(mix: Supplier<AudioMix>)
}

/** One stereo output queue keeps both world emitters on the same playback clock. */
class SpatialPcm(private val mix: Supplier<AudioMix>) {
    private var scratch = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    fun process(buffer: ByteBuffer): ByteBuffer {
        val input = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = input.remaining() / 4
        val size = frames * 4
        if (scratch.capacity() < size) scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        scratch.clear()
        val matrix = mix.get()
        repeat(frames) {
            val left = input.short.toFloat()
            val right = input.short.toFloat()
            scratch.putShort((left * matrix.ll + right * matrix.rl).toInt().coerceIn(-32768, 32767).toShort())
            scratch.putShort((left * matrix.lr + right * matrix.rr).toInt().coerceIn(-32768, 32767).toShort())
        }
        scratch.flip()
        return scratch
    }
}
