package net.astrorbits.differangle.client.render.compat

import net.astrorbits.differangle.camera.CameraDefinition
import net.fabricmc.loader.api.FabricLoader

object RendererCompatibility {
    fun shadersEnabled() = FabricLoader.getInstance().isModLoaded("iris") && IrisCameraScope.active()
    fun projection(camera: CameraDefinition, zeroToOne: Boolean) =
        if (shadersEnabled()) camera.projectionMatrix(zeroToOne) else camera.renderProjectionMatrix(zeroToOne)
}
