package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.pipeline.RenderTarget
import net.astrorbits.differangle.camera.CameraDefinition
import org.joml.Matrix4f

/** One camera snapshot shared by all output surfaces in this frame. */
data class PreparedCameraView(
    val camera: CameraDefinition,
    val terrain: SharedTerrainRenderer.PreparedTerrain,
    val environment: CameraEnvironment,
    val environmentUniform: GpuBufferSlice,
)

/** Only output mapping and depth ownership differ between Texture and Embedded. */
data class CameraRenderOutput(
    val color: GpuTextureView,
    val cameraDepth: GpuTextureView,
    val mainDepth: GpuTextureView? = null,
    val screenModelView: Matrix4f = Matrix4f(),
    val target: RenderTarget? = null,
) { val embedded get() = mainDepth != null }

data class CameraDrawContext(
    val view: PreparedCameraView,
    val output: CameraRenderOutput,
    val viewUniform: GpuBufferSlice,
    val blockAtlas: GpuTextureView,
    val lightmap: GpuTextureView,
    val celestialAtlas: GpuTextureView,
    val endSky: GpuTextureView,
)

/** Stages consume the same camera snapshot; they never invoke another world renderer. */
interface CameraRenderStage : AutoCloseable {
    fun draw(context: CameraDrawContext)
    fun endFrame() {}
    override fun close() {}
}
