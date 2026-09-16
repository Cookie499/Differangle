package net.astrorbits.differangle.camera

import org.joml.Matrix4f
import org.joml.Quaternionf

/** Immutable world coordinates. Subtract the render origin before converting to floats. */
data class Position(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

/** Local-to-world rotation; identity looks along -Z, with +Y up and +X right. */
data class Rotation(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {
    init {
        val lengthSquared = x * x + y * y + z * z + w * w
        require(lengthSquared.isFinite() && lengthSquared > 1e-12f) { "Invalid quaternion" }
    }

    fun quaternion(): Quaternionf = Quaternionf(x, y, z, w).normalize()

    companion object {
        /** Minecraft yaw: 0 faces +Z, 90 faces -X. Positive pitch looks down. */
        fun minecraftDegrees(yaw: Float, pitch: Float, roll: Float = 0f): Rotation {
            require(yaw.isFinite() && pitch.isFinite() && roll.isFinite())
            val radians = (Math.PI / 180.0).toFloat()
            val q = Quaternionf().rotationYXZ((180f - yaw) * radians, -pitch * radians, roll * radians)
            return Rotation(q.x, q.y, q.z, q.w)
        }
    }
}

data class Resolution(val width: Int = 256, val height: Int = 144) {
    init { require(width > 0 && height > 0) { "Resolution must be positive" } }
    val aspect: Float get() = width.toFloat() / height
}

data class CameraDefinition(
    val id: String,
    val position: Position = Position(),
    val rotation: Rotation = Rotation(),
    val fov: Float = 70f,
    val nearPlane: Float = 0.05f,
    val farPlane: Float = 1024f,
    val resolution: Resolution = Resolution(),
    val updateRate: Int = 15,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val offAxis: OffAxisProjection? = null,
) {
    init {
        require(id.isNotBlank())
        require(fov.isFinite() && fov > 0f && fov < 180f)
        require(nearPlane.isFinite() && farPlane.isFinite() && nearPlane > 0f && farPlane > nearPlane)
        require(updateRate in 1..1000) { "Update rate must be 1..1000 FPS" }
    }

    val intervalNanos: Long get() = 1_000_000_000L / updateRate

    /** Consumes vertices relative to [origin], which the drawing backend must also use. */
    fun viewMatrix(origin: Position = position): Matrix4f = Matrix4f()
        .rotate(rotation.quaternion().conjugate())
        .translate(-(position.x - origin.x).toFloat(), -(position.y - origin.y).toFloat(), -(position.z - origin.z).toFloat())

    /** Right-handed perspective. The backend selects its clip-space depth convention. */
    fun projectionMatrix(zZeroToOne: Boolean = false): Matrix4f = offAxis?.matrix(nearPlane, farPlane, zZeroToOne) ?: Matrix4f().perspective(
        Math.toRadians(fov.toDouble()).toFloat(), resolution.aspect, nearPlane, farPlane, zZeroToOne,
    )

    /** Vanilla feature pipelines use reversed Z: near=1, far=0 in the depth attachment. */
    fun renderProjectionMatrix(zZeroToOne: Boolean): Matrix4f = offAxis?.matrix(nearPlane, farPlane, zZeroToOne)?.also {
        // Reverse only depth; swapping near/far would also scale asymmetric frustum bounds.
        it.m22(if (zZeroToOne) -1f - it.m22() else -it.m22())
        it.m32(-it.m32())
    } ?: Matrix4f().perspective(
        Math.toRadians(fov.toDouble()).toFloat(), resolution.aspect, farPlane, nearPlane, zZeroToOne,
    )
}

data class OffAxisProjection(val left: Float, val right: Float, val bottom: Float, val top: Float) {
    init {
        require(listOf(left, right, bottom, top).all { it.isFinite() })
        require(right > left && top > bottom)
    }
    fun matrix(near: Float, far: Float, zeroToOne: Boolean) = Matrix4f().frustum(left, right, bottom, top, near, far, zeroToOne)
}
