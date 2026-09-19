package net.astrorbits.differangle.world

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB

object BindingTool {
    fun initialize() {
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            val stack = player.getItemInHand(hand)
            if (stack.item !== WorldResources.bindingTool) InteractionResult.PASS
            else {
                if (!level.isClientSide) {
                    if (player.isShiftKeyDown) clear(player, stack)
                    else if (!selectCamera(player, stack)) when (val target = level.getBlockEntity(hit.blockPos)) {
                        is SpeakerBlockEntity -> selectSpeaker(player, stack, target)
                        is ScreenBlockEntity -> push(player, stack, target)
                    }
                }
                InteractionResult.SUCCESS
            }
        }
        UseItemCallback.EVENT.register { player, level, hand ->
            val stack = player.getItemInHand(hand)
            if (stack.item !== WorldResources.bindingTool) InteractionResult.PASS
            else {
                if (!level.isClientSide) {
                    if (player.isShiftKeyDown) clear(player, stack) else selectCamera(player, stack)
                }
                InteractionResult.SUCCESS
            }
        }
    }
    private fun data(stack: ItemStack) = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
    private fun save(stack: ItemStack, tag: CompoundTag) { stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)) }
    private fun message(player: Player, key: String, vararg args: Any) = player.sendOverlayMessage(Component.translatable("differangle.binding.$key", *args))
    private fun clear(player: Player, stack: ItemStack) {
        stack.remove(DataComponents.CUSTOM_DATA)
        message(player, "cleared")
    }
    private fun selectCamera(player: Player, stack: ItemStack): Boolean {
        val start = player.eyePosition
        val end = start.add(player.lookAngle.scale(player.blockInteractionRange()))
        val obstruction = player.level().clip(ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        val limit = start.distanceToSqr(obstruction.location)
        val camera = player.level().getEntitiesOfClass(CameraEntity::class.java, AABB(start, end).inflate(0.3))
            .mapNotNull { camera -> camera.boundingBox.inflate(0.3).clip(start, end).orElse(null)?.let { camera to start.distanceToSqr(it) } }
            .filter { it.second <= limit }.minByOrNull { it.second }?.first ?: return false
        val tag = CompoundTag()
        tag.putString("camera", camera.uuid.toString())
        tag.putString("dimension", player.level().dimension().identifier().toString())
        save(stack, tag)
        message(player, "camera", camera.customName?.string ?: camera.uuid.toString())
        return true
    }
    private fun bindings(tag: CompoundTag): List<SpeakerBinding> = (0..1).mapNotNull { index ->
        ScreenConfig.uuid(tag.getStringOr("speaker_$index", ""))?.let { SpeakerBinding(it, BlockPos.of(tag.getLongOr("pos_$index", 0L))) }
    }.distinctBy { it.uuid }
    private fun selectSpeaker(player: Player, stack: ItemStack, speaker: SpeakerBlockEntity) {
        val old = data(stack)
        val dimension = player.level().dimension().identifier().toString()
        val selected = if (old.getStringOr("dimension", "") == dimension) bindings(old) else emptyList()
        if (selected.any { it.uuid == speaker.speakerUuid }) { message(player, "duplicate"); return }
        if (selected.size == 2) { message(player, "full"); return }
        val tag = CompoundTag()
        tag.putString("dimension", dimension)
        (selected + SpeakerBinding(speaker.speakerUuid, speaker.blockPos)).forEachIndexed { index, binding ->
            tag.putString("speaker_$index", binding.uuid.toString()); tag.putLong("pos_$index", binding.pos.asLong())
        }
        save(stack, tag)
        message(player, "speaker", selected.size + 1)
    }
    private fun push(player: Player, stack: ItemStack, screen: ScreenBlockEntity) {
        val tag = data(stack)
        val camera = ScreenConfig.uuid(tag.getStringOr("camera", ""))
        val speakers = bindings(tag)
        if (camera == null && speakers.isEmpty()) return
        if ((player as? ServerPlayer)?.permissions()?.hasPermission(Permissions.COMMANDS_GAMEMASTER) != true) {
            message(player, "permission"); return
        }
        if (tag.getStringOr("dimension", "") != player.level().dimension().identifier().toString()) {
            message(player, "dimension"); return
        }
        if (camera != null) screen.configure(screen.config.copy(cameraUuid = camera)) else screen.bindSpeakers(speakers)
        message(player, "applied")
    }
}
