package net.astrorbits.differangle.camera

import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CameraDepthTest {
    @Test fun `native and terrain depth both map near to one and far to zero`() {
        val camera = CameraDefinition("depth", nearPlane = 0.1f, farPlane = 128f)
        for (zeroToOne in listOf(false, true)) {
            val projection = camera.renderProjectionMatrix(zeroToOne)
            fun depth(distance: Float): Float {
                val ndc = projection.transformProject(Vector3f(0f, 0f, -distance)).z
                return if (zeroToOne) ndc else ndc * 0.5f + 0.5f
            }
            assertEquals(1f, depth(camera.nearPlane), 1e-5f)
            assertEquals(0f, depth(camera.farPlane), 1e-5f)
            assertTrue(depth(2f) > depth(20f))
        }
    }

    @Test fun `native content stage follows enabled layers independently of output mode`() {
        val layers = CameraLayers()
        assertTrue(layers.hasNativeContent)
        CameraLayer.entries.filter { it != CameraLayer.TRANSLUCENT }.forEach { layers.set(it, false) }
        assertFalse(layers.hasNativeContent)
        assertTrue(CameraLayer.TRANSLUCENT in layers)
        CameraLayer.entries.filter { it != CameraLayer.TRANSLUCENT }.forEach {
            layers.set(it, true); assertTrue(layers.hasNativeContent); layers.set(it, false)
        }
    }
}
