package net.astrorbits.differangle.camera

import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector2f
import org.joml.Vector3d
import org.joml.Vector4f
import kotlin.math.ceil

/** Planar reflection with an off-axis frustum through the physical mirror corners. */
object MirrorView {
    fun projectedResolution(
        screen: ScreenDefinition, eye: Position, viewProjection: Matrix4fc,
        viewport: Resolution, scale: Double, maxDimension: Int, maxPixels: Long,
    ): Resolution {
        val matrix = Matrix4f(viewProjection).mul(screen.modelMatrix(eye))
        fun project(x: Float, y: Float): Vector2f? {
            val clip = matrix.transform(Vector4f(x, y, 0f, 1f))
            if (clip.w <= 0.001f) return null
            return Vector2f(clip.x / clip.w * viewport.width / 2f, clip.y / clip.w * viewport.height / 2f)
        }
        val bottomLeft = project(-0.5f, -0.5f)
        val bottomRight = project(0.5f, -0.5f)
        val topLeft = project(-0.5f, 0.5f)
        val topRight = project(0.5f, 0.5f)
        val maximum = maxOf(viewport.width, viewport.height)
        fun bucket(pixels: Float): Int =
            (ceil(pixels.coerceAtMost(maximum.toFloat()) * scale / 32).toInt() * 32).coerceIn(16, maximum.coerceAtLeast(16))
        val estimate = if (bottomLeft == null || bottomRight == null || topLeft == null || topRight == null) {
            // A mirror crossing the eye plane can occupy most of the viewport.
            Resolution(bucket(viewport.width.toFloat()), bucket(viewport.height.toFloat()))
        } else Resolution(
            bucket(maxOf(bottomLeft.distance(bottomRight), topLeft.distance(topRight))),
            bucket(maxOf(bottomLeft.distance(topLeft), bottomRight.distance(topRight))),
        )
        return estimate.limitedTo(maxDimension, maxPixels)
    }

    fun camera(screen: ScreenDefinition, eye: Position, farPlane: Float): CameraDefinition? {
        val q = screen.rotation.quaternion()
        val normal = q.transform(Vector3d(0.0, 0.0, 1.0))
        val right = q.transform(Vector3d(1.0, 0.0, 0.0))
        val up = q.transform(Vector3d(0.0, 1.0, 0.0))
        val delta = Vector3d(eye.x - screen.position.x, eye.y - screen.position.y, eye.z - screen.position.z)
        val distance = delta.dot(normal)
        if (distance < 0.01 || distance >= farPlane - 1.0) return null
        val reflected = Vector3d(eye.x, eye.y, eye.z).sub(Vector3d(normal).mul(2.0 * distance))
        val center = Vector3d(screen.position.x, screen.position.y, screen.position.z).sub(reflected)
        val cx = -center.dot(right).toFloat()
        val cy = center.dot(up).toFloat()
        // A proper rotation keeps native backface culling intact. The compositor reverses U.
        val rotation = q.rotateY(Math.PI.toFloat())
        return CameraDefinition("mirror:${screen.id}", Position(reflected.x, reflected.y, reflected.z),
            Rotation(rotation.x, rotation.y, rotation.z, rotation.w), nearPlane = distance.toFloat(),
            farPlane = farPlane, resolution = screen.resolution, updateRate = screen.updateRate,
            offAxis = OffAxisProjection(cx - screen.width / 2, cx + screen.width / 2,
                cy - screen.height / 2, cy + screen.height / 2), mirrored = true)
    }
}
