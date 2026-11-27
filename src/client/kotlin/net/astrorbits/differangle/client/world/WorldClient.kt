package net.astrorbits.differangle.client.world

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.systems.RenderSystem
import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.CameraRuntime
import net.astrorbits.differangle.media.MediaSourceType
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.world.*
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.entity.*
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import org.joml.Quaternionf
import org.joml.Vector3d
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.UUID

object WorldClient {
    private val LOGGER = LoggerFactory.getLogger("differangle")
    private const val REMOTE_RANGE = 64.0
    private var world: ClientLevel? = null
    private var cameras = setOf<String>()
    private var screens = setOf<String>()
    private lateinit var playbackKey: KeyMapping
    private var playbackKeyHeld = false
    private val durationReports = mutableMapOf<UUID, Long>()
    var blocks = listOf<ScreenBlockEntity>(); private set
    var debugCameras = listOf<CameraEntity>(); private set
    fun initialize() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("differangle", "controls"))
        playbackKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.differangle.remote_playback", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_4, category),
        )
        EntityRenderers.register(WorldResources.cameraType) { context -> object : EntityRenderer<CameraEntity,EntityRenderState>(context) {
            override fun createRenderState() = EntityRenderState()
            override fun shouldShowName(entity: CameraEntity,distanceToCameraSq: Double) = !entity.isInvisible
            override fun extractRenderState(entity: CameraEntity,state: EntityRenderState,partial: Float) {
                super.extractRenderState(entity,state,partial)
                if (!entity.isInvisible) state.nameTag = Component.literal("${entity.customName?.string ?: "Camera"} ${entity.uuid}")
            }
        } }
    }

    @JvmStatic
    fun openEditor(player: LocalPlayer, pos: BlockPos): Boolean {
        if (player.isShiftKeyDown || player.mainHandItem.item === WorldResources.bindingTool) return false
        val entity = player.level().getBlockEntity(pos) as? ScreenBlockEntity ?: return false
        return openEditor(entity)
    }

    @JvmStatic
    fun useRemote(player: LocalPlayer): Boolean {
        if (player.mainHandItem.item !== WorldResources.screenRemote) return false
        remoteTarget(player)?.let { openEditor(it) }
        return true
    }

    private fun tickRemotePlayback(client: Minecraft) {
        val clicked = playbackKey.consumeClick()
        while (playbackKey.consumeClick()) {}
        // A keyboard binding can enqueue repeat events while held.
        val pressed = clicked && !playbackKeyHeld
        playbackKeyHeld = playbackKey.isDown
        if (!pressed || client.gui.screen() != null) return
        val player = client.player ?: return
        if (player.mainHandItem.item !== WorldResources.screenRemote) return
        if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            player.sendOverlayMessage(Component.translatable("differangle.screen.remote.permission"))
            return
        }
        val target = remoteTarget(player) ?: return
        val pos = target.blockPos
        client.connection?.sendCommand("differangle screen playback-toggle ${pos.x} ${pos.y} ${pos.z} ${target.screenUuid}")
    }

    private fun remoteTarget(player: LocalPlayer): ScreenBlockEntity? {
        val level = player.level()
        val start = player.eyePosition
        val direction = player.lookAngle.normalize()
        val hit = level.clip(ClipContext(start, start.add(direction.scale(REMOTE_RANGE)),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        var distance = start.distanceTo(hit.location)
        var target = if (hit.type == HitResult.Type.BLOCK)
            level.getBlockEntity(hit.blockPos) as? ScreenBlockEntity else null
        val origin = Position(start.x, start.y, start.z)
        val ray = Vector3d(direction.x, direction.y, direction.z)
        for (entity in blocks) {
            if (entity.level !== level || entity.isRemoved) continue
            val intersection = definition(entity).frontIntersectionDistance(origin, ray) ?: continue
            if (intersection < distance) {
                distance = intersection
                target = entity
            }
        }
        if (target == null) player.sendOverlayMessage(Component.translatable("differangle.screen.remote.miss"))
        return target
    }

    private fun openEditor(entity: ScreenBlockEntity): Boolean {
        return runCatching { Minecraft.getInstance().gui.setScreen(ScreenEditor(entity)) }
            .onFailure { LOGGER.error("[screen] failed to open editor at {}", entity.blockPos, it) }
            .isSuccess
    }

    fun sendMediaControl(client: Minecraft, entity: ScreenBlockEntity, action: String) {
        if (client.level !== entity.level || entity.isRemoved) return
        val pos = entity.blockPos
        client.connection?.sendCommand("differangle screen media-control ${pos.x} ${pos.y} ${pos.z} ${entity.screenUuid} $action")
    }

    fun tick(client: Minecraft,runtime: CameraRuntime) {
        val level = client.level
        if (world !== level) { world=level; cameras=emptySet(); screens=emptySet(); durationReports.clear() }
        blocks = ScreenBlockEntity.clientLoaded.filter { it.level === level && !it.isRemoved && level?.getBlockEntity(it.blockPos) === it }
        tickRemotePlayback(client)
        val entities = level?.entitiesForRendering()?.filterIsInstance<CameraEntity>() ?: emptyList()
        debugCameras = entities.filter { !it.isInvisible }
        val nextCameras = entities.map { it.uuid.toString() }.toSet()
        val nextScreens = blocks.map { it.screenUuid.toString() }.toSet()
        runtime.syncMedia(blocks.associate {
            it.screenUuid.toString() to MediaRequest(it.config.media,
                it.blockPos.x + 0.5, it.blockPos.y + 0.5, it.blockPos.z + 0.5,
                it.speakers.map { binding ->
                    val speaker = level?.getBlockEntity(binding.pos) as? SpeakerBlockEntity
                    net.astrorbits.differangle.media.AudioEmitter(binding.pos.x + 0.5, binding.pos.y + 0.5, binding.pos.z + 0.5,
                        speaker?.speakerUuid == binding.uuid)
                })
        })
        if (client.player?.permissions()?.hasPermission(Permissions.COMMANDS_GAMEMASTER) == true) {
            val unmeasured = blocks.filter { !it.config.mirror && it.config.media.sourceType.isVideo && it.config.media.durationMillis == 0L }
            durationReports.keys.retainAll(unmeasured.map { it.screenUuid }.toSet())
            for (block in unmeasured) {
                if (durationReports[block.screenUuid] == block.revision) continue
                val duration = runtime.mediaSession(block.screenUuid.toString())?.playbackDurationMillis ?: 0L
                if (duration > 0L) {
                    durationReports[block.screenUuid] = block.revision
                    sendMediaControl(client, block, "duration ${block.revision} $duration")
                }
            }
        }
        (screens-nextScreens).forEach(runtime.system::removeScreen)
        (cameras-nextCameras).forEach(runtime.system::removeCamera)
        for (entity in entities) {
            val users = blocks.filter {
                it.config.media.sourceType == MediaSourceType.CAMERA && it.config.cameraUuid == entity.uuid && it.config.enabled && !it.config.mirror
            }
            val resolution = users.map { cameraResolution(it.config) }.maxByOrNull { it.pixels } ?: Resolution()
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
            Rotation(q.x,q.y,q.z,q.w),c.width,c.height,c.enabled,cameraResolution(c),c.mirror,c.fps)
    }

    private fun cameraResolution(config: ScreenConfig) = Resolution(config.resX, config.resY)
        .limitedTo(RenderSystem.getDevice().deviceInfo.limits().maxTextureSize(), ScreenConfig.MAX_PIXELS)
}
