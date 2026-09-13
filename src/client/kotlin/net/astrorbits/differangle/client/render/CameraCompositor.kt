package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import net.astrorbits.differangle.camera.Position
import net.astrorbits.differangle.camera.ScreenDefinition
import net.minecraft.client.renderer.DynamicUniformStorage
import org.joml.Matrix4f
import org.joml.Vector4f
import java.nio.ByteBuffer
import java.util.Optional
import java.util.OptionalDouble

/** Output allocation/mapping only. World content is drawn by CameraRenderStages. */
class CameraCompositor : AutoCloseable {
    private class SurfaceUniform(val modelView: Matrix4f) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) { Std140Builder.intoBuffer(buffer).putMat4f(modelView) }
    }
    private val surfaces = DynamicUniformStorage<SurfaceUniform>("Differangle surfaces", 64, 16)
    private var embeddedDepth: GpuTexture? = null
    private var embeddedDepthView: GpuTextureView? = null
    private var depthWidth = 0
    private var depthHeight = 0

    fun texture(target: RenderTarget, clearColor: Vector4f): CameraRenderOutput {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            target.colorTexture!!, clearColor, target.depthTexture!!, 0.0)
        return CameraRenderOutput(target.colorTextureView!!, target.depthTextureView!!, target = target)
    }

    fun embedded(screen: ScreenDefinition, origin: Position, mainView: Matrix4f, target: RenderTarget): CameraRenderOutput {
        ensureDepth(target.width, target.height)
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(embeddedDepth!!, 0.0)
        return CameraRenderOutput(target.colorTextureView!!, embeddedDepthView!!, target.depthTextureView!!,
            Matrix4f(mainView).mul(screen.modelMatrix(origin)),
            BorrowedCameraTarget(target.colorTextureView!!, embeddedDepthView!!, target.width, target.height))
    }

    fun surface(screen: ScreenDefinition, origin: Position, mainView: Matrix4f, target: RenderTarget, color: GpuTextureView) {
        val uniform = surfaces.writeUniform(SurfaceUniform(Matrix4f(mainView).mul(screen.modelMatrix(origin))))
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "Differangle screen ${screen.id}" }, target.colorTextureView!!, Optional.empty(),
            target.depthTextureView!!, OptionalDouble.empty(),
        ).use { pass ->
            pass.setPipeline(CameraPipelines.texturedSurface)
            pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer()!!)
            pass.setUniform("Surface", uniform)
            pass.bindTexture("Sampler0", color, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
            pass.draw(6, 1, 0, 0)
        }
    }

    private fun ensureDepth(width: Int, height: Int) {
        if (embeddedDepth != null && width == depthWidth && height == depthHeight) return
        embeddedDepthView?.close(); embeddedDepth?.close()
        embeddedDepthView = null; embeddedDepth = null
        val device = RenderSystem.getDevice()
        val texture = device.createTexture("Differangle embedded depth",
            GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, width, height, 1, 1)
        try { embeddedDepthView = device.createTextureView(texture) }
        catch (failure: Exception) { texture.close(); throw failure }
        embeddedDepth = texture
        depthWidth = width; depthHeight = height
    }

    fun endFrame() = surfaces.endFrame()
    override fun close() {
        surfaces.close()
        embeddedDepthView?.close(); embeddedDepth?.close()
        embeddedDepthView = null; embeddedDepth = null
    }
}
