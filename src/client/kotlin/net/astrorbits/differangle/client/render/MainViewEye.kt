package net.astrorbits.differangle.client.render

import net.astrorbits.differangle.camera.Position
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Converts vanilla's projection-space walk bob into the world-space eye used by planar reflection. */
object MainViewEye {
    fun resolve(camera: CameraRenderState, bobView: Boolean): Position {
        val entity = camera.entityRenderState
        if (!bobView || !entity.isPlayer || entity.bob == 0f) {
            return Position(camera.pos.x, camera.pos.y, camera.pos.z)
        }

        val phase = entity.backwardsInterpolatedWalkDistance * Math.PI
        val bob = entity.bob
        val wave = sin(phase).toFloat()
        val bounce = cos(phase).toFloat()
        val viewBob = Matrix4f()
            .translate(wave * bob * 0.5f, -abs(bounce * bob), 0f)
            .rotateZ(Math.toRadians((wave * bob * 3f).toDouble()).toFloat())
            .rotateX(Math.toRadians(abs(cos(phase - 0.2) * bob * 5.0)).toFloat())

        // Vanilla renders with P * bob * inverse(camera orientation). The inverse of that
        // view transform gives the effective eye in camera-local coordinates.
        val offset = viewBob.invert().transformPosition(Vector3f())
        camera.orientation.transform(offset)
        return Position(camera.pos.x + offset.x, camera.pos.y + offset.y, camera.pos.z + offset.z)
    }
}
