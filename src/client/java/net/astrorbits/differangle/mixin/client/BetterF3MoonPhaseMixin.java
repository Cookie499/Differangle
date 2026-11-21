package net.astrorbits.differangle.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * BetterF3 19.0.0 queries the positional moon_phase attribute without a position
 * (EnvironmentAttributeSystem.getDimensionValue). Minecraft 26.2 deliberately throws
 * for positional attributes when IS_RUNNING_IN_IDE, so the F3 overlay crashes every
 * development client with BetterF3 installed. Redirect that single call to the
 * positional API with the player's position, matching BetterF3's surrounding code
 * which already holds the player's block position.
 */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.modules.LocationModule", remap = false)
abstract class BetterF3MoonPhaseMixin {
    @Redirect(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;getDimensionValue(Lnet/minecraft/world/attribute/EnvironmentAttribute;)Ljava/lang/Object;"
        ),
        require = 0
    )
    private static Object differangle$positionalMoonPhase(EnvironmentAttributeSystem system, EnvironmentAttribute<?> attribute) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.player != null) {
            return system.getValue(attribute, client.player.position(), null);
        }
        return attribute.defaultValue();
    }
}
