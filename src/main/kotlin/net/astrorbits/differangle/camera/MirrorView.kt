package net.astrorbits.differangle.camera

import org.joml.Vector3d

/** Planar reflection with an off-axis frustum through the physical mirror corners. */
object MirrorView {
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
