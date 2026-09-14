package net.astrorbits.differangle.world

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
    }

    companion object {
        fun uuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()
        fun load(input: ValueInput): ScreenConfig {
            val binding = uuid(input.getStringOr("camera_uuid", ""))
            return runCatching { ScreenConfig(binding,
                input.getFloatOr("width", 2f), input.getFloatOr("height", 1.125f),
                input.getIntOr("resolution_width", 256), input.getIntOr("resolution_height", 144), input.getIntOr("update_rate", 15),
                input.getBooleanOr("enabled", true),
                input.getDoubleOr("offset_x", 0.0), input.getDoubleOr("offset_y", 0.0), input.getDoubleOr("offset_z", 0.5),
                input.getFloatOr("yaw", 0f), input.getFloatOr("pitch", 0f), input.getFloatOr("roll", 0f),
                input.getFloatOr("frame_depth", 0.0625f)) }.getOrElse { ScreenConfig(cameraUuid = binding) }
        }
    }
}
