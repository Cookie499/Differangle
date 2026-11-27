package net.astrorbits.differangle.camera

import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.abs

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
    val mirror: Boolean = false,
    val updateRate: Int = 15,
) {
    init {
        require(id.isNotBlank() && cameraId.isNotBlank())
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
        require(updateRate in 1..240)
    }

    fun modelMatrix(origin: Position): Matrix4f = Matrix4f()
        .translation((position.x - origin.x).toFloat(), (position.y - origin.y).toFloat(), (position.z - origin.z).toFloat())
        .rotate(rotation.quaternion())
        .scale(width, height, 1f)

    fun fitAspect(contentAspect: Float): ScreenDefinition {
        val screenAspect = width / height
        return if (contentAspect >= screenAspect) copy(height = width / contentAspect)
        else copy(width = height * contentAspect)
    }

    fun isFrontFacing(observer: Position): Boolean {
        val normal = rotation.quaternion().transform(Vector3d(0.0, 0.0, 1.0))
        return normal.x * (observer.x-position.x) + normal.y * (observer.y-position.y) + normal.z * (observer.z-position.z) > 1e-6
    }

    /** Returns the distance in blocks along a unit ray hitting the front of the quad. */
    fun frontIntersectionDistance(origin: Position, direction: Vector3dc): Double? {
        val inverse = rotation.quaternion().conjugate()
        val start = inverse.transform(Vector3d(origin.x - position.x, origin.y - position.y, origin.z - position.z))
        val ray = inverse.transform(Vector3d(direction))
        if (start.z <= 1e-6 || ray.z >= -1e-6) return null
        val distance = -start.z / ray.z
        return distance.takeIf {
            abs(start.x + ray.x * it) <= width / 2 && abs(start.y + ray.y * it) <= height / 2
        }
    }
}
