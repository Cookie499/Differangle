package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.systems.RenderSystem
import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.camera.CameraLayers
import net.astrorbits.differangle.camera.CameraLayer
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
import net.astrorbits.differangle.client.render.compat.RendererCompatibility

/** Both output modes execute exactly the same ordered world-content stages. */
class CameraWorldRenderer(private val layers: CameraLayers, private val cameraShaders: Boolean = true) : AutoCloseable {
    private class ViewUniform(val camera: Matrix4f, val screen: Matrix4f, val zeroToOne: Boolean) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) {
            Std140Builder.intoBuffer(buffer).putMat4f(camera).putMat4f(screen)
                .putVec4(Vector4f(if (zeroToOne) 1f else 0f, if (RendererCompatibility.shadersEnabled()) 1f else 0f, 0f, 0f))
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
    val nativeFeatures = CameraNativeFeatures(layers)
    private val stages: List<CameraRenderStage> = listOf(CameraSkyRenderer(), terrain)
    private val views = DynamicUniformStorage<ViewUniform>("Differangle views", 144, 8)
    private val environments = DynamicUniformStorage<EnvironmentUniform>("Differangle environments", 128, 16)
    val compositor = CameraCompositor()
    private var shaderCameras: net.astrorbits.differangle.client.render.compat.IrisShaderCameras? = null
    private val config = net.astrorbits.differangle.client.ClientConfig()
    val terrainDraws get() = terrain.terrainDraws + (shaderCameras?.terrainDraws ?: 0)

    /** Mirrors keep the Iris backend only for packs whose screen-space passes tolerate the
     *  off-axis mirror frustum. The allowlist is config/mirrorShaderPacks, comma-separated prefixes. */
    private fun mirrorPackAllowed(): Boolean {
        val pack = net.irisshaders.iris.Iris.getCurrentPackName().lowercase()
        return config.loadMirrorShaderPacks().split(',')
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .any { pack.startsWith(it) }
    }

    fun beginFrame() { terrain.beginFrame(); nativeFeatures.beginFrame(); shaderCameras?.beginFrame() }

    fun prepare(camera: CameraDefinition, renderer: LevelRenderer, environment: CameraEnvironment,
                embedded: Boolean = false): PreparedCameraView {
        val atlas = Minecraft.getInstance().atlasManager.getAtlasOrThrow(AtlasIds.CELESTIALS)
        val sun = atlas.getSprite(Identifier.withDefaultNamespace("sun"))
        val moon = atlas.getSprite(Identifier.withDefaultNamespace("moon/${environment.moonPhase.serializedName}"))
        val mirrorShaders = !camera.mirrored || mirrorPackAllowed()
        val preparedTerrain = if (cameraShaders && !embedded && mirrorShaders && RendererCompatibility.shadersEnabled()) {
            shaderBackend().prepare(camera)
        } else terrain.prepare(camera, renderer, CameraLayer.TRANSLUCENT in layers)
        return PreparedCameraView(camera, preparedTerrain, environment,
            environments.writeUniform(EnvironmentUniform(environment, uv(sun), uv(moon))))
    }

    fun draw(view: PreparedCameraView, output: CameraRenderOutput) {
        val client = Minecraft.getInstance()
        val zeroToOne = RenderSystem.getDevice().deviceInfo.isZZeroToOne
        val viewBuffer = views.writeUniform(ViewUniform(
            RendererCompatibility.projection(view.camera, zeroToOne).mul(view.camera.viewMatrix()), output.screenModelView, zeroToOne))
        val context = CameraDrawContext(view, output, viewBuffer,
            client.atlasManager.getAtlasOrThrow(AtlasIds.BLOCKS).textureView,
            client.gameRenderer.levelLightmap(), client.atlasManager.getAtlasOrThrow(AtlasIds.CELESTIALS).textureView,
            client.textureManager.getTexture(Identifier.withDefaultNamespace("textures/environment/end_sky.png")).textureView)
        if (cameraShaders && !output.embedded && (!view.camera.mirrored || mirrorPackAllowed()) && RendererCompatibility.shadersEnabled()) {
            shaderBackend().draw(context, nativeFeatures, layers)
            return
        }
        stages.forEach { it.draw(context) }
        if (layers.hasNativeContent) {
            nativeFeatures.draw(context, { terrain.drawLayer(context, true) })
        } else {
            terrain.drawLayer(context, true)
        }
    }

    private fun shaderBackend() = shaderCameras
        ?: net.astrorbits.differangle.client.render.compat.IrisShaderCameras().also { shaderCameras = it }

    fun endFrame() {
        stages.forEach { it.endFrame() }
        nativeFeatures.endFrame()
        views.endFrame(); environments.endFrame(); compositor.endFrame()
        shaderCameras?.endFrame()
    }
    override fun close() {
        stages.forEach { it.close() }
        nativeFeatures.close()
        views.close(); environments.close(); compositor.close()
        shaderCameras?.close()
    }
    private fun uv(sprite: TextureAtlasSprite) = Vector4f(sprite.u0, sprite.v0, sprite.u1, sprite.v1)
}
