package net.astrorbits.differangle.test

import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.DifferangleClient
import net.astrorbits.differangle.world.*
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.core.BlockPos

class CameraMirrorGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            for (command in listOf("tp @a 0.5 -60 0.5 0 0", "time set noon", "weather clear",
                "fill -8 -61 -6 8 -61 12 quartz_block", "setblock 0 -59 5 differangle:screen_base[facing=north]",
                "fill -3 -60 -3 -3 -58 -3 red_concrete", "fill 3 -60 -3 3 -58 -3 blue_concrete",
                "summon minecraft:pig 2 -60 1 {NoAI:1b,NoGravity:1b}")) world.server.runCommand(command)
            world.server.runOnServer<RuntimeException> { server ->
                val entity = CameraController.screen(server.overworld(), BlockPos(0, -59, 5))
                // Mirror correctness is tied to the observer frame, even when the persisted screen FPS is low.
                CameraController.configure(server.overworld(), entity.blockPos, entity.config.copy(width = 4f, height = 3f, fps = 1))
            }
            world.server.runCommand("differangle screen mirror 0 -59 5 true")
            context.waitTicks(80)
            context.runOnClient<RuntimeException> { client ->
                DifferangleClient.runtime.switchMode(CameraMode.TEXTURE)
                DifferangleClient.runtime.setCameraShaderRendering(true)
                check((client.level!!.getBlockEntity(BlockPos(0,-59,5)) as ScreenBlockEntity).config.mirror)
            }
            await(context)
            repeat(3) {
                context.waitTicks(1)
                context.runOnClient<RuntimeException> {
                    check(DifferangleClient.runtime.statistics.cameraUpdates == 1) {
                        "Visible mirror was not refreshed with the main view"
                    }
                }
            }
            context.takeScreenshot("mirror-front-player")
            context.input.holdKey { it.keyUp }
            context.waitTicks(4)
            context.takeScreenshot("mirror-walking-bob")
            context.input.releaseKey { it.keyUp }
            context.waitTicks(1)
            world.server.runCommand("tp @a 1.5 -60 0.5 0 0")
            await(context)
            context.takeScreenshot("mirror-offset-player")
            world.server.runCommand("differangle screen transform 0 -59 5 0 0 0.5 15 0 12")
            await(context)
            context.takeScreenshot("mirror-rotated")
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.switchMode(CameraMode.EMBEDDED) }
            context.waitTicks(15)
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                check(runtime.statistics.screenDraws == 0 && runtime.system.screens().single().mirror)
            }
            context.takeScreenshot("mirror-embedded-disabled")
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.switchMode(CameraMode.TEXTURE) }
            await(context)
            world.server.runCommand("clone 0 -59 5 0 -59 5 -4 -59 5")
            context.waitTicks(15)
            world.server.runOnServer<RuntimeException> { server ->
                val entity = CameraController.screen(server.overworld(), BlockPos(-4,-59,5))
                check(entity.config.mirror && entity.config.cameraUuid == null)
            }
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.clear() }
            println("MIRROR PASS: unbound screen, observer motion, rotated plane, Texture-only, cloned NBT")
        }
    }
    private fun await(context: ClientGameTestContext) {
        context.waitFor({
            val runtime = DifferangleClient.runtime
            check(runtime.lastError == null) { runtime.lastError!! }
            runtime.statistics.screenDraws > 0 && runtime.nativeFeatures.entities > 0
        }, 500)
        context.waitTicks(20)
    }
}
