package net.astrorbits.differangle.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Attachment facade only: never owns or frees the main color or camera depth textures. */
public final class BorrowedCameraTarget extends RenderTarget {
    public BorrowedCameraTarget(GpuTextureView color, GpuTextureView depth, int width, int height) {
        super("Differangle borrowed embedded target", true, color.texture().getFormat());
        this.colorTexture = color.texture(); this.colorTextureView = color;
        this.depthTexture = depth.texture(); this.depthTextureView = depth;
        this.width = width; this.height = height;
    }
    @Override public void destroyBuffers() { }
    @Override public void createBuffers(int width, int height) { throw new UnsupportedOperationException("Borrowed attachments"); }
    @Override public void resize(int width, int height) { throw new UnsupportedOperationException("Borrowed attachments"); }
}
