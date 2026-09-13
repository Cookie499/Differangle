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
) {
    init {
        require(id.isNotBlank() && cameraId.isNotBlank())
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
    }

    fun modelMatrix(origin: Position): Matrix4f = Matrix4f()
        .translation((position.x - origin.x).toFloat(), (position.y - origin.y).toFloat(), (position.z - origin.z).toFloat())
        .rotate(rotation.quaternion())
        .scale(width, height, 1f)
}
