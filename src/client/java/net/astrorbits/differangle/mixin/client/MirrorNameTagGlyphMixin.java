package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.astrorbits.differangle.client.render.NativeCameraScope;
import net.astrorbits.differangle.client.render.ReverseQuadVertexConsumer;
import net.astrorbits.differangle.client.render.VirtualCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.font.TextRenderable;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps reflected NameTag glyphs front-facing after their vertical axis is corrected. */
@Mixin(targets = "net.minecraft.client.renderer.feature.NameTagFeatureRenderer$GlyphRenderer")
abstract class MirrorNameTagGlyphMixin {
    @Redirect(
        method = "acceptRenderable",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/font/TextRenderable;render(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V"
        )
    )
    private void differangle$renderMirrorGlyph(
        TextRenderable renderable, Matrix4fc pose, VertexConsumer buffer, int lightCoords, boolean flat
    ) {
        Camera observer = NativeCameraScope.observer;
        if (observer instanceof VirtualCamera camera && camera.getDefinition().getMirrored()) {
            ReverseQuadVertexConsumer reversed = new ReverseQuadVertexConsumer(buffer);
            renderable.render(pose, reversed, lightCoords, flat);
            reversed.flush();
        } else {
            renderable.render(pose, buffer, lightCoords, flat);
        }
    }
}
