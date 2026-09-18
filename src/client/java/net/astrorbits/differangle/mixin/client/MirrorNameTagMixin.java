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

/** Name tags do not have a useful reflection transform, so mirrors omit them. */
@Mixin(SubmitNodeCollection.class)
abstract class MirrorNameTagMixin {
    @Inject(
        method = "submitNameTag",
        at = @At("HEAD"),
        cancellable = true
    )
    private void differangle$hideMirrorNameTag(
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
            ci.cancel();
        }
    }
}
