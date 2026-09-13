package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.astrorbits.differangle.client.render.NativeCameraScope;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class CameraGameRendererMixin {
    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void differangle$target(CallbackInfoReturnable<RenderTarget> cir) {
        if (NativeCameraScope.target != null) cir.setReturnValue(NativeCameraScope.target);
    }
    @Inject(method = "mainCamera", at = @At("HEAD"), cancellable = true)
    private void differangle$camera(CallbackInfoReturnable<Camera> cir) {
        if (NativeCameraScope.observer != null) cir.setReturnValue(NativeCameraScope.observer);
    }
}
