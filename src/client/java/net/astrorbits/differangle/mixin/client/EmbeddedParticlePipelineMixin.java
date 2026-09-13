package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.astrorbits.differangle.client.render.NativeCameraScope;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(QuadParticleFeatureRenderer.class)
abstract class EmbeddedParticlePipelineMixin {
    @Redirect(method = "drawLayers", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private static void differangle$pipeline(RenderPass pass, RenderPipeline pipeline) {
        NativeCameraScope.setPipeline(pass, pipeline);
    }
}
