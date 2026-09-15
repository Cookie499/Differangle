package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Camera

/** Available without Iris installed; used while initializing/finalizing a secondary pipeline. */
object ShaderCameraContext {
    @JvmField var target: RenderTarget? = null
    @JvmField var observer: Camera? = null
}
