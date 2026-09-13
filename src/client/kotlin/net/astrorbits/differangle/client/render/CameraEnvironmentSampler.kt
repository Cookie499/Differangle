package net.astrorbits.differangle.client.render

import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.camera.CameraFog
import net.astrorbits.differangle.camera.Position
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.util.ARGB
import net.minecraft.world.attribute.EnvironmentAttributeProbe
import net.minecraft.world.attribute.EnvironmentAttributes as Attributes
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.MoonPhase
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.dimension.DimensionType.Skybox
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.pow
import kotlin.math.sin

/** Snapshot sampled at the virtual camera, not the player's position/status effects. */
data class CameraEnvironment(
    val fog: CameraFog, val fogColor: Vector4f, val skyColor: Vector4f, val sunriseColor: Vector4f,
    val skybox: Skybox, val sunAngle: Float, val moonAngle: Float, val starAngle: Float,
    val moonPhase: MoonPhase, val starBrightness: Float, val rainBrightness: Float,
    val skyVisibility: Float, val belowHorizon: Boolean, val medium: String,
)

class CameraEnvironmentSampler {
    private class Entry(val position: Position) {
        val probe = EnvironmentAttributeProbe()
        var lastTick: Long? = null
    }
    private val entries = mutableMapOf<String, Entry>()

    fun clear() = entries.clear()

    /** Called once per client tick even when a camera is hidden or refreshes infrequently. */
    fun tick(level: ClientLevel, cameras: List<CameraDefinition>) {
        entries.keys.retainAll(cameras.map { it.id }.toSet())
        cameras.forEach { entry(level, it) }
    }

    private fun entry(level: ClientLevel, camera: CameraDefinition): Entry {
        val entry = entries[camera.id]?.takeIf { it.position == camera.position }
            ?: Entry(camera.position).also { entries[camera.id] = it }
        if (entry.lastTick != level.gameTime) {
            entry.probe.tick(level, Vec3(camera.position.x, camera.position.y, camera.position.z))
            entry.lastTick = level.gameTime
            // Warm all values each tick so interpolation is between game ticks, not camera frames.
            with(entry.probe) {
                getValue(Attributes.FOG_COLOR, 1f); getValue(Attributes.SKY_COLOR, 1f)
                getValue(Attributes.FOG_START_DISTANCE, 1f); getValue(Attributes.FOG_END_DISTANCE, 1f)
                getValue(Attributes.SKY_FOG_END_DISTANCE, 1f)
                getValue(Attributes.WATER_FOG_COLOR, 1f); getValue(Attributes.WATER_FOG_START_DISTANCE, 1f)
                getValue(Attributes.WATER_FOG_END_DISTANCE, 1f)
                getValue(Attributes.SUN_ANGLE, 1f); getValue(Attributes.MOON_ANGLE, 1f)
                getValue(Attributes.STAR_ANGLE, 1f); getValue(Attributes.STAR_BRIGHTNESS, 1f)
                getValue(Attributes.SUNRISE_SUNSET_COLOR, 1f); getValue(Attributes.MOON_PHASE, 1f)
            }
        }
        return entry
    }

    fun sample(level: ClientLevel, camera: CameraDefinition, partialTick: Float, loadedDistance: Float): CameraEnvironment {
        val probe = entry(level, camera).probe
        val partial = partialTick.coerceIn(0f, 1f)
        val blockPos = BlockPos.containing(camera.position.x, camera.position.y, camera.position.z)
        val fluid = level.getFluidState(blockPos)
        val submerged = camera.position.y < blockPos.y + fluid.getHeight(level, blockPos)
        val medium = when {
            submerged && fluid.`is`(FluidTags.WATER) -> "water"
            submerged && fluid.`is`(FluidTags.LAVA) -> "lava"
            level.getBlockState(blockPos).`is`(Blocks.POWDER_SNOW) -> "powder_snow"
            else -> "air"
        }
        val rain = level.getRainLevel(partial)
        val thunder = level.getThunderLevel(partial)
        var skyColor = probe.getValue(Attributes.SKY_COLOR, partial)
        skyColor = ARGB.scaleRGB(skyColor, 1f - rain * 0.5f, 1f - rain * 0.5f, 1f - rain * 0.4f)
        skyColor = ARGB.scaleRGB(skyColor, 1f - thunder * 0.5f)
        val sunAngle = Math.toRadians(probe.getValue(Attributes.SUN_ANGLE, partial).toDouble()).toFloat()
        val sunrise = probe.getValue(Attributes.SUNRISE_SUNSET_COLOR, partial)
        var fogColor = probe.getValue(Attributes.FOG_COLOR, partial)
        var start = probe.getValue(Attributes.FOG_START_DISTANCE, partial)
        var end = probe.getValue(Attributes.FOG_END_DISTANCE, partial)
        val skyEnd = probe.getValue(Attributes.SKY_FOG_END_DISTANCE, partial)

        when (medium) {
            "water" -> {
                fogColor = probe.getValue(Attributes.WATER_FOG_COLOR, partial)
                start = probe.getValue(Attributes.WATER_FOG_START_DISTANCE, partial)
                end = probe.getValue(Attributes.WATER_FOG_END_DISTANCE, partial)
            }
            "lava" -> { fogColor = 0xff991900.toInt(); start = 0.25f; end = 1f }
            "powder_snow" -> { fogColor = 0xff9fbbce.toInt(); start = 0f; end = 2f }
            else -> {
                val sunFacing = camera.rotation.quaternion().transform(Vector3f(0f, 0f, -1f)).x * if (sin(sunAngle) > 0f) -1f else 1f
                fogColor = ARGB.srgbLerp(sunFacing.coerceAtLeast(0f) * ARGB.alphaFloat(sunrise), fogColor, ARGB.opaque(sunrise))
                val skyMix = 1f - ((minOf(skyEnd, loadedDistance) / 512f).coerceIn(0.25f, 1f)).pow(0.25f)
                fogColor = ARGB.srgbLerp(skyMix, fogColor, skyColor)
                val skyLight = ((level.getBrightness(LightLayer.SKY, blockPos) - 8f) / 7f).coerceIn(0f, 1f)
                val precipitation = if (level.getBiome(blockPos).value().hasPrecipitation()) 1f else 0.5f
                val rainFog = rain * skyLight * precipitation
                start -= 160f * rainFog
                end = maxOf(minOf(96f, end), end - 256f * rainFog)
            }
        }
        // The void darkening belongs to this camera's height; never use the player's eye height.
        val voidRange = level.levelData.voidDarknessOnsetRange()
        if (voidRange > 0f && medium != "lava" && medium != "powder_snow") {
            val brightness = ((camera.position.y - level.minY) / voidRange).toFloat().coerceIn(0f, 1f)
            fogColor = ARGB.scaleRGB(fogColor, brightness * brightness)
        }
        return CameraEnvironment(
            CameraFog.forView(start, end, camera.farPlane, loadedDistance),
            ARGB.vector4fFromARGB32(ARGB.opaque(fogColor)), ARGB.vector4fFromARGB32(ARGB.opaque(skyColor)),
            ARGB.vector4fFromARGB32(sunrise),
            if (medium == "air") level.dimensionType().skybox() else Skybox.NONE,
            sunAngle, Math.toRadians(probe.getValue(Attributes.MOON_ANGLE, partial).toDouble()).toFloat(),
            Math.toRadians(probe.getValue(Attributes.STAR_ANGLE, partial).toDouble()).toFloat(),
            probe.getValue(Attributes.MOON_PHASE, partial), probe.getValue(Attributes.STAR_BRIGHTNESS, partial),
            1f - rain, (skyEnd / 128f).coerceIn(0f, 1f),
            camera.position.y < level.levelData.getHorizonHeight(level), medium,
        )
    }
}
