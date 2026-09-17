package net.astrorbits.differangle.client.world

import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.CameraRuntime
import net.astrorbits.differangle.media.MediaSourceType
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.world.*
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.entity.*
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import org.joml.Quaternionf
import org.joml.Vector3d

object WorldClient {
    private var world: ClientLevel? = null
    private var cameras = setOf<String>()
    private var screens = setOf<String>()
    var blocks = listOf<ScreenBlockEntity>(); private set
    var debugCameras = listOf<CameraEntity>(); private set
    fun initialize() {
        EntityRenderers.register(WorldResources.cameraType) { context -> object : EntityRenderer<CameraEntity,EntityRenderState>(context) {
            override fun createRenderState() = EntityRenderState()
            override fun shouldShowName(entity: CameraEntity,distanceToCameraSq: Double) = !entity.isInvisible
            override fun extractRenderState(entity: CameraEntity,state: EntityRenderState,partial: Float) {
                super.extractRenderState(entity,state,partial)
                if (!entity.isInvisible) state.nameTag = Component.literal("${entity.customName?.string ?: "Camera"} ${entity.uuid}")
            }
        } }
        UseBlockCallback.EVENT.register { player,level,hand,hit ->
            val entity = level.getBlockEntity(hit.blockPos) as? ScreenBlockEntity
            if (entity != null && level.isClientSide && !player.isShiftKeyDown) {
                Minecraft.getInstance().gui.setScreen(ScreenEditor(entity))
                InteractionResult.SUCCESS
            } else InteractionResult.PASS
        }
    }
    fun tick(client: Minecraft,runtime: CameraRuntime) {
        val level = client.level
        if (world !== level) { world=level; cameras=emptySet(); screens=emptySet() }
        blocks = ScreenBlockEntity.clientLoaded.filter { it.level === level && !it.isRemoved && level?.getBlockEntity(it.blockPos) === it }
        val entities = level?.entitiesForRendering()?.filterIsInstance<CameraEntity>() ?: emptyList()
        debugCameras = entities.filter { !it.isInvisible }
        val nextCameras = entities.map { it.uuid.toString() }.toSet()
        val nextScreens = blocks.map { it.screenUuid.toString() }.toSet()
        runtime.syncMedia(blocks.associate {
            val screen = definition(it)
            it.screenUuid.toString() to MediaRequest(it.config.media, it.config.resX, it.config.resY, it.config.fps,
                screen.position.x, screen.position.y, screen.position.z)
        })
        (screens-nextScreens).forEach(runtime.system::removeScreen)
        (cameras-nextCameras).forEach(runtime.system::removeCamera)
        for (entity in entities) {
            val users = blocks.filter {
                it.config.media.sourceType == MediaSourceType.CAMERA && it.config.cameraUuid == entity.uuid && it.config.enabled && !it.config.mirror
            }
            val largest = users.maxByOrNull { it.config.resX*it.config.resY }?.config
            val resolution = largest?.let { Resolution(it.resX,it.resY) } ?: Resolution()
            val fps = minOf(entity.fps,users.maxOfOrNull { it.config.fps } ?: entity.fps)
            runtime.system.putCamera(entity.snapshot(resolution).copy(updateRate=fps))
        }
        blocks.forEach { runtime.system.putScreen(definition(it)) }
        cameras=nextCameras; screens=nextScreens
    }
    fun baseRotation(entity: ScreenBlockEntity): Quaternionf = when(entity.blockState.getValue(ScreenBlock.FACING)) {
        Direction.SOUTH -> Quaternionf()
        Direction.NORTH -> Quaternionf().rotationY(Math.PI.toFloat())
        Direction.EAST -> Quaternionf().rotationY((Math.PI/2).toFloat())
        Direction.WEST -> Quaternionf().rotationY((-Math.PI/2).toFloat())
        Direction.UP -> Quaternionf().rotationX((-Math.PI/2).toFloat())
        Direction.DOWN -> Quaternionf().rotationX((Math.PI/2).toFloat())
    }
    fun anchor(entity: ScreenBlockEntity): Vector3d {
        val facing=entity.blockState.getValue(ScreenBlock.FACING)
        val pos=entity.blockPos
        return Vector3d(pos.x+0.5-facing.stepX*0.5,pos.y+0.5-facing.stepY*0.5,pos.z+0.5-facing.stepZ*0.5)
    }
    fun definition(entity: ScreenBlockEntity): ScreenDefinition {
        val c=entity.config
        val q=baseRotation(entity)
        val position=q.transform(Vector3d(c.offsetX,c.offsetY,c.offsetZ)).add(anchor(entity))
        val rad=(Math.PI/180).toFloat()
        q.rotateYXZ(c.yaw*rad,c.pitch*rad,c.roll*rad)
        return ScreenDefinition(entity.screenUuid.toString(),c.cameraUuid?.toString() ?: "unbound",Position(position.x,position.y,position.z),
            Rotation(q.x,q.y,q.z,q.w),c.width,c.height,c.enabled,Resolution(c.resX,c.resY),c.mirror,c.fps)
    }
}
