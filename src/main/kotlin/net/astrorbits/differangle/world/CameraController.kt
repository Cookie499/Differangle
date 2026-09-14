package net.astrorbits.differangle.world

import net.astrorbits.differangle.camera.*
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/** Public world mutation API. Commands and other integrations share these checks. */
object CameraController {
    fun checkThread(level: ServerLevel) { check(level.server.isSameThread) { "Camera mutations require the server thread" } }
    fun create(level: ServerLevel, name: String, position: Position, rotation: Rotation, invisible: Boolean = true): CameraEntity {
        checkThread(level)
        require(name.length in 1..64)
        val entity = CameraEntity(WorldResources.cameraType,level)
        entity.customName = Component.literal(name)
        entity.isInvisible = invisible
        entity.pose(position, rotation)
        check(level.addFreshEntity(entity)) { "Unable to create camera" }
        return entity
    }
    fun find(level: ServerLevel, id: String): CameraEntity {
        checkThread(level)
        val uuid = ScreenConfig.uuid(id)
        if (uuid != null) return level.getEntity(uuid) as? CameraEntity ?: error("摄像机未加载或不在当前维度")
        val matches = level.allEntities.filterIsInstance<CameraEntity>().filter { it.customName?.string == id }
        require(matches.size == 1) { "名称不存在或不唯一，请使用 UUID" }
        return matches.single()
    }
    fun screen(level: ServerLevel, pos: BlockPos): ScreenBlockEntity {
        checkThread(level)
        require(level.hasChunkAt(pos)) { "显示屏区块未加载" }
        return level.getBlockEntity(pos) as? ScreenBlockEntity ?: error("该位置不是显示屏基座")
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
