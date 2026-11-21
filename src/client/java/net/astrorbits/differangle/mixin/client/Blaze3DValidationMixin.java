package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Iris 1.11's MixinRenderPipeline.iris$change2 replaces RenderPipeline.getVertexFormatBindings()
 * with a length-1 array while a shader pack is active and level rendering is in progress.
 * Vanilla's dev-only GlCommandEncoder.validateDraw then reads indices 0..15 from that array and
 * crashes with ArrayIndexOutOfBoundsException; production clients are unaffected because
 * GlRenderPass.VALIDATION is SharedConstants.IS_RUNNING_IN_IDE.
 *
 * This mixin pads short bindings arrays inside the validation loop only, so the development
 * client survives Iris's short array without changing any rendering behavior.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
abstract class Blaze3DValidationMixin {
    @Redirect(
        method = "validateDraw",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;getVertexFormatBindings()[Lcom/mojang/blaze3d/vertex/VertexFormat;"
        ),
        require = 0
    )
    private static VertexFormat[] differangle$padBindings(RenderPipeline pipeline) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        if (bindings.length >= 16) return bindings;
        return Arrays.copyOf(bindings, 16);
    }

    @Redirect(
        method = "trySetup",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlTextureView;isClosed()Z"
        ),
        require = 0
    )
    private boolean differangle$allowClosedSamplerTexture(GlTextureView view) {
        // Vanilla's dev-only trySetup rejects closed sampler texture views. Production
        // clients skip this validation entirely and simply bind whatever view is left, so
        // a stale GUI item atlas view crashes only development clients. Mirror production.
        return false;
    }
}
