package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.systems.RenderSystem
import net.astrorbits.differangle.camera.CameraDefinition
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.DynamicUniformStorage
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier
import net.minecraft.world.level.dimension.DimensionType.Skybox
import org.joml.Matrix4f
import org.joml.Vector4f
import java.nio.ByteBuffer

/** Both output modes execute exactly the same ordered world-content stages. */
class CameraWorldRenderer : AutoCloseable {
    private class ViewUniform(val camera: Matrix4f, val screen: Matrix4f, val zeroToOne: Boolean) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) {
            Std140Builder.intoBuffer(buffer).putMat4f(camera).putMat4f(screen)
                .putVec4(Vector4f(if (zeroToOne) 1f else 0f, 0f, 0f, 0f))
        }
    }
    private class EnvironmentUniform(val e: CameraEnvironment, val sun: Vector4f, val moon: Vector4f) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) {
            val skybox = when (e.skybox) { Skybox.OVERWORLD -> 1f; Skybox.END -> 2f; else -> 0f }
            Std140Builder.intoBuffer(buffer).putVec4(e.fogColor)
                .putVec4(Vector4f(e.fog.environmentalStart, e.fog.environmentalEnd, e.fog.renderDistanceStart, e.fog.renderDistanceEnd))
                .putVec4(e.skyColor).putVec4(e.sunriseColor)
                .putVec4(Vector4f(e.sunAngle, e.moonAngle, e.starAngle, e.skyVisibility))
                .putVec4(Vector4f(skybox, e.starBrightness, e.rainBrightness, if (e.belowHorizon) 1f else 0f))
                .putVec4(sun).putVec4(moon)
        }
    }
    private val terrain = SharedTerrainRenderer()
    private val stages: List<CameraRenderStage> = listOf(CameraSkyRenderer(), terrain)
    private val views = DynamicUniformStorage<ViewUniform>("Differangle views", 144, 8)
    private val environments = DynamicUniformStorage<EnvironmentUniform>("Differangle environments", 128, 16)
    val compositor = CameraCompositor()
    val terrainDraws get() = terrain.terrainDraws

    fun beginFrame() = terrain.beginFrame()

    fun prepare(camera: CameraDefinition, renderer: LevelRenderer, environment: CameraEnvironment): PreparedCameraView {
        val atlas = Minecraft.getInstance().atlasManager.getAtlasOrThrow(AtlasIds.CELESTIALS)
        val sun = atlas.getSprite(Identifier.withDefaultNamespace("sun"))
        val moon = atlas.getSprite(Identifier.withDefaultNamespace("moon/${environment.moonPhase.serializedName}"))
        return PreparedCameraView(camera, terrain.prepare(camera, renderer), environment,
            environments.writeUniform(EnvironmentUniform(environment, uv(sun), uv(moon))))
    }

    fun draw(view: PreparedCameraView, output: CameraRenderOutput) {
        val client = Minecraft.getInstance()
        val zeroToOne = RenderSystem.getDevice().deviceInfo.isZZeroToOne
        val viewBuffer = views.writeUniform(ViewUniform(
            view.camera.projectionMatrix(zeroToOne).mul(view.camera.viewMatrix()), output.screenModelView, zeroToOne))
        val context = CameraDrawContext(view, output, viewBuffer,
            client.atlasManager.getAtlasOrThrow(AtlasIds.BLOCKS).textureView,
            client.gameRenderer.levelLightmap(), client.atlasManager.getAtlasOrThrow(AtlasIds.CELESTIALS).textureView,
            client.textureManager.getTexture(Identifier.withDefaultNamespace("textures/environment/end_sky.png")).textureView)
        stages.forEach { it.draw(context) }
    }

    fun endFrame() {
        stages.forEach { it.endFrame() }
        views.endFrame(); environments.endFrame(); compositor.endFrame()
    }
    override fun close() {
        stages.forEach { it.close() }
        views.close(); environments.close(); compositor.close()
    }
    private fun uv(sprite: TextureAtlasSprite) = Vector4f(sprite.u0, sprite.v0, sprite.u1, sprite.v1)
}
