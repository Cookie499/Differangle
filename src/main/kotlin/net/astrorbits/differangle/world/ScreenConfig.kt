package net.astrorbits.differangle.world

import net.astrorbits.differangle.media.AudioAttenuation
import net.astrorbits.differangle.media.MediaConfig
import net.astrorbits.differangle.media.MediaSourceType
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/** Portable settings: identity deliberately belongs to the placed block, not this value. */
data class ScreenConfig(
    val cameraUuid: UUID? = null,
    val width: Float = 2f, val height: Float = 1.125f,
    val resX: Int = 256, val resY: Int = 144, val fps: Int = 15,
    val enabled: Boolean = true,
    val offsetX: Double = 0.0, val offsetY: Double = 0.0, val offsetZ: Double = 0.5,
    val yaw: Float = 0f, val pitch: Float = 0f, val roll: Float = 0f,
    val frameDepth: Float = 0.0625f,
    val mirror: Boolean = false,
    val media: MediaConfig = MediaConfig(),
) {
    init {
        require(width.isFinite() && width in 0.125f..64f && height.isFinite() && height in 0.125f..64f)
        require(resX in 16..2048 && resY in 16..2048 && fps in 1..240)
        require(listOf(offsetX, offsetY, offsetZ).all { it.isFinite() && it in -64.0..64.0 })
        require(listOf(yaw, pitch, roll).all { it.isFinite() })
        require(frameDepth.isFinite() && frameDepth in 0.01f..1f)
    }

    fun save(out: ValueOutput) {
        cameraUuid?.let { out.putString("camera_uuid", it.toString()) }
        out.putFloat("width", width); out.putFloat("height", height)
        out.putInt("resolution_width", resX); out.putInt("resolution_height", resY); out.putInt("update_rate", fps)
        out.putBoolean("enabled", enabled)
        out.putDouble("offset_x", offsetX); out.putDouble("offset_y", offsetY); out.putDouble("offset_z", offsetZ)
        out.putFloat("yaw", yaw); out.putFloat("pitch", pitch); out.putFloat("roll", roll)
        out.putFloat("frame_depth", frameDepth)
        out.putBoolean("mirror", mirror)
        out.putString("source_type", media.sourceType.serializedName)
        out.putString("source_url", media.sourceUrl)
        out.putBoolean("media_playing", media.playing)
        out.putBoolean("media_loop", media.loop)
        out.putDouble("media_position", media.positionSeconds)
        out.putBoolean("audio_enabled", media.audioEnabled)
        out.putFloat("audio_volume", media.volume)
        out.putString("audio_attenuation", media.attenuation.serializedName)
        out.putFloat("audio_distance", media.audibleDistance)
        out.putInt("media_max_height", media.maxVideoHeight)
        out.putLong("media_position_game_time", media.positionGameTime)
    }

    companion object {
        fun uuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()
        fun load(input: ValueInput): ScreenConfig {
            val binding = uuid(input.getStringOr("camera_uuid", ""))
            val media = runCatching { MediaConfig(
                MediaSourceType.parse(input.getStringOr("source_type", "camera")),
                input.getStringOr("source_url", ""),
                input.getBooleanOr("media_playing", true), input.getBooleanOr("media_loop", false),
                input.getDoubleOr("media_position", 0.0), input.getBooleanOr("audio_enabled", true),
                input.getFloatOr("audio_volume", 1f),
                AudioAttenuation.parse(input.getStringOr("audio_attenuation", "linear")),
                input.getFloatOr("audio_distance", 32f), input.getIntOr("media_max_height", 720),
                input.getLongOr("media_position_game_time", -1L),
            ) }.getOrDefault(MediaConfig())
            return runCatching { ScreenConfig(binding,
                input.getFloatOr("width", 2f), input.getFloatOr("height", 1.125f),
                input.getIntOr("resolution_width", 256), input.getIntOr("resolution_height", 144), input.getIntOr("update_rate", 15),
                input.getBooleanOr("enabled", true),
                input.getDoubleOr("offset_x", 0.0), input.getDoubleOr("offset_y", 0.0), input.getDoubleOr("offset_z", 0.5),
                input.getFloatOr("yaw", 0f), input.getFloatOr("pitch", 0f), input.getFloatOr("roll", 0f),
                input.getFloatOr("frame_depth", 0.0625f), input.getBooleanOr("mirror", false), media) }.getOrElse { ScreenConfig(cameraUuid = binding, media = media) }
        }
    }
}
