package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.astrorbits.differangle.client.render.NativeCameraScope;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({WeatherEffectRenderer.class, CloudRenderer.class})
abstract class EmbeddedEnvironmentPipelineMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void differangle$pipeline(RenderPass pass, RenderPipeline pipeline) {
        NativeCameraScope.setPipeline(pass, pipeline);
    }
}
