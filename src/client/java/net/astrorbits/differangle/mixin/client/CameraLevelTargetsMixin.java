package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.astrorbits.differangle.client.render.NativeCameraScope;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class CameraLevelTargetsMixin {
    @Inject(method = {"translucentTarget", "itemEntityTarget", "particlesTarget", "weatherTarget", "cloudsTarget", "entityOutlineTarget"},
        at = @At("HEAD"), cancellable = true)
    private void differangle$target(CallbackInfoReturnable<RenderTarget> cir) {
        if (NativeCameraScope.target != null) cir.setReturnValue(NativeCameraScope.target);
    }
}
