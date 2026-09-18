package net.astrorbits.differangle.client.media

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import net.astrorbits.differangle.media.AudioAttenuation
import net.astrorbits.differangle.media.MediaBackend
import net.astrorbits.differangle.media.MediaNetworkPolicy
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.media.MediaSession
import net.astrorbits.differangle.media.MediaSourceType
import net.astrorbits.differangle.media.MediaUrls
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.sounds.SoundSource
import org.lwjgl.openal.AL10
import org.slf4j.LoggerFactory
import org.watermedia.api.media.MRL
import org.watermedia.api.media.MediaAPI
import org.watermedia.api.media.engines.AWTEngine
import org.watermedia.api.media.players.MediaPlayer
import org.watermedia.api.util.MediaQuality
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.net.InetAddress
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Cross-platform WaterMedia adapter. Software frames keep the Minecraft GPU backend abstract. */
class WaterMediaBackend : MediaBackend {
    override fun open(request: MediaRequest): MediaSession {
        require(request.config.sourceType in setOf(MediaSourceType.VIDEO, MediaSourceType.BILIBILI))
        val uri = MediaUrls.requireHttp(request.config.sourceUrl)
        MediaNetworkPolicy.requirePublic(InetAddress.getAllByName(uri.host).asIterable())
        return WaterMediaSession(request, MediaAPI.mrl(uri))
    }
}

private class WaterMediaSession(
    initialRequest: MediaRequest,
    private val mrl: MRL,
) : TextureMediaSession {
    @Volatile override var request: MediaRequest = initialRequest
        private set
    private val queue = ArrayDeque<NativeImage>(MAX_QUEUED_FRAMES)
    private var player: MediaPlayer? = null
    private var texture: DynamicTexture? = null
    private var attempted = false
    private var configuredAudioSource = 0
    private var synchronizationTicks = 0
    @Volatile private var closed = false
    @Volatile override var failure: String? = null
        private set

    @Synchronized
    override fun update(request: MediaRequest): Boolean {
        if (closed) return false
        val previous = this.request
        val old = previous.config
        val next = request.config
        if (old.sourceType != next.sourceType || old.sourceUrl != next.sourceUrl ||
            old.audioEnabled != next.audioEnabled) return false

        this.request = request
        val active = player ?: return true
        if (previous.width != request.width || previous.height != request.height) {
            active.maxSize(request.width, request.height)
        }
        if (old.maxVideoHeight != next.maxVideoHeight) active.quality(MediaQuality.of(next.maxVideoHeight))
        if (old.loop != next.loop) active.repeat(next.loop)
        active.volume(mediaVolume())
        if (old.attenuation != next.attenuation || old.audibleDistance != next.audibleDistance ||
            previous.x != request.x || previous.y != request.y || previous.z != request.z) {
            configuredAudioSource = 0
            updateAudio(active)
        }

        val target = synchronizedPositionMillis(active)
        val actual = active.time()
        val timelineChanged = old.positionSeconds != next.positionSeconds ||
            old.positionGameTime != next.positionGameTime
        if (old.playing != next.playing) {
            if (next.playing) {
                if (actual >= 0L && abs(actual - target) > CONTROL_SEEK_DRIFT_MILLIS) active.seek(target)
                active.pause(false)
            } else {
                active.pause(true)
                if (actual >= 0L && abs(actual - target) > MAXIMUM_PAUSED_DRIFT_MILLIS) active.seek(target)
            }
        } else if (timelineChanged && actual >= 0L && abs(actual - target) > CONTROL_SEEK_DRIFT_MILLIS) {
            active.seek(target)
        }
        return true
    }

    override fun tick() {
        if (closed) return
        val active = player
        if (active == null) {
            when (mrl.status()) {
                MRL.Status.FETCHING -> Unit
                MRL.Status.LOADED -> if (!attempted) createPlayer()
                MRL.Status.EXPIRED, MRL.Status.FORGOTTEN -> {
                    attempted = false
                    mrl.reload()
                }
                MRL.Status.ERROR, MRL.Status.BLOCKED -> fail(mrl.exception())
            }
            return
        }
        if (active.error()) fail(IllegalStateException("WaterMedia playback failed"))
        updateAudio(active)
        if (++synchronizationTicks >= SYNCHRONIZATION_INTERVAL_TICKS) {
            synchronizationTicks = 0
            synchronizePlayback(active)
        }
    }

    private fun createPlayer() {
        attempted = true
        var output: AWTEngine? = null
        var created: MediaPlayer? = null
        try {
            created = MediaAPI.createPlayer(
                mrl,
                {
                    MediaAPI.awtEngine {
                        output?.image()?.let(::capture)
                    }.also { output = it }
                },
                { if (config.audioEnabled) MediaAPI.alEngine() else null },
            ) ?: run {
                fail(IllegalStateException("WaterMedia could not create a player"))
                return
            }
            player = created
            created.maxSize(request.width, request.height)
            created.quality(MediaQuality.of(config.maxVideoHeight))
            created.repeat(config.loop)
            created.volume(mediaVolume())
            if (config.playing) created.start() else created.startPaused()
            val target = synchronizedPositionMillis(created)
            if (target > 0L) created.seek(target)
        } catch (cause: Throwable) {
            if (created != null) created.release() else output?.release()
            player = null
            fail(cause)
        }
    }

    private fun capture(source: BufferedImage) {
        if (closed) return
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return
        val pixels = (source.raster.dataBuffer as DataBufferInt).data
        val image = NativeImage(width, height, false)
        try {
            val destination = image.pixelBytes
            val flipVertically = config.sourceType == MediaSourceType.BILIBILI
            for (destinationY in 0 until height) {
                // Bilibili needs Z +180 degrees followed by a horizontal mirror. Combined,
                // those operations are a vertical flip, so reverse rows during the copy.
                val sourceY = if (flipVertically) height - 1 - destinationY else destinationY
                val rowStart = sourceY * width
                for (x in 0 until width) {
                    val argb = pixels[rowStart + x]
                    destination.put(((argb ushr 16) and 0xff).toByte())
                    destination.put(((argb ushr 8) and 0xff).toByte())
                    destination.put((argb and 0xff).toByte())
                    destination.put(((argb ushr 24) and 0xff).toByte())
                }
            }
            destination.flip()
            synchronized(queue) {
                while (queue.size >= MAX_QUEUED_FRAMES) queue.removeFirst().close()
                if (closed) image.close() else queue.addLast(image)
            }
        } catch (cause: Throwable) {
            image.close()
            throw cause
        }
    }

    private fun updateAudio(active: MediaPlayer) {
        if (!config.audioEnabled) return
        val source = active.audioSource()
        if (source <= 0) return
        active.volume(mediaVolume())
        if (configuredAudioSource == source) return
        configuredAudioSource = source
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, if (config.attenuation == AudioAttenuation.NONE) AL10.AL_TRUE else AL10.AL_FALSE)
        if (config.attenuation == AudioAttenuation.NONE) {
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
            AL10.alSourcei(source, SOURCE_DISTANCE_MODEL, AL10.AL_NONE)
        } else {
            AL10.alSource3f(source, AL10.AL_POSITION, request.x.toFloat(), request.y.toFloat(), request.z.toFloat())
            AL10.alSourcei(source, SOURCE_DISTANCE_MODEL, LINEAR_DISTANCE_CLAMPED)
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, config.audibleDistance)
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1f)
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0f)
        }
    }

    private fun synchronizePlayback(active: MediaPlayer) {
        // Minecraft game ticks and WaterMedia's audio clock have slightly different rates. Comparing
        // their continuously advancing values and hard-seeking at a small threshold eventually causes
        // periodic seeks. With split Bilibili DASH streams those seeks can starve the video queue while
        // the audio/master clock keeps advancing. Playing sessions therefore free-run after their initial
        // authoritative seek; the server establishes a new anchor for explicit playback changes.
        if (config.playing || !active.canSeek() || active.loading() || active.buffering()) return
        val actual = active.time()
        if (actual < 0L) return
        val target = synchronizedPositionMillis(active)
        if (abs(actual - target) > MAXIMUM_PAUSED_DRIFT_MILLIS) active.seek(target)
    }

    private fun synchronizedPositionMillis(active: MediaPlayer): Long {
        val gameTime = Minecraft.getInstance().level?.gameTime ?: config.positionGameTime
        var target = (config.positionAt(gameTime) * 1000.0).roundToLong().coerceAtLeast(0L)
        val duration = active.duration()
        if (duration > 0L) target = if (config.loop) Math.floorMod(target, duration) else target.coerceAtMost(duration)
        return target
    }

    private fun mediaVolume(): Int {
        val category = Minecraft.getInstance().options.getFinalSoundSourceVolume(SoundSource.RECORDS)
        return (config.volume * category * 100f).roundToInt().coerceIn(0, 100)
    }

    @Synchronized
    override fun textureView(): GpuTextureView? {
        RenderSystem.assertOnRenderThread()
        var newest: NativeImage? = null
        synchronized(queue) {
            while (queue.isNotEmpty()) {
                newest?.close()
                newest = queue.removeFirst()
            }
        }
        newest?.let { frame ->
            val current = texture
            if (current == null || current.pixels.width != frame.width || current.pixels.height != frame.height) {
                current?.close()
                texture = DynamicTexture({ "Differangle WaterMedia ${config.sourceUrl}" }, frame)
            } else {
                current.setPixels(frame)
                current.upload()
            }
        }
        return texture?.textureView
    }

    private fun fail(cause: Throwable?) {
        if (failure != null || closed) return
        failure = cause?.message ?: "Unknown WaterMedia error"
        LOGGER.warn("Could not play media {}: {}", config.sourceUrl, failure, cause)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        player?.release()
        player = null
        synchronized(queue) { while (queue.isNotEmpty()) queue.removeFirst().close() }
        texture?.close()
        texture = null
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("Differangle")
        private const val MAX_QUEUED_FRAMES = 2
        private const val SYNCHRONIZATION_INTERVAL_TICKS = 20
        private const val CONTROL_SEEK_DRIFT_MILLIS = 1_000L
        private const val MAXIMUM_PAUSED_DRIFT_MILLIS = 100L
        // AL_EXT_source_distance_model constants used by Minecraft's Channel implementation.
        private const val SOURCE_DISTANCE_MODEL = 0xD000
        private const val LINEAR_DISTANCE_CLAMPED = 0xD003
    }
}
