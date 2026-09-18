package net.astrorbits.differangle.world

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

class ScreenBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(WorldResources.screenType, pos, state) {
    var screenUuid: UUID = UUID.randomUUID(); private set
    var config = ScreenConfig(); private set
    var revision = 0L; private set
    private var savedDimension = ""

    fun configure(value: ScreenConfig) {
        check(level?.isClientSide != true)
        if (config == value) return
        config = value
        changed()
    }

    fun renewIdentity() { screenUuid = UUID.randomUUID(); changed() }
    private fun changed() {
        revision++
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    fun tick() {
        val world = level ?: return
        if (world.isClientSide) clientLoaded.add(this)
        else {
            val dimension = world.dimension().identifier().toString()
            if (savedDimension.isNotEmpty() && savedDimension != dimension) renewIdentity()
            savedDimension = dimension
            // Old saves had only a fixed seek position. Anchor them once so all clients share a clock.
            if (config.media.sourceType.isMedia && config.media.positionGameTime < 0L) {
                configure(config.copy(media = config.media.anchored(world.gameTime)))
            }
        }
    }

    override fun setRemoved() { super.setRemoved(); clientLoaded.remove(this) }
    override fun saveAdditional(out: ValueOutput) {
        super.saveAdditional(out)
        out.putString("screen_uuid", screenUuid.toString())
        out.putLong("screen_anchor", blockPos.asLong())
        out.putString("screen_dimension", level?.dimension()?.identifier()?.toString() ?: savedDimension)
        out.putLong("revision", revision)
        config.save(out)
    }
    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        val stored = ScreenConfig.uuid(input.getStringOr("screen_uuid", ""))
        // Structure / clone copies retain all settings but cannot retain another position's identity.
        screenUuid = if (input.getLongOr("screen_anchor", blockPos.asLong()) == blockPos.asLong()) stored ?: UUID.randomUUID() else UUID.randomUUID()
        savedDimension = input.getStringOr("screen_dimension", "")
        revision = input.getLongOr("revision", 0L)
        config = ScreenConfig.load(input)
    }
    override fun getUpdatePacket() = ClientboundBlockEntityDataPacket.create(this)
    override fun getUpdateTag(registries: HolderLookup.Provider) = saveCustomOnly(registries)

    companion object {
        val clientLoaded: MutableSet<ScreenBlockEntity> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    }
}
