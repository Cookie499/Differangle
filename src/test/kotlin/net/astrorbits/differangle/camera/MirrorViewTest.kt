package net.astrorbits.differangle.camera

import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MirrorViewTest {
    @Test
    fun `mirror corners match flipped texture corners from offset observers and rotated planes`() {
        for (rotation in listOf(Rotation(), Rotation.minecraftDegrees(37f, 25f, 18f))) {
            val screen = ScreenDefinition("mirror", "unbound", Position(1000.0, 70.0, -300.0), rotation,
                width = 4f, height = 3f, mirror = true)
            val eyeOffset = rotation.quaternion().transform(Vector3d(1.3, 0.7, 5.0))
            val eye = Position(screen.position.x + eyeOffset.x, screen.position.y + eyeOffset.y, screen.position.z + eyeOffset.z)
            val camera = requireNotNull(MirrorView.camera(screen, eye, 256f))
            val matrix = camera.projectionMatrix().mul(camera.viewMatrix()).mul(screen.modelMatrix(camera.position))
            for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) {
                val clip = matrix.transform(Vector4f(x, y, 0f, 1f))
                assertEquals(-2f * x, clip.x / clip.w, 0.0002f)
                assertEquals(2f * y, clip.y / clip.w, 0.0002f)
                assertEquals(-1f, clip.z / clip.w, 0.0002f)
            }
        }
    }

    @Test
    fun `mirror plane clips rear objects and supports forward and reverse depth`() {
        val screen = ScreenDefinition("mirror", "unbound", mirror = true)
        val camera = requireNotNull(MirrorView.camera(screen, Position(0.0, 0.0, 5.0), 256f))
        for (zeroToOne in listOf(false, true)) {
            for (reverse in listOf(false, true)) {
                val projection = if (reverse) camera.renderProjectionMatrix(zeroToOne) else camera.projectionMatrix(zeroToOne)
                fun depth(z: Float): Float {
                    val p = projection.transform(Vector4f(0f, 0f, -z, 1f))
                    return p.z / p.w
                }
                val near = if (reverse) 1f else if (zeroToOne) 0f else -1f
                val far = if (reverse) if (zeroToOne) 0f else -1f else 1f
                assertEquals(near, depth(camera.nearPlane), 0.00001f)
                assertEquals(far, depth(camera.farPlane), 0.00001f)
                assertTrue(if (reverse) depth(4f) > 1f else depth(4f) < near)
            }
        }
        assertNull(MirrorView.camera(screen, Position(0.0, 0.0, -1.0), 256f))
        assertNull(MirrorView.camera(screen, Position(0.0, 0.0, 0.001), 256f))
    }
}
