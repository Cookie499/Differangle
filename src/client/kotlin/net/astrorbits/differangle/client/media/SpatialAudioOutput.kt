package net.astrorbits.differangle.client.media

import net.astrorbits.differangle.media.AudioAttenuation
import net.astrorbits.differangle.media.AudioEmitter
import net.astrorbits.differangle.media.MediaRequest
import org.lwjgl.openal.AL10.*
import org.watermedia.api.player.ALEngine
import org.watermedia.api.player.PlayerAPI

class SpatialAudioOutput(bufferCount: Int = ALEngine.DEFAULT_BUFFER_COUNT) {
    val engine: ALEngine = PlayerAPI.alEngine(bufferCount, true).also { engine ->
        try {
            check(engine.sinks().single().mix(ALEngine.Mix.LEFT)) { "Could not route the left audio channel" }
            checkNotNull(engine.addSink(ALEngine.Mix.RIGHT)) { "Could not create the right audio sink" }
        } catch (failure: Throwable) {
            runCatching(engine::release).onFailure(failure::addSuppressed)
            throw failure
        }
    }

    fun update(request: MediaRequest) = synchronized(engine) {
        if (engine.released()) return@synchronized
        val emitters = request.speakers.ifEmpty { listOf(AudioEmitter(request.x, request.y, request.z)) }
        val attenuation = if (request.config.attenuation == AudioAttenuation.NONE) AL_NONE else LINEAR_DISTANCE_CLAMPED
        val gain = engine.volume() * if (emitters.size == 2) 1f else 0.5f
        engine.sinks().forEachIndexed { index, sink ->
            val emitter = emitters[if (emitters.size == 2) index else 0]
            val source = sink.source()
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE)
            alSource3f(source, AL_POSITION, emitter.x.toFloat(), emitter.y.toFloat(), emitter.z.toFloat())
            alSourcei(source, SOURCE_DISTANCE_MODEL, attenuation)
            alSourcef(source, AL_REFERENCE_DISTANCE, 0f)
            alSourcef(source, AL_MAX_DISTANCE, request.config.audibleDistance)
            alSourcef(source, AL_ROLLOFF_FACTOR, 1f)
            alSourcef(source, AL_GAIN, if (emitter.available) gain else 0f)
        }
    }

    private companion object {
        const val SOURCE_DISTANCE_MODEL = 0xD000
        const val LINEAR_DISTANCE_CLAMPED = 0xD003
    }
}
