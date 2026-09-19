package net.astrorbits.differangle.test

import net.astrorbits.differangle.client.media.SpatialAudioOutput
import net.astrorbits.differangle.client.media.SpatialPcm
import net.astrorbits.differangle.media.AudioMix
import net.astrorbits.differangle.world.*
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.TypedEntityData
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioBindingGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.runOnClient<RuntimeException> {
            check(SpatialAudioOutput::class.java.isAssignableFrom(Class.forName("org.watermedia.api.media.engines.ALEngine")))
            var matrix = AudioMix(1f, 0f, 0f, 1f)
            val pcm = SpatialPcm { matrix }
            val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putShort(1234).putShort(-2345).flip()
            check(pcm.process(input).let { it.short == 1234.toShort() && it.short == (-2345).toShort() })
            check(input.position() == 0)
            matrix = AudioMix(0f, 1f, 1f, 0f)
            check(pcm.process(input).let { it.short == (-2345).toShort() && it.short == 1234.toShort() })
            val output = org.watermedia.api.media.MediaAPI.alEngine()
            try {
                (output as Any as SpatialAudioOutput).differangleSpatial { AudioMix.SILENT }
                check(output.supportedChannels().single().channels() == 2)
                check(output.supportedTypes().single() == org.watermedia.api.media.engines.SFXEngine.SampleType.S16)
                check(output.format(org.watermedia.api.media.engines.SFXEngine.SampleType.S16, 2, 48000))
                check(output.upload(input))
                check(org.lwjgl.openal.AL10.alGetError() == org.lwjgl.openal.AL10.AL_NO_ERROR)
            } finally { output.release() }
        }
        context.worldBuilder().create().use { world ->
            world.server.runCommand("tp @a 0 -60 0 0 0")
            world.server.runCommand("setblock 0 -59 3 differangle:screen_base")
            world.server.runCommand("setblock -1 -59 3 differangle:speaker")
            world.server.runCommand("setblock 1 -59 3 differangle:speaker")
            context.waitTicks(20)
            world.server.runOnServer<RuntimeException> { server ->
                val player = server.playerList.players.single()
                server.playerList.op(player.nameAndId())
                val level = server.overworld()
                val screen = level.getBlockEntity(BlockPos(0, -59, 3)) as ScreenBlockEntity
                val left = level.getBlockEntity(BlockPos(-1, -59, 3)) as SpeakerBlockEntity
                val right = level.getBlockEntity(BlockPos(1, -59, 3)) as SpeakerBlockEntity
                val stack = ItemStack(WorldResources.bindingTool)
                player.setItemInHand(InteractionHand.MAIN_HAND, stack)
                fun click(pos: BlockPos) { UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                    BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false)) }
                val revision = screen.revision
                click(screen.blockPos)
                check(screen.revision == revision)
                click(left.blockPos)
                click(screen.blockPos)
                check(screen.speakers.map { it.uuid } == listOf(left.speakerUuid))
                click(left.blockPos) // A duplicate must not occupy the second channel.
                click(right.blockPos)
                click(screen.blockPos)
                check(screen.speakers.map { it.uuid } == listOf(left.speakerUuid, right.speakerUuid))
                val restored = ScreenBlockEntity(screen.blockPos, screen.blockState)
                check(TypedEntityData.of(WorldResources.screenType, screen.saveWithoutMetadata(level.registryAccess())).loadInto(restored, level.registryAccess()))
                check(restored.speakers == screen.speakers)
                val cameraPos = player.eyePosition.add(player.lookAngle.scale(1.0))
                val camera = CameraController.create(level, "binding-test", net.astrorbits.differangle.camera.Position(cameraPos.x, cameraPos.y, cameraPos.z), net.astrorbits.differangle.camera.Rotation())
                UseItemCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND)
                val cameraTag = stack.get(DataComponents.CUSTOM_DATA)!!.copyTag()
                check(cameraTag.getStringOr("camera", "") == camera.uuid.toString())
                check(!cameraTag.contains("speaker_0"))
                camera.setPos(20.0, -59.0, 20.0)
                click(screen.blockPos)
                check(screen.config.cameraUuid == camera.uuid && screen.speakers.size == 2)
                click(left.blockPos)
                check(!stack.get(DataComponents.CUSTOM_DATA)!!.copyTag().contains("camera"))
                player.isShiftKeyDown = true
                UseItemCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND)
                player.isShiftKeyDown = false
                check(stack.get(DataComponents.CUSTOM_DATA) == null)
                val boundRevision = screen.revision
                click(screen.blockPos)
                check(screen.revision == boundRevision)
                println("AUDIO BINDING PASS: native audio upload, PCM routing, speakers, camera ray selection, persistence, clear and empty tool")
            }
            context.waitTicks(10)
            context.runOnClient<RuntimeException> { client ->
                check((client.level!!.getBlockEntity(BlockPos(0, -59, 3)) as ScreenBlockEntity).speakers.size == 2)
            }
        }
    }
}
