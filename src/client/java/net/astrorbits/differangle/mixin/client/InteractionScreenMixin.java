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

@Mixin(Minecraft.class)
abstract class InteractionScreenMixin {
    @Shadow public HitResult hitResult;
    @Shadow private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void differangle$openScreenEditor(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gui.screen() != null) return;
        if (!WorldClient.useRemote(player)
                && !(hitResult instanceof BlockHitResult hit && WorldClient.openEditor(player, hit.getBlockPos()))) return;
        // Cancellation skips vanilla's use cooldown.
        rightClickDelay = 4;
        ci.cancel();
    }
}
