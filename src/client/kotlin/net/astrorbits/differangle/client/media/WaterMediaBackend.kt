package net.astrorbits.differangle.client.media

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import net.astrorbits.differangle.camera.Resolution
import net.astrorbits.differangle.media.MediaBackend
import net.astrorbits.differangle.media.MediaNetworkPolicy
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.media.MediaSession
import net.astrorbits.differangle.media.MediaSourceType
import net.astrorbits.differangle.media.MediaUrls
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.sounds.SoundSource
import org.slf4j.LoggerFactory
import org.watermedia.WaterMediaConfig
import org.watermedia.api.codecs.CodecsAPI
import org.watermedia.api.player.AWTEngine
import org.watermedia.api.player.MRL
import org.watermedia.api.player.MediaPlayer
import org.watermedia.api.player.PlayerAPI
import org.watermedia.api.util.MediaQuality
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Cross-platform WaterMedia adapter. Software frames keep the Minecraft GPU backend abstract. */
class WaterMediaBackend : MediaBackend {
    override fun open(request: MediaRequest): MediaSession {
        require(request.config.sourceType.isVideo)
        return WaterMediaSession(request) {
            ensureSoftwareDecoding()
            val uri = MediaUrls.requireHttp(request.config.sourceUrl)
            MediaNetworkPolicy.requirePublic(InetAddress.getAllByName(uri.host).asIterable())
            PlayerAPI.mrl(uri)
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("Differangle")
        @Volatile private var softwareDecodingConfigured = false

        @Synchronized
        private fun ensureSoftwareDecoding() {
            if (softwareDecodingConfigured) return
            softwareDecodingConfigured = true
            if (WaterMediaConfig.codecs.ffmpeg.hardwareAccel) {
                WaterMediaConfig.codecs.ffmpeg.hardwareAccel = false
                LOGGER.warn(
                    "Disabled WaterMedia hardware video decoding because its CUDA path stops " +
                        "releasing frames after approximately 33,520 decoded frames",
                )
            }
        }
    }
}

private class WaterMediaSession(
    initialRequest: MediaRequest,
    private val resolveMrl: () -> MRL,
) : TextureMediaSession {
    private var mrl: MRL? = null
    @Volatile override var request: MediaRequest = initialRequest
        private set
    private val frameLock = Any()
    private var pendingPixels = IntArray(0)
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var sourceResolution: Resolution? = null
    private var hasPendingFrame = false
    private var player: MediaPlayer? = null
    private var texture: DynamicTexture? = null
    private var attempted = false
    @Volatile private var audioOutput: SpatialAudioOutput? = null
    private var synchronizationTicks = 0
    @Volatile private var playerGeneration = 0
    private var playerStartedNanos = 0L
    private var observedPlayerTime = -1L
    private var observedPlayerTimeNanos = 0L
    private var recoveryCount = 0
    private var pendingSeek = false
    private var localEnded = false
    private var resolvedDurationMillis = 0L
    override val playbackDurationMillis get() = config.durationMillis.takeIf { it > 0L } ?: resolvedDurationMillis
    override val playbackEnded get() = config.finished || localEnded
    @Volatile private var lastCapturedFrameNanos = 0L
    @Volatile private var frameCallbackFailureLogged = false
    @Volatile private var closed = false
    @Volatile override var failure: String? = null
        private set

    @Synchronized
    override fun update(request: MediaRequest): Boolean {
        if (closed) return false
        val previous = this.request
        val old = previous.config
        val next = request.config
        val timelineChanged = old.positionSeconds != next.positionSeconds ||
            old.positionGameTime != next.positionGameTime
        if (old.sourceType != next.sourceType || old.sourceUrl != next.sourceUrl ||
            old.audioEnabled != next.audioEnabled || old.finished != next.finished ||
            localEnded && (timelineChanged || old.playing != next.playing)) return false

        this.request = request
        val active = player ?: return true
        if (old.maxVideoHeight != next.maxVideoHeight) active.quality(MediaQuality.of(next.maxVideoHeight))
        if (old.loop != next.loop) active.repeat(next.loop)
        updateAudio(active)

        val target = synchronizedPositionMillis(active)
        val actual = active.time()
        if (old.playing != next.playing) {
            if (next.playing) {
                pendingSeek = actual < 0L || abs(actual - target) > CONTROL_SEEK_DRIFT_MILLIS
                active.pause(false)
            } else {
                active.pause(true)
                pendingSeek = actual < 0L || abs(actual - target) > MAXIMUM_PAUSED_DRIFT_MILLIS
            }
        } else if (timelineChanged) pendingSeek = true
        return true
    }

    override fun tick() {
        if (closed || playbackEnded) return
        val active = player
        if (active == null) {
            if (!CodecsAPI.ffmpegLoaded()) {
                fail(IllegalStateException("Video playback requires the FFmpeg libraries from WaterMedia Binaries"))
                return
            }
            val mrl = mrl ?: resolveMrl().also { this.mrl = it }
            when (mrl.status()) {
                MRL.Status.FETCHING -> Unit
                MRL.Status.LOADED -> if (!attempted) createPlayer(mrl)
                MRL.Status.EXPIRED, MRL.Status.FORGOTTEN -> {
                    attempted = false
                    this.mrl = mrl.reload()
                }
                MRL.Status.ERROR, MRL.Status.BLOCKED -> fail(mrl.exception())
            }
            return
        }
        if (active.error()) fail(IllegalStateException("WaterMedia playback failed"))
        updateSourceResolution(active)
        if (!active.live() && active.duration() > 0L) resolvedDurationMillis = active.duration()
        if (config.playing && !config.loop && active.ended() && !pendingSeek) {
            if (resolvedDurationMillis == 0L) resolvedDurationMillis = active.time().coerceAtLeast(0L)
            localEnded = true
            playerGeneration++
            player = null
            audioOutput = null
            active.release()
            synchronized(frameLock) { hasPendingFrame = false }
            texture?.close()
            texture = null
            return
        }
        updateAudio(active)
        applyPendingSeek(active)
        monitorVideoFrames(active)
        if (++synchronizationTicks >= SYNCHRONIZATION_INTERVAL_TICKS) {
            synchronizationTicks = 0
            synchronizePlayback(active)
        }
    }

    private fun createPlayer(mrl: MRL) {
        attempted = true
        val generation = ++playerGeneration
        playerStartedNanos = System.nanoTime()
        lastCapturedFrameNanos = 0L
        observedPlayerTime = -1L
        observedPlayerTimeNanos = 0L
        frameCallbackFailureLogged = false
        var output: AWTEngine? = null
        var created: MediaPlayer? = null
        try {
            created = PlayerAPI.player(
                mrl,
                {
                    PlayerAPI.awtEngine {
                        if (generation == playerGeneration && !closed) {
                            try {
                                output?.image()?.let(::capture)
                            } catch (cause: Exception) {
                                if (!frameCallbackFailureLogged) {
                                    frameCallbackFailureLogged = true
                                    LOGGER.warn("Failed to copy a WaterMedia video frame for {}", config.sourceUrl, cause)
                                }
                            }
                        }
                    }.also { output = it }
                },
                { if (config.audioEnabled) SpatialAudioOutput().also { audioOutput = it }.engine else null },
            ) ?: run {
                audioOutput = null
                fail(IllegalStateException("WaterMedia could not create a player"))
                return
            }
            player = created
            val limit = videoDimensionLimit()
            created.scale(limit, limit)
            sourceResolution = null
            created.quality(MediaQuality.of(config.maxVideoHeight))
            created.repeat(config.loop)
            updateAudio(created)
            if (config.playing) created.start() else created.startPaused()
            pendingSeek = config.positionSeconds > 0.0 || config.playing
        } catch (cause: Throwable) {
            if (created != null) {
                runCatching(created::release).onFailure(cause::addSuppressed)
            } else {
                runCatching { output?.release() }.onFailure(cause::addSuppressed)
                runCatching { audioOutput?.engine?.release() }.onFailure(cause::addSuppressed)
            }
            player = null
            audioOutput = null
            fail(cause)
        }
    }

    private fun capture(source: BufferedImage) {
        if (closed) return
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return
        val pixels = (source.raster.dataBuffer as DataBufferInt).data
        synchronized(frameLock) {
            val size = width * height
            if (pendingPixels.size != size) pendingPixels = IntArray(size)
            val flipVertically = config.sourceType == MediaSourceType.BILIBILI
            for (destinationY in 0 until height) {
                // Bilibili needs Z +180 degrees followed by a horizontal mirror. Combined,
                // those operations are a vertical flip, so reverse rows during the copy.
                val sourceY = if (flipVertically) height - 1 - destinationY else destinationY
                System.arraycopy(pixels, sourceY * width, pendingPixels, destinationY * width, width)
            }
            pendingWidth = width
            pendingHeight = height
            hasPendingFrame = true
            lastCapturedFrameNanos = System.nanoTime()
        }
    }

    private fun videoDimensionLimit() =
        minOf(MAX_VIDEO_DIMENSION, RenderSystem.getDevice().deviceInfo.limits().maxTextureSize())

    private fun updateSourceResolution(active: MediaPlayer) {
        val width = active.sourceWidth()
        val height = active.sourceHeight()
        if (width <= 0 || height <= 0 || sourceResolution?.let { it.width == width && it.height == height } == true) return
        val native = Resolution(width, height)
        sourceResolution = native
        val capped = native.limitedTo(videoDimensionLimit())
        active.scale(capped.width, capped.height)
    }

    private fun applyPendingSeek(active: MediaPlayer) {
        if (!pendingSeek || active.loading() || active.buffering()) return
        if (!active.canSeek()) {
            if (active.live() || active.ended()) pendingSeek = false
            return
        }
        val target = synchronizedPositionMillis(active)
        if (active.time() == target || active.seek(target) || active.ended()) pendingSeek = false
    }

    private fun updateAudio(active: MediaPlayer) {
        if (!config.audioEnabled) return
        active.volume(mediaVolume())
        audioOutput?.update(request)
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

    private fun monitorVideoFrames(active: MediaPlayer) {
        val now = System.nanoTime()
        if (!config.playing || active.paused() || active.loading() || active.buffering() || active.error()) {
            observedPlayerTime = active.time()
            observedPlayerTimeNanos = now
            return
        }
        if (now - playerStartedNanos < PLAYER_STARTUP_GRACE_NANOS) return

        val currentTime = active.time()
        if (currentTime < 0L) return
        if (observedPlayerTimeNanos == 0L) {
            observedPlayerTime = currentTime
            observedPlayerTimeNanos = now
            return
        }
        if (now - observedPlayerTimeNanos < STALL_OBSERVATION_INTERVAL_NANOS) return

        val clockAdvanced = currentTime - observedPlayerTime
        observedPlayerTime = currentTime
        observedPlayerTimeNanos = now
        val lastFrame = lastCapturedFrameNanos
        val frameAge = if (lastFrame == 0L) now - playerStartedNanos else now - lastFrame
        if (clockAdvanced >= MINIMUM_CLOCK_ADVANCE_MILLIS && frameAge >= VIDEO_STALL_TIMEOUT_NANOS) {
            recoverStalledPlayer(active, currentTime, frameAge)
        }
    }

    private fun recoverStalledPlayer(active: MediaPlayer, playerTime: Long, frameAgeNanos: Long) {
        val target = synchronizedPositionMillis(active)
        recoveryCount++
        LOGGER.warn(
            "WaterMedia video stopped producing frames for {} ms while its clock advanced at {} ms " +
                "(server target {} ms); rebuilding player for {} (recovery #{})",
            frameAgeNanos / NANOS_PER_MILLISECOND,
            playerTime,
            target,
            config.sourceUrl,
            recoveryCount,
        )

        // Invalidate the old callback before release, but retain the uploaded texture so the screen
        // continues displaying its last valid frame until the replacement player produces one.
        playerGeneration++
        player = null
        attempted = false
        audioOutput = null
        synchronizationTicks = 0
        playerStartedNanos = 0L
        pendingSeek = false
        lastCapturedFrameNanos = 0L
        observedPlayerTime = -1L
        observedPlayerTimeNanos = 0L
        active.release()
        synchronized(frameLock) { hasPendingFrame = false }
        try {
            // Bilibili playback uses expiring resolved DASH URLs. Reloading the MRL obtains fresh
            // stream URLs as well as resetting FFmpeg's demux/decode state.
            mrl = mrl?.reload()
        } catch (cause: Throwable) {
            fail(cause)
        }
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
        if (playbackEnded) return null
        synchronized(frameLock) {
            if (!hasPendingFrame) return texture?.textureView
            val current = texture
            if (current == null || current.pixels.width != pendingWidth || current.pixels.height != pendingHeight) {
                current?.close()
                val image = NativeImage(pendingWidth, pendingHeight, false)
                try {
                    copyPixelsToNativeImage(image)
                    texture = DynamicTexture({ "Differangle WaterMedia ${config.sourceUrl}" }, image)
                } catch (cause: Throwable) {
                    image.close()
                    throw cause
                }
            } else {
                copyPixelsToNativeImage(current.pixels)
                current.upload()
            }
            hasPendingFrame = false
        }
        return texture?.textureView
    }

    private fun copyPixelsToNativeImage(image: NativeImage) {
        val destination = image.pixelBytes
        destination.clear()
        for (argb in pendingPixels) {
            destination.put(((argb ushr 16) and 0xff).toByte())
            destination.put(((argb ushr 8) and 0xff).toByte())
            destination.put((argb and 0xff).toByte())
            destination.put(((argb ushr 24) and 0xff).toByte())
        }
        destination.flip()
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
        playerGeneration++
        audioOutput = null
        player?.release()
        player = null
        synchronized(frameLock) {
            hasPendingFrame = false
            pendingPixels = IntArray(0)
            pendingWidth = 0
            pendingHeight = 0
        }
        texture?.close()
        texture = null
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("Differangle")
        private const val MAX_VIDEO_DIMENSION = 8192
        private const val SYNCHRONIZATION_INTERVAL_TICKS = 20
        private const val CONTROL_SEEK_DRIFT_MILLIS = 1_000L
        private const val MAXIMUM_PAUSED_DRIFT_MILLIS = 100L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MINIMUM_CLOCK_ADVANCE_MILLIS = 500L
        private const val STALL_OBSERVATION_INTERVAL_NANOS = 1_000_000_000L
        private const val VIDEO_STALL_TIMEOUT_NANOS = 5_000_000_000L
        private const val PLAYER_STARTUP_GRACE_NANOS = 15_000_000_000L
    }
}
