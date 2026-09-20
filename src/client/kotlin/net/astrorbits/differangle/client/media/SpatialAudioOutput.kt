package net.astrorbits.differangle.client.media

import net.astrorbits.differangle.media.AudioAttenuation
import net.astrorbits.differangle.media.AudioEmitter
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.media.StereoPcm
import org.lwjgl.openal.AL10.*
import org.watermedia.api.media.engines.ALEngine
import org.watermedia.api.media.engines.SFXEngine.SampleType
import java.nio.ByteBuffer

/** Implemented only on screen-owned WaterMedia outputs. */
interface SpatialAudioOutput {
    fun differangleSpatial(request: MediaRequest)
    fun differangleSources(): IntArray
}

/** The original ALEngine owns the left queue and playback clock; the companion owns the right.
 * The mixin serializes all queue operations and game-thread updates on this object's monitor.
 * Minecraft's existing listener handles movement, rotation and attenuation for both mono sources.
 */
class NativeSpatialAudio(private val leftSource: Int, bufferCount: Int, initial: MediaRequest) {
    private val right = ALEngine(bufferCount)
    private val sources = intArrayOf(leftSource, right.source())
    private val capacity = bufferCount
    private val pcm = StereoPcm()
    private var request = initial
    private var volume = 0f
    var released = false; private set

    init { update(initial) }

    @Synchronized fun sourceIds() = sources.clone()

    @Synchronized fun update(value: MediaRequest) {
        if (released) return
        request = value
        val emitters = value.speakers.ifEmpty { listOf(AudioEmitter(value.x, value.y, value.z)) }
        sources.forEachIndexed { index, source ->
            val emitter = emitters[if (emitters.size == 2) index else 0]
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE)
            alSource3f(source, AL_POSITION, emitter.x.toFloat(), emitter.y.toFloat(), emitter.z.toFloat())
            // These are the same source-distance settings used by vanilla Channel.
            alSourcei(source, SOURCE_DISTANCE_MODEL, if (value.config.attenuation == AudioAttenuation.NONE) AL_NONE else LINEAR_DISTANCE_CLAMPED)
            alSourcef(source, AL_REFERENCE_DISTANCE, 0f)
            alSourcef(source, AL_MAX_DISTANCE, value.config.audibleDistance)
            alSourcef(source, AL_ROLLOFF_FACTOR, 1f)
            alSourcef(source, AL_GAIN, if (!emitter.available) 0f else volume * if (emitters.size == 2) 1f else 0.5f)
        }
    }

    fun format(rate: Int) = right.format(SampleType.S16, 1, rate)

    /** Reserve neither channel unless the right queue has room as well as the original left queue. */
    fun canUpload(): Boolean = !released &&
        alGetSourcei(right.source(), AL_BUFFERS_QUEUED) - alGetSourcei(right.source(), AL_BUFFERS_PROCESSED) < capacity

    fun upload(leftBuffer: Int, stereo: ByteBuffer, rate: Int) {
        pcm.split(stereo)
        check(right.upload(pcm.right)) { "Right audio queue became full during paired upload" }
        alBufferData(leftBuffer, AL_FORMAT_MONO16, pcm.left, rate)
    }

    fun play() {
        if (released) return
        // A vector operation resumes both sources on the same OpenAL mixer sample.
        if (alGetSourcei(leftSource, AL_SOURCE_STATE) != AL_PLAYING) alSourcePlayv(sources)
    }
    fun pause() { if (!released) alSourcePausev(sources) }
    fun speed(value: Float) { if (!released) sources.forEach { alSourcef(it, AL_PITCH, value) } }
    fun volume(value: Float) {
        volume = value
        update(request)
    }
    fun flushRight() { if (!released) right.flush() }
    fun releaseRight() {
        if (released) return
        released = true
        right.release()
    }

    companion object {
        private const val SOURCE_DISTANCE_MODEL = 0xD000
        private const val LINEAR_DISTANCE_CLAMPED = 0xD003
    }
}
