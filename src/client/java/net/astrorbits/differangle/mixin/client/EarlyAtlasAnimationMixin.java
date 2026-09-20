package net.astrorbits.differangle.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup crash guard for the global-settings uniform race on Minecraft 26.2.
 *
 * <p>On Fabric 26.2 the initial resource reload can finish before {@code GameRenderer.render} has ever
 * uploaded the global settings UBO. Any mod that keeps the render thread busy at client start for a few
 * seconds (Xaero's World Map does here) opens that window, and then the first {@code Minecraft.runTick}
 * reaches {@code TextureAtlas.tick} with a populated atlas but no Globals uniform, crashing with:
 *
 * <pre>java.lang.IllegalStateException: Missing uniform Globals (should be UNIFORM_BUFFER)
 *   at GlCommandEncoder.trySetup
 *   at SpriteContents$AnimationState.drawToAtlas</pre>
 *
 * <p>Nothing is on screen yet when the uniform is still absent, so skipping the upload loses no visible
 * animation: {@code uploadAnimationFrames} recomputes from the current tick to the target frame, so the
 * next tick (after the first real frame has rendered) catches straight up. Reported upstream as the same
 * failure seen with Distant Horizons + Fabric API alone; this guard can be dropped once the race is
 * fixed in the game or in a rendering mod.
 */
@Mixin(TextureAtlas.class)
abstract class EarlyAtlasAnimationMixin {
    @Inject(method = "uploadAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void differangle$skipAnimationBeforeFirstFrame(CallbackInfo ci) {
        if (RenderSystem.getGlobalSettingsUniform() == null) ci.cancel();
    }
}
