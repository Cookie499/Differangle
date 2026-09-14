package net.astrorbits.differangle.world

import net.minecraft.core.Registry
import net.minecraft.core.registries.*
import net.minecraft.resources.*
import net.minecraft.world.entity.*
import net.minecraft.world.item.*
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.PushReaction

object WorldResources {
    private fun id(name: String) = Identifier.fromNamespaceAndPath("differangle", name)
    private val blockKey = ResourceKey.create(Registries.BLOCK, id("screen_base"))
    val screenBlock = Registry.register(BuiltInRegistries.BLOCK, blockKey, ScreenBlock(BlockBehaviour.Properties.of().setId(blockKey).strength(1.5f).noOcclusion().pushReaction(PushReaction.BLOCK)))
    val screenType: BlockEntityType<ScreenBlockEntity> = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("screen_base"), BlockEntityType(::ScreenBlockEntity, setOf(screenBlock)))
    val screenItem: Item = Registry.register(BuiltInRegistries.ITEM, id("screen_base"), BlockItem(screenBlock, Item.Properties().setId(ResourceKey.create(Registries.ITEM,id("screen_base")))) )
    private val cameraKey = ResourceKey.create(Registries.ENTITY_TYPE,id("camera"))
    val cameraType: EntityType<CameraEntity> = Registry.register(BuiltInRegistries.ENTITY_TYPE,cameraKey,
        EntityType.Builder.of(::CameraEntity,MobCategory.MISC).sized(0.01f,0.01f).clientTrackingRange(32).updateInterval(1).build(cameraKey))
    fun initialize() {
        WorldCommands.register()
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { _, player, _, state, _ ->
            state.block !== screenBlock || (player as? net.minecraft.server.level.ServerPlayer)?.permissions()
                ?.hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) == true
        }
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register { player,level,hand,_ ->
            if (!level.isClientSide && player.getItemInHand(hand).item === screenItem &&
                (player as? net.minecraft.server.level.ServerPlayer)?.permissions()?.hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) != true)
                net.minecraft.world.InteractionResult.FAIL else net.minecraft.world.InteractionResult.PASS
        }
    }
}
