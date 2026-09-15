package net.astrorbits.differangle.client.render

import net.astrorbits.differangle.camera.CameraDefinition
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import org.joml.Matrix4f
import org.joml.Vector3f

/** Read-only virtual observer. Never moves or ticks the player's entity. */
class VirtualCamera(val definition: CameraDefinition, level: ClientLevel) : Camera() {
    private val orientation = definition.rotation.quaternion()
    private val view = definition.viewMatrix()
    private val frustum = Frustum(view, definition.projectionMatrix()).also {
        it.prepare(definition.position.x, definition.position.y, definition.position.z)
    }
    init {
        setLevel(level)
        setPosition(definition.position.x, definition.position.y, definition.position.z)
        // Iris queries Camera.attributeProbe directly for sun angle, sky and biome properties.
        // A detached camera never receives vanilla Camera.tick(), so initialize its probe here.
        attributeProbe().tick(level, position())
        val euler = orientation.getEulerAnglesYXZ(Vector3f())
        setRotation(180f - Math.toDegrees(euler.y.toDouble()).toFloat(), -Math.toDegrees(euler.x.toDouble()).toFloat())
        // Render extensions (e.g. 3D Skin Layers) use Camera.entity() for distance checks.
        // A detached, unregistered marker represents this observer without moving the player,
        // spawning a world entity, or confusing the remote position with the main camera.
        setEntity(Marker(EntityTypes.MARKER, level).also {
            it.snapTo(position(), yRot(), xRot())
            it.setOldPosAndRot()
            it.isInvisible = true
        })
    }
    override fun rotation() = orientation
    override fun getFov() = definition.fov
    override fun getCullFrustum() = frustum
    override fun isInitialized() = true
    override fun isDetached() = true
    override fun forwardVector() = orientation.transform(Vector3f(0f, 0f, -1f))
    override fun upVector() = orientation.transform(Vector3f(0f, 1f, 0f))
    override fun leftVector() = orientation.transform(Vector3f(-1f, 0f, 0f))
    override fun getViewRotationMatrix(dest: Matrix4f): Matrix4f = dest.set(view)
    override fun getViewRotationProjectionMatrix(dest: Matrix4f): Matrix4f = dest.set(definition.projectionMatrix()).mul(view)

    fun renderState(zeroToOne: Boolean) = CameraRenderState().also {
        it.initialized = true
        it.pos = position()
        it.blockPos = BlockPos.containing(it.pos)
        it.xRot = xRot(); it.yRot = yRot()
        it.orientation.set(orientation)
        it.viewRotationMatrix.set(view)
        it.projectionMatrix.set(net.astrorbits.differangle.client.render.compat.RendererCompatibility.projection(definition, zeroToOne))
        it.cullFrustum.set(frustum)
        it.depthFar = definition.farPlane
        it.hudFov = definition.fov
    }
}
