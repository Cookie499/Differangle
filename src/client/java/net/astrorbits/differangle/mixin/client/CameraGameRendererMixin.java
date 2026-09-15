package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.astrorbits.differangle.client.render.NativeCameraScope;
import net.astrorbits.differangle.client.DifferangleClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class CameraGameRendererMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", shift = At.Shift.AFTER))
    private void differangle$afterLevel(CallbackInfo ci) {
        DifferangleClient.Companion.getRuntime().renderAfterLevel();
    }
    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void differangle$target(CallbackInfoReturnable<RenderTarget> cir) {
        if (NativeCameraScope.target != null) cir.setReturnValue(NativeCameraScope.target);
    }
    @Inject(method = "mainCamera", at = @At("HEAD"), cancellable = true)
    private void differangle$camera(CallbackInfoReturnable<Camera> cir) {
        if (NativeCameraScope.observer != null) cir.setReturnValue(NativeCameraScope.observer);
    }
}
