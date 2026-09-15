package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.systems.ScissorState
import net.minecraft.client.Camera
import org.joml.Matrix4f

/** Scoped redirects for vanilla feature/sky-effect output. No recursive level.render calls. */
class NativeCameraScope(output: RenderTarget, camera: Camera, projection: GpuBufferSlice,
                        fog: GpuBufferSlice, view: Matrix4f, private val drawContext: CameraDrawContext) : AutoCloseable {
    private val oldProjection = RenderSystem.getProjectionMatrixBuffer()
    private val oldProjectionType = RenderSystem.getProjectionType()
    private val oldFog = requireNotNull(RenderSystem.getShaderFog())
    private val oldLights = requireNotNull(RenderSystem.getShaderLights())
    private val oldGlobal = RenderSystem.getGlobalSettingsUniform()
    private val oldColor = RenderSystem.outputColorTextureOverride
    private val oldDepth = RenderSystem.outputDepthTextureOverride
    private val oldScissor = ScissorState(RenderSystem.getScissorStateForRenderTypeDraws())
    init {
        RenderSystem.assertOnRenderThread()
        check(target == null) { "Nested native camera rendering is forbidden" }
        target = output; observer = camera
        active = this
        RenderSystem.outputColorTextureOverride = output.colorTextureView
        RenderSystem.outputDepthTextureOverride = output.depthTextureView
        RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE)
        RenderSystem.setShaderFog(fog)
        RenderSystem.disableScissorForRenderTypeDraws()
        RenderSystem.getModelViewStack().pushMatrix().set(view)
    }
    override fun close() {
        RenderSystem.getModelViewStack().popMatrix()
        RenderSystem.setProjectionMatrix(oldProjection!!, oldProjectionType)
        RenderSystem.setShaderFog(oldFog)
        RenderSystem.setShaderLights(oldLights)
        RenderSystem.setGlobalSettingsUniform(oldGlobal!!)
        RenderSystem.outputColorTextureOverride = oldColor
        RenderSystem.outputDepthTextureOverride = oldDepth
        RenderSystem.getScissorStateForRenderTypeDraws().setFrom(oldScissor)
        observer = null; target = null; active = null
    }
    companion object {
        private var active: NativeCameraScope? = null
        @JvmField var target: RenderTarget? = null
        @JvmField var observer: Camera? = null
        fun mainProjection(): GpuBufferSlice = active?.oldProjection ?: requireNotNull(RenderSystem.getProjectionMatrixBuffer())
        @JvmStatic fun setPipeline(pass: RenderPass, pipeline: RenderPipeline) {
            val scope = active
            if (scope == null || !scope.drawContext.output.embedded) {
                pass.setPipeline(pipeline)
                return
            }
            pass.setPipeline(EmbeddedNativePipelines.variant(pipeline))
            pass.setUniform("CameraView", scope.drawContext.viewUniform)
            pass.setUniform("MainProjection", requireNotNull(scope.oldProjection))
            pass.bindTexture("ScreenVisibility", requireNotNull(scope.drawContext.output.screenVisibility),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
        }
    }
}
