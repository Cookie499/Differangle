package net.astrorbits.differangle.client.render;

import net.astrorbits.differangle.mixin.client.BlockEntityDispatcherAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Vanilla's crosshairPickEntity is nullable at runtime despite its non-null annotation. */
public final class CameraDispatcherScope implements AutoCloseable {
    private final EntityRenderDispatcher entities;
    private final BlockEntityRenderDispatcher blocks;
    private final Camera previousCamera;
    private final Entity previousPick;
    private final Vec3 previousBlockPosition;

    public CameraDispatcherScope(EntityRenderDispatcher entities, BlockEntityRenderDispatcher blocks, Camera camera) {
        this.entities = entities;
        this.blocks = blocks;
        previousCamera = entities.camera;
        previousPick = entities.crosshairPickEntity;
        previousBlockPosition = ((BlockEntityDispatcherAccessor) blocks).differangle$getCameraPos();
        entities.prepare(camera, null);
        blocks.prepare(camera.position());
    }

    @Override public void close() {
        entities.camera = previousCamera;
        entities.crosshairPickEntity = previousPick;
        blocks.prepare(previousBlockPosition);
    }
}
