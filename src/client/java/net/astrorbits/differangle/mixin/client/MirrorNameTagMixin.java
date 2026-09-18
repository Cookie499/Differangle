package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.astrorbits.differangle.client.render.NativeCameraScope;
import net.astrorbits.differangle.client.render.VirtualCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores the billboard's vertical axis after the reflected view changes handedness. */
@Mixin(SubmitNodeCollection.class)
abstract class MirrorNameTagMixin {
    @Inject(
        method = "submitNameTag",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V",
            shift = At.Shift.BEFORE
        )
    )
    private void differangle$orientMirrorNameTag(
        PoseStack poseStack,
        @Nullable Vec3 nameTagAttachment,
        int offset,
        Component name,
        boolean seeThrough,
        int lightCoords,
        CameraRenderState cameraState,
        CallbackInfo ci
    ) {
        Camera observer = NativeCameraScope.observer;
        if (observer instanceof VirtualCamera camera && camera.getDefinition().getMirrored()) {
            // Vanilla applies another negative Y scale immediately afterwards. Doubling it here
            // keeps the glyph plane facing the camera while cancelling the mirror-only inversion.
            poseStack.scale(1.0F, -1.0F, 1.0F);
        }
    }
}
