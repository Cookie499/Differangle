package net.astrorbits.differangle.world

import net.astrorbits.differangle.camera.*
import net.minecraft.network.syncher.*
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.*
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.joml.Quaternionf

/** Server entity pose is authoritative; client definitions are disposable projections. */
class CameraEntity(type: EntityType<out CameraEntity>, level: Level) : Entity(type, level) {
    init { noPhysics = true; setNoGravity(true); isInvisible = true }
    var enabled: Boolean get() = entityData.get(ENABLED); set(value) { entityData.set(ENABLED, value) }
    var fov: Float get() = entityData.get(FOV); set(value) { require(value.isFinite() && value in 1f..179f); entityData.set(FOV, value) }
    var nearPlane: Float get() = entityData.get(NEAR); set(value) { require(value.isFinite() && value > 0f && value < farPlane); entityData.set(NEAR, value) }
    var farPlane: Float get() = entityData.get(FAR); set(value) { require(value.isFinite() && value > nearPlane); entityData.set(FAR, value) }
    var fps: Int get() = entityData.get(FPS); set(value) { require(value in 1..240); entityData.set(FPS, value) }
    var viewRotation: Rotation
        get() = entityData.get(ROTATION).let { Rotation(it.x(), it.y(), it.z(), it.w()) }
        set(value) { entityData.set(ROTATION, value.quaternion()) }
    private var motion: Motion? = null

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(ENABLED, true).define(FOV, 70f).define(NEAR, 0.05f).define(FAR, 1024f).define(FPS, 15)
            .define(ROTATION, Quaternionf())
    }
    fun snapshot(resolution: Resolution = Resolution()) = CameraDefinition(uuid.toString(), Position(x, y, z), viewRotation, fov, nearPlane, farPlane, resolution, fps, enabled = enabled)
    fun pose(position: Position, rotation: Rotation, ticks: Int = 0, easing: String = "linear") {
        check(!level().isClientSide)
        require(ticks in 0..72000 && easing in listOf("step", "linear", "smoothstep"))
        motion = if (ticks == 0) null else Motion(Position(x,y,z), viewRotation, position, rotation, ticks, 0, easing)
        if (ticks == 0) { setPos(position.x, position.y, position.z); viewRotation = rotation }
    }
    override fun tick() {
        super.tick()
        if (level().isClientSide) return
        motion?.let { m ->
            val elapsed = m.elapsed + 1
            val linear = (elapsed.toFloat() / m.ticks).coerceIn(0f,1f)
            val t = when (m.easing) { "step" -> if (linear < 1f) 0f else 1f; "smoothstep" -> linear * linear * (3f - 2f * linear); else -> linear }
            setPos(m.start.x + (m.end.x-m.start.x)*t, m.start.y+(m.end.y-m.start.y)*t, m.start.z+(m.end.z-m.start.z)*t)
            val q = m.from.quaternion().slerp(m.to.quaternion(),t).normalize()
            viewRotation = Rotation(q.x,q.y,q.z,q.w)
            motion = if (elapsed >= m.ticks) null else m.copy(elapsed = elapsed)
        }
    }
    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float) = false
    override fun isPickable() = false
    override fun isPushable() = false
    override fun readAdditionalSaveData(input: ValueInput) {
        enabled = input.getBooleanOr("enabled", true)
        fov = input.getFloatOr("fov",70f).takeIf { it.isFinite() && it in 1f..179f } ?: 70f
        fps = input.getIntOr("update_rate",15).coerceIn(1,240)
        val near = input.getFloatOr("near_plane",0.05f)
        val far = input.getFloatOr("far_plane",1024f)
        if (near.isFinite() && far.isFinite() && near > 0f && far > near) { entityData.set(NEAR,near); entityData.set(FAR,far) }
        viewRotation = readRotation(input, "rotation")
        motion = runCatching {
            val duration = input.getIntOr("motion_ticks",0)
            if (duration !in 1..72000) null else Motion(readPosition(input,"start"),readRotation(input,"from"),readPosition(input,"end"),readRotation(input,"to"),duration,
                input.getIntOr("path_time",0).coerceIn(0,duration), input.getStringOr("easing","linear"))
        }.getOrNull()
    }
    override fun addAdditionalSaveData(out: ValueOutput) {
        out.putBoolean("enabled",enabled); out.putFloat("fov",fov); out.putFloat("near_plane",nearPlane); out.putFloat("far_plane",farPlane); out.putInt("update_rate",fps)
        writeRotation(out,"rotation",viewRotation)
        motion?.let { m ->
            out.putInt("motion_ticks",m.ticks); out.putInt("path_time",m.elapsed); out.putString("easing",m.easing)
            writePosition(out,"start",m.start); writePosition(out,"end",m.end)
            writeRotation(out,"from",m.from); writeRotation(out,"to",m.to)
        }
    }
    private data class Motion(val start: Position, val from: Rotation, val end: Position, val to: Rotation, val ticks: Int, val elapsed: Int, val easing: String)
    companion object {
        private val ENABLED = SynchedEntityData.defineId(CameraEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val FOV = SynchedEntityData.defineId(CameraEntity::class.java, EntityDataSerializers.FLOAT)
        private val NEAR = SynchedEntityData.defineId(CameraEntity::class.java, EntityDataSerializers.FLOAT)
        private val FAR = SynchedEntityData.defineId(CameraEntity::class.java, EntityDataSerializers.FLOAT)
        private val FPS = SynchedEntityData.defineId(CameraEntity::class.java, EntityDataSerializers.INT)
        private val ROTATION = SynchedEntityData.defineId(CameraEntity::class.java, EntityDataSerializers.QUATERNION)
        private fun readRotation(i: ValueInput,p: String) = runCatching { Rotation(i.getFloatOr(p+"_x",0f),i.getFloatOr(p+"_y",0f),i.getFloatOr(p+"_z",0f),i.getFloatOr(p+"_w",1f)) }.getOrDefault(Rotation())
        private fun writeRotation(o: ValueOutput,p: String,r: Rotation) { o.putFloat(p+"_x",r.x);o.putFloat(p+"_y",r.y);o.putFloat(p+"_z",r.z);o.putFloat(p+"_w",r.w) }
        private fun readPosition(i: ValueInput,p: String) = Position(i.getDoubleOr(p+"_x",0.0),i.getDoubleOr(p+"_y",0.0),i.getDoubleOr(p+"_z",0.0))
        private fun writePosition(o: ValueOutput,p: String,v: Position) { o.putDouble(p+"_x",v.x);o.putDouble(p+"_y",v.y);o.putDouble(p+"_z",v.z) }
    }
}
