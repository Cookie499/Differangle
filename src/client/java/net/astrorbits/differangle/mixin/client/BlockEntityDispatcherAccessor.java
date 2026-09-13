package net.astrorbits.differangle.mixin.client;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(BlockEntityRenderDispatcher.class)
public interface BlockEntityDispatcherAccessor {
    @Accessor("cameraPos") Vec3 differangle$getCameraPos();
}
