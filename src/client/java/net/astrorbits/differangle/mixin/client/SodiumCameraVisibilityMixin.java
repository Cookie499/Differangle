package net.astrorbits.differangle.mixin.client;

import net.astrorbits.differangle.client.render.NativeCameraScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The caller still applies the virtual camera's vanilla distance and frustum tests. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class SodiumCameraVisibilityMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void differangle$cameraVisibility(CallbackInfoReturnable<Boolean> cir) {
        if (NativeCameraScope.observer != null) cir.setReturnValue(true);
    }
}
