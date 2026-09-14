package net.astrorbits.differangle.world

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.*
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.*

class ScreenBlock(properties: Properties) : BaseEntityBlock(properties) {
    init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH)) }
    override fun codec(): MapCodec<ScreenBlock> = CODEC
    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { builder.add(FACING) }
    override fun getStateForPlacement(context: BlockPlaceContext) = defaultBlockState().setValue(FACING, context.clickedFace)
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = ScreenBlockEntity(pos, state)
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        createTickerHelper(type, WorldResources.screenType, BlockEntityTicker<ScreenBlockEntity> { _, _, _, entity -> entity.tick() })
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = when (state.getValue(FACING)) {
        Direction.SOUTH -> box(5.0, 5.0, 0.0, 11.0, 11.0, 2.0)
        Direction.NORTH -> box(5.0, 5.0, 14.0, 11.0, 11.0, 16.0)
        Direction.EAST -> box(0.0, 5.0, 5.0, 2.0, 11.0, 11.0)
        Direction.WEST -> box(14.0, 5.0, 5.0, 16.0, 11.0, 11.0)
        Direction.UP -> box(5.0, 0.0, 5.0, 11.0, 2.0, 11.0)
        Direction.DOWN -> box(5.0, 14.0, 5.0, 11.0, 16.0, 11.0)
    }
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext) = Shapes.empty()
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult) = InteractionResult.SUCCESS
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, by: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, by, stack)
        if (!level.isClientSide) (level.getBlockEntity(pos) as? ScreenBlockEntity)?.renewIdentity()
    }
    private fun item(entity: ScreenBlockEntity?): ItemStack = ItemStack(WorldResources.screenItem).also { stack ->
        entity?.let { stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
            net.minecraft.world.item.component.TypedEntityData.of(it.type, it.saveWithoutMetadata(it.level!!.registryAccess()))) }
    }
    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean) =
        item(if (includeData) level.getBlockEntity(pos) as? ScreenBlockEntity else null)
    override fun getDrops(state: BlockState, params: LootParams.Builder): List<ItemStack> =
        listOf(item(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) as? ScreenBlockEntity))
    companion object {
        val FACING = BlockStateProperties.FACING
        val CODEC: MapCodec<ScreenBlock> = simpleCodec(::ScreenBlock)
    }
}
