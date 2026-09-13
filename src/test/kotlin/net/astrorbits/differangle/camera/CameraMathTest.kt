package net.astrorbits.differangle.camera

import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CameraMathTest {
    private fun assertVector(expected: Vector3f, actual: Vector3f) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
        assertEquals(expected.z, actual.z, 0.0001f)
    }

    @Test fun `camera origin maps to zero and forward maps to negative Z`() {
        val camera = CameraDefinition("a", Position(10.0, 20.0, 30.0), Rotation.minecraftDegrees(90f, 30f, 20f))
        val view = camera.viewMatrix(Position())
        assertVector(Vector3f(), view.transformPosition(Vector3f(10f, 20f, 30f)))
        val forward = camera.rotation.quaternion().transform(Vector3f(0f, 0f, -1f))
        assertVector(Vector3f(0f, 0f, -1f), view.transformDirection(forward))
        assertVector(Vector3f(0f, 0f, 1f), Rotation.minecraftDegrees(0f, 0f).quaternion().transform(Vector3f(0f, 0f, -1f)))
    }

    @Test fun `projection uses independent FOV aspect and clipping planes`() {
        val camera = CameraDefinition("a", fov = 90f, nearPlane = 1f, farPlane = 10f, resolution = Resolution(200, 100))
        val projection = camera.projectionMatrix()
        assertEquals(1f, projection.m11(), 0.0001f)
        assertEquals(0.5f, projection.m00(), 0.0001f)
        assertEquals(-1f, projection.transformProject(Vector3f(0f, 0f, -1f)).z, 0.0001f)
        assertEquals(1f, projection.transformProject(Vector3f(0f, 0f, -10f)).z, 0.0001f)
        assertEquals(0f, camera.projectionMatrix(true).transformProject(Vector3f(0f, 0f, -1f)).z, 0.0001f)
        assertTrue(camera.copy(fov = 120f).projectionMatrix().m11() < projection.m11())
    }

    @Test fun `screen rotates around its center with independent width and height`() {
        val screen = ScreenDefinition("s", "a", Position(10.0, 20.0, 30.0), Rotation.minecraftDegrees(90f, 0f), 4f, 2f)
        val model = screen.modelMatrix(Position(10.0, 20.0, 30.0))
        assertVector(Vector3f(), model.transformPosition(Vector3f()))
        assertVector(Vector3f(0f, 1f, -2f), model.transformPosition(Vector3f(0.5f, 0.5f, 0f)))
    }

    @Test fun `relative origins retain sub-block precision far from world origin`() {
        val origin = Position(30_000_000.0, 100.0, 30_000_000.0)
        val position = Position(origin.x + 0.125, origin.y, origin.z)
        val camera = CameraDefinition("a", position)
        assertVector(Vector3f(-0.125f, 0f, 0f), camera.viewMatrix(origin).transformPosition(Vector3f()))
        assertVector(Vector3f(0.125f, 0f, 0f), ScreenDefinition("s", "a", position).modelMatrix(origin).transformPosition(Vector3f()))
    }

    @Test fun `invalid inputs fail before matrix construction or GPU allocation`() {
        listOf(0f, 180f, Float.NaN, Float.POSITIVE_INFINITY).forEach { fov ->
            assertThrows(IllegalArgumentException::class.java) { CameraDefinition("a", fov = fov) }
        }
        assertThrows(IllegalArgumentException::class.java) { CameraDefinition("a", nearPlane = 10f, farPlane = 1f) }
        assertThrows(IllegalArgumentException::class.java) { CameraDefinition("a", updateRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { Position(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Rotation(0f, 0f, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { Resolution(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { ScreenDefinition("s", "a", width = -1f) }
        val rotation = Rotation(w = 2f)
        rotation.quaternion().rotateY(1f)
        assertVector(Vector3f(0f, 0f, -1f), rotation.quaternion().transform(Vector3f(0f, 0f, -1f)))
    }
}
