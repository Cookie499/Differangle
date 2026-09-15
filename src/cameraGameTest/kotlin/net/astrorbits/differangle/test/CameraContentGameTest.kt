package net.astrorbits.differangle.test

import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.DifferangleClient
import net.astrorbits.differangle.client.render.NativeCameraScope
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.CloudStatus
import net.minecraft.core.particles.ParticleTypes

/** Runs in a generated test world, never in run/saves or a user's current world. */
class CameraContentGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        // This suite compares the two basic renderers; shader output has its own opt-in GPU suite.
        context.runOnClient<RuntimeException> { DifferangleClient.runtime.setCameraShaderRendering(false) }
        context.worldBuilder().create().use { world ->
            for (command in listOf(
                "tp @a 0 -60 0 0 0",
                "fill -6 -61 2 6 -61 14 minecraft:stone",
                "fill -3 -60 6 -1 -58 6 minecraft:blue_stained_glass",
                "setblock -2 -60 8 minecraft:water",
                "setblock 2 -60 7 minecraft:chest[facing=north]",
                "setblock -4 -60 8 minecraft:campfire",
                "summon minecraft:pig 0 -60 8 {NoAI:1b}",
                "weather rain"
            )) world.server.runCommand(command)
            context.waitTicks(80)
            context.runOnClient<RuntimeException> { client ->
                val runtime = DifferangleClient.runtime
                runtime.syncWorld(client)
                client.options.cloudStatus().set(CloudStatus.FANCY)
                client.options.cloudRange().set(16)
                runtime.system.putCamera(CameraDefinition("test", Position(0.5, -58.38, 2.0), Rotation.minecraftDegrees(0f, 0f), updateRate = 60))
                runtime.system.putScreen(ScreenDefinition("test", "test", Position(0.0, -58.38, 4.0), Rotation.minecraftDegrees(0f, 0f)))
                repeat(12) { client.particleEngine.createParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, -3.0, -58.0, 7.0, 0.0, 0.01, 0.0) }
            }
            awaitDraw(context)
            context.waitFor({
                val runtime = DifferangleClient.runtime
                check(runtime.lastError == null) { runtime.lastError!! }
                val counts = runtime.nativeFeatures
                counts.entities > 0 && counts.blockEntities > 0 && counts.particles > 0 && counts.weatherColumns > 0 && counts.cloudViews > 0
            }, 400)
            context.waitTicks(30)
            context.takeScreenshot("camera-all-layers-texture")
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                check(runtime.lastError == null) { runtime.lastError!! }
                check(NativeCameraScope.target == null && NativeCameraScope.observer == null)
                runtime.switchMode(CameraMode.EMBEDDED)
            }
            awaitDraw(context)
            context.waitFor({
                val runtime = DifferangleClient.runtime
                check(runtime.lastError == null) { runtime.lastError!! }
                val counts = runtime.nativeFeatures
                runtime.statistics.cachedCameraCount == 0 &&
                    counts.entities > 0 && counts.blockEntities > 0 && counts.particles > 0 && counts.weatherColumns > 0 && counts.cloudViews > 0
            }, 400)
            context.takeScreenshot("camera-all-layers-direct-embedded")
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                CameraLayer.entries.filter { it != CameraLayer.TRANSLUCENT }.forEach { runtime.setLayer(it, false) }
            }
            awaitDraw(context)
            context.takeScreenshot("camera-translucent-direct-embedded")
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                CameraLayer.entries.forEach { runtime.setLayer(it, true) }
                runtime.switchMode(CameraMode.TEXTURE)
                runtime.reloadResources()
            }
            awaitDraw(context)
            world.server.runCommand("weather clear")
            world.server.runCommand("time set noon")
            context.waitTicks(100)
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                runtime.system.putCamera(runtime.system.cameras().single().copy(position = Position(0.5, 175.0, 2.0), rotation = Rotation.minecraftDegrees(0f, -45f)))
                runtime.reloadResources()
            }
            context.waitFor({
                val runtime = DifferangleClient.runtime
                check(runtime.lastError == null) { runtime.lastError!! }
                runtime.statistics.screenDraws > 0 && runtime.nativeFeatures.cloudViews == 1
            }, 400)
            context.takeScreenshot("camera-clouds")
            context.runOnClient<RuntimeException> {
                DifferangleClient.runtime.switchMode(CameraMode.EMBEDDED)
            }
            awaitDraw(context)
            context.takeScreenshot("camera-clouds-direct-embedded")
            context.runOnClient<RuntimeException> { client ->
                client.options.cloudStatus().set(CloudStatus.FAST)
            }
            context.waitTicks(5)
            awaitDraw(context)
            context.takeScreenshot("camera-flat-clouds-direct-embedded")
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                runtime.system.putCamera(runtime.system.cameras().single().copy(position = Position(0.5, -58.38, 2.0), rotation = Rotation.minecraftDegrees(0f, 0f)))
                runtime.system.putScreen(runtime.system.screens().single().copy(rotation = Rotation.minecraftDegrees(20f, 15f, 20f)))
                runtime.reloadResources()
            }
            awaitDraw(context)
            context.takeScreenshot("camera-native-tilted-embedded")
            // In front of the physical screen, behind the remote observer: only main depth can hide the screen here.
            world.server.runCommand("setblock 0 -59 1 minecraft:oak_fence")
            context.waitTicks(15)
            awaitDraw(context)
            context.takeScreenshot("camera-native-main-occlusion")
            world.server.runCommand("setblock 0 -59 1 minecraft:air")
            context.runOnClient<RuntimeException> { client ->
                val runtime = DifferangleClient.runtime
                val definition = runtime.system.cameras().single().copy(position = Position(0.0, -58.38, 3.0), rotation = Rotation.minecraftDegrees(180f, 0f))
                val observer = net.astrorbits.differangle.client.render.VirtualCamera(definition, client.level!!)
                check(observer.entity() != null)
                check(observer.entity() !== client.player)
                check(observer.entity()!!.position() == observer.position())
                check(client.level!!.entitiesForRendering().none { it === observer.entity() })
                runtime.system.putCamera(definition)
                runtime.system.putScreen(runtime.system.screens().single().copy(rotation = Rotation.minecraftDegrees(0f, 0f)))
            }
            for (mode in CameraMode.entries) {
                context.runOnClient<RuntimeException> { DifferangleClient.runtime.switchMode(mode) }
                context.waitTicks(10)
                awaitDraw(context)
                context.runOnClient<RuntimeException> { client ->
                    check(client.gameRenderer.mainCamera().entity() === client.player)
                    check(client.player!!.position().distanceTo(net.minecraft.world.phys.Vec3(0.5, -60.0, 0.5)) < 0.01)
                }
                context.takeScreenshot("camera-player-${mode.commandName}")
            }
            context.runOnClient<RuntimeException> {
                check(DifferangleClient.runtime.lastError == null)
                check(NativeCameraScope.target == null)
                DifferangleClient.runtime.clear()
            }
        }
    }

    private fun awaitDraw(context: ClientGameTestContext) {
        context.waitFor({
            val runtime = DifferangleClient.runtime
            check(runtime.lastError == null) { runtime.lastError!! }
            runtime.statistics.screenDraws > 0
        }, 400)
    }
}
