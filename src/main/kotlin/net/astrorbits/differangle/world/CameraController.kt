package net.astrorbits.differangle.world

import net.astrorbits.differangle.camera.*
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Public world mutation API. Commands and other integrations share these checks.
 *
 * Failure messages are translation keys: the command layer renders them through the client's language
 * file, so a rejected operation reports the same text as the help it belongs to.
 */
object CameraController {
    fun checkThread(level: ServerLevel) { check(level.server.isSameThread) { "differangle.error.thread" } }
    fun create(level: ServerLevel, name: String, position: Position, rotation: Rotation, invisible: Boolean = true): CameraEntity {
        checkThread(level)
        require(name.length in 1..64) { "differangle.error.name" }
        val entity = CameraEntity(WorldResources.cameraType,level)
        entity.customName = Component.literal(name)
        entity.isInvisible = invisible
        entity.pose(position, rotation)
        check(level.addFreshEntity(entity)) { "differangle.error.create" }
        return entity
    }
    fun find(level: ServerLevel, id: String): CameraEntity {
        checkThread(level)
        val uuid = ScreenConfig.uuid(id)
        if (uuid != null) return level.getEntity(uuid) as? CameraEntity ?: error("differangle.error.camera.unloaded")
        val matches = level.allEntities.filterIsInstance<CameraEntity>().filter { it.customName?.string == id }
        require(matches.size == 1) { "differangle.error.camera.name" }
        return matches.single()
    }
    fun screen(level: ServerLevel, pos: BlockPos): ScreenBlockEntity {
        checkThread(level)
        require(level.hasChunkAt(pos)) { "differangle.error.screen.chunk" }
        return level.getBlockEntity(pos) as? ScreenBlockEntity ?: error("differangle.error.screen.missing")
    }
    fun bind(level: ServerLevel, pos: BlockPos, camera: UUID?) {
        val screen = screen(level,pos)
        // A retained unresolved UUID is valid, including copied NBT and temporarily unloaded cameras.
        screen.configure(screen.config.copy(cameraUuid = camera))
    }
    fun configure(level: ServerLevel, pos: BlockPos, value: ScreenConfig) { screen(level,pos).configure(value) }
    fun setPose(level: ServerLevel, id: String, target: Position, rotation: Rotation, ticks: Int = 0, easing: String = "linear") {
        find(level,id).pose(target,rotation,ticks,easing)
    }
    fun setEnabled(level: ServerLevel, id: String, enabled: Boolean) { find(level,id).enabled = enabled }
    fun setInvisible(level: ServerLevel, id: String, invisible: Boolean) { find(level,id).isInvisible = invisible }
    fun remove(level: ServerLevel, id: String) { find(level,id).discard() }
}
