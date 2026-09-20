package net.astrorbits.differangle.mixin.client;

import net.astrorbits.differangle.client.world.WorldClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the screen-base editor from the client's own right-click entry point.
 *
 * <p>`Minecraft.startUseItem` is what the client runs on a use-key press: vanilla picks the target from
 * {@link Minecraft#hitResult} and turns the click into `MultiPlayerGameMode.useItemOn` ->
 * `performUseItemOn` (which also runs the block's `useWithoutItem` locally) and finally the
 * `ServerboundUseItemOnPacket`. Hooking here keeps the feature independent of the Fabric
 * `UseBlockCallback` event, whose client wiring in Fabric API 0.160.0+26.2 is dead: it only targets
 * `MultiPlayerGameModeMixin.interactBlock`, a method 26.2 no longer has, so the event never fires on the
 * client and the editor could only ever be opened from the server side.
 *
 * <p>Opening the editor consumes the click, so the interaction never reaches the server through this
 * path. The base stays usable either way: the server-side `UseBlockCallback` only handles the binding
 * tool, and `ScreenBlock.useWithoutItem` returns SUCCESS without side effects.
 */
@Mixin(Minecraft.class)
abstract class InteractionScreenMixin {
    @Shadow public HitResult hitResult;
    @Shadow private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void differangle$openScreenEditor(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gui.screen() != null) return;
        if (!(hitResult instanceof BlockHitResult hit)) return;
        if (!WorldClient.openEditor(player, hit.getBlockPos())) return;
        // Cancelling skips the cooldown vanilla sets below this point; restore it so a held mouse button
        // cannot reopen the editor every tick.
        rightClickDelay = 4;
        ci.cancel();
    }
}
