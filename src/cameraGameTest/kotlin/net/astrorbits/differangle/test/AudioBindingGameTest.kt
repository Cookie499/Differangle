package net.astrorbits.differangle.test

import net.astrorbits.differangle.client.media.SpatialAudioOutput
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET
import net.astrorbits.differangle.media.*
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
        context.runOnClient<RuntimeException> { verifyNativeAudio() }
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
    private fun verifyNativeAudio() {
        check(SpatialAudioOutput::class.java.isAssignableFrom(Class.forName("org.watermedia.api.media.engines.ALEngine")))
        val output = org.watermedia.api.media.MediaAPI.alEngine(2)
        val spatial = output as Any as SpatialAudioOutput
        val request = MediaRequest(MediaConfig(audibleDistance = 32f), 256, 144, 15,
            speakers = listOf(AudioEmitter(-3.0, 1.0, 2.0), AudioEmitter(3.0, 1.0, 2.0)))
        val silence = ByteBuffer.allocateDirect(48000 * 4).order(ByteOrder.nativeOrder())
        var sources = intArrayOf()
        fun position(source: Int): List<Float> = FloatArray(3).also { alGetSourcefv(source, AL_POSITION, it) }.toList()
        fun queued() = sources.map { alGetSourcei(it, AL_BUFFERS_QUEUED) }
        try {
            spatial.differangleSpatial(request)
            sources = spatial.differangleSources()
            check(sources.size == 2 && sources[0] != sources[1] && sources[0] == output.source())
            check(output.supportedChannels().single().channels() == 2)
            check(output.supportedTypes().single() == org.watermedia.api.media.engines.SFXEngine.SampleType.S16)
            check(output.format(org.watermedia.api.media.engines.SFXEngine.SampleType.S16, 2, 48000))
            output.volume(0.8f)
            check(position(sources[0]) == listOf(-3f, 1f, 2f))
            check(position(sources[1]) == listOf(3f, 1f, 2f))
            sources.forEach {
                check(alGetSourcei(it, AL_SOURCE_RELATIVE) == AL_FALSE)
                check(alGetSourcei(it, 0xD000) == 0xD003)
                check(alGetSourcef(it, AL_MAX_DISTANCE) == 32f)
                check(alGetSourcef(it, AL_REFERENCE_DISTANCE) == 0f)
                check(alGetSourcef(it, AL_GAIN) == 0.8f)
            }
            repeat(2) { check(output.upload(silence)) }
            check(queued() == listOf(2, 2))
            check(!output.upload(silence)) // A full queue must not enqueue only one channel.
            check(queued() == listOf(2, 2))
            output.buffers().forEach { check(alGetBufferi(it, AL_CHANNELS) == 1) }
            check(output.pendingMs() == 2000L) // Clock duration remains correct after stereo -> mono split.

            // Move and mute emitters with two seconds already buffered: changes must apply immediately.
            spatial.differangleSpatial(request.copy(speakers = listOf(AudioEmitter(8.0, 2.0, 4.0))))
            sources.forEach {
                check(position(it) == listOf(8f, 2f, 4f))
                check(alGetSourcef(it, AL_GAIN) == 0.4f)
            }
            check(queued() == listOf(2, 2))
            spatial.differangleSpatial(request.copy(config = request.config.copy(attenuation = AudioAttenuation.NONE),
                speakers = listOf(request.speakers[0], request.speakers[1].copy(available = false))))
            check(alGetSourcef(sources[1], AL_GAIN) == 0f)
            check(sources.all { alGetSourcei(it, 0xD000) == AL_NONE })
            output.speed(1.25f)
            check(sources.all { alGetSourcef(it, AL_PITCH) == 1.25f })
            output.play()
            check(sources.all { alGetSourcei(it, AL_SOURCE_STATE) == AL_PLAYING })
            output.pause()
            check(sources.all { alGetSourcei(it, AL_SOURCE_STATE) == AL_PAUSED })
            check(alGetSourcei(sources[0], AL_SAMPLE_OFFSET) == alGetSourcei(sources[1], AL_SAMPLE_OFFSET))
            output.play()
            output.flush()
            check(queued() == listOf(0, 0))
            check(output.pendingMs() == 0L)
            check(output.upload(silence))
            check(queued() == listOf(1, 1))
            output.play()
            check(sources.all { alGetSourcei(it, AL_SOURCE_STATE) == AL_PLAYING })
            check(alGetError() == AL_NO_ERROR)
        } finally { output.release() }
        check(sources.none { alIsSource(it) })
        output.release() // Closing and a late game-thread update are safe after decoder teardown.
        spatial.differangleSpatial(request)
        check(alGetError() == AL_NO_ERROR)
        println("NATIVE AUDIO PASS: paired mono queues, live position/gain, attenuation, clock, backpressure, pause/seek and release")
    }
}
