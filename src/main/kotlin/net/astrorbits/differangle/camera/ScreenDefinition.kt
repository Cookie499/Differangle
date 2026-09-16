package net.astrorbits.differangle.camera

import org.joml.Matrix4f

/** A centered unit XY quad, facing local +Z. UV mapping is owned by the backend. */
data class ScreenDefinition(
    val id: String,
    val cameraId: String,
    val position: Position = Position(),
    val rotation: Rotation = Rotation(),
    val width: Float = 4f,
    val height: Float = 2.25f,
    val enabled: Boolean = true,
    /**
     * Pixels this screen asks for.
     *
     * Texture mode renders one target per camera, so there the scheduler picks a single resolution for the whole
     * group and every screen stretches that picture. Embedded draws each screen itself, so this screen's own
     * pixels shape its picture: the resolution's aspect is exactly the stretch shown inside this screen.
     */
    val resolution: Resolution = Resolution(),
) {
    init {
        require(id.isNotBlank() && cameraId.isNotBlank())
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
    }

    fun modelMatrix(origin: Position): Matrix4f = Matrix4f()
        .translation((position.x - origin.x).toFloat(), (position.y - origin.y).toFloat(), (position.z - origin.z).toFloat())
        .rotate(rotation.quaternion())
        .scale(width, height, 1f)

    fun isFrontFacing(observer: Position): Boolean {
        val normal = rotation.quaternion().transform(org.joml.Vector3d(0.0, 0.0, 1.0))
        return normal.x * (observer.x-position.x) + normal.y * (observer.y-position.y) + normal.z * (observer.z-position.z) > 1e-6
    }
}
