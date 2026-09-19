package net.astrorbits.differangle.world

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

class SpeakerBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<SpeakerBlock> = CODEC
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = SpeakerBlockEntity(pos, state)
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        createTickerHelper(type, WorldResources.speakerType, BlockEntityTicker<SpeakerBlockEntity> { _, _, _, entity -> entity.tick() })
    companion object { val CODEC: MapCodec<SpeakerBlock> = simpleCodec(::SpeakerBlock) }
}

class SpeakerBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(WorldResources.speakerType, pos, state) {
    var speakerUuid: UUID = UUID.randomUUID(); private set
    private var savedDimension = ""
    fun tick() {
        val world = level ?: return
        if (world.isClientSide) return
        val dimension = world.dimension().identifier().toString()
        if (savedDimension != dimension) {
            if (savedDimension.isNotEmpty()) speakerUuid = UUID.randomUUID()
            savedDimension = dimension
            setChanged()
            world.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }
    override fun saveAdditional(out: ValueOutput) {
        super.saveAdditional(out)
        out.putString("speaker_uuid", speakerUuid.toString())
        out.putLong("speaker_anchor", blockPos.asLong())
        out.putString("speaker_dimension", level?.dimension()?.identifier()?.toString() ?: savedDimension)
    }
    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        speakerUuid = if (input.getLongOr("speaker_anchor", blockPos.asLong()) == blockPos.asLong())
            ScreenConfig.uuid(input.getStringOr("speaker_uuid", "")) ?: UUID.randomUUID() else UUID.randomUUID()
        savedDimension = input.getStringOr("speaker_dimension", "")
    }
    override fun getUpdatePacket() = ClientboundBlockEntityDataPacket.create(this)
    override fun getUpdateTag(registries: HolderLookup.Provider) = saveCustomOnly(registries)
}

data class SpeakerBinding(val uuid: UUID, val pos: BlockPos)
