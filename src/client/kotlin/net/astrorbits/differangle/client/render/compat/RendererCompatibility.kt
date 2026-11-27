package net.astrorbits.differangle.client.render.compat

import net.astrorbits.differangle.camera.CameraDefinition
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.version.VersionPredicate

object RendererCompatibility {
    private val iris = FabricLoader.getInstance().getModContainer("iris").orElse(null)
    private val legacyIrisDepth = iris?.let { VersionPredicate.parse("<1.11.4").test(it.metadata.version) } ?: false

    fun shadersEnabled() = iris != null && IrisCameraScope.active()
    fun forwardDepth() = legacyIrisDepth && shadersEnabled()
    fun projection(camera: CameraDefinition, zeroToOne: Boolean) =
        if (forwardDepth()) camera.projectionMatrix(zeroToOne) else camera.renderProjectionMatrix(zeroToOne)
}
