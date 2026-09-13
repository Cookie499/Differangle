package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import java.util.Optional
import java.util.OptionalDouble

class CameraSkyRenderer : CameraRenderStage {
    override fun draw(context: CameraDrawContext) {
        val output = context.output
        // Embedded sky reserves Screen surface depth in the main view. Texture sky leaves
        // camera depth at its clear value, so all terrain can draw in front of it.
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "Differangle sky ${context.view.camera.id}" }, output.color, Optional.empty(),
            output.mainDepth ?: output.cameraDepth, OptionalDouble.empty(),
        ).use { pass ->
            pass.setPipeline(if (output.embedded) CameraPipelines.embeddedSky else CameraPipelines.textureSky)
            pass.setUniform("CameraView", context.viewUniform)
            pass.setUniform("CameraEnvironment", context.view.environmentUniform)
            if (output.embedded) pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer()!!)
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            pass.bindTexture("Celestials", context.celestialAtlas, sampler)
            pass.bindTexture("EndSky", context.endSky, sampler)
            pass.draw(6, 1, 0, 0)
        }
    }
}
