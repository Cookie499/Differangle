package net.astrorbits.differangle.test

import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.DifferangleClient
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import javax.imageio.ImageIO

/** Pixel regression: draw-call counters alone cannot catch inverted depth rejecting glass and water. */
class CameraTransparencyGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            for (command in listOf("tp @a 0 -60 0 0 0", "weather clear", "time set noon",
                "gamerule minecraft:advance_time false", "fill -6 -61 18 6 -61 30 minecraft:quartz_block")) {
                world.server.runCommand(command)
            }
            context.waitTicks(80)
            context.runOnClient<RuntimeException> { client ->
                val runtime = DifferangleClient.runtime
                runtime.syncWorld(client)
                CameraLayer.entries.forEach { runtime.setLayer(it, false) }
                runtime.system.putCamera(CameraDefinition("transparency", Position(0.5, -57.5, 19.0), Rotation.minecraftDegrees(0f, 15f), updateRate = 60))
                runtime.system.putScreen(ScreenDefinition("transparency", "transparency", Position(0.0, -58.38, 4.0), Rotation.minecraftDegrees(0f, 0f)))
            }
            for (material in listOf("glass", "water")) {
                world.server.runCommand("fill -3 -60 23 3 -58 25 minecraft:air")
                world.server.runCommand(if (material == "glass") "fill -2 -60 24 2 -58 24 minecraft:blue_stained_glass"
                    else "fill -2 -60 23 2 -60 25 minecraft:water")
                context.waitTicks(80)
                for (mode in CameraMode.entries) {
                    context.runOnClient<RuntimeException> {
                        DifferangleClient.runtime.switchMode(mode)
                        DifferangleClient.runtime.setLayer(CameraLayer.TRANSLUCENT, false)
                    }
                    awaitDraw(context)
                    val off = ImageIO.read(context.takeScreenshot("$material-${mode.commandName}-off").toFile())
                    context.runOnClient<RuntimeException> { DifferangleClient.runtime.setLayer(CameraLayer.TRANSLUCENT, true) }
                    awaitDraw(context)
                    val on = ImageIO.read(context.takeScreenshot("$material-${mode.commandName}-on").toFile())
                    var changed = 0
                    // Only inspect the physical screen; moving hands, HUD and main-world water don't count.
                    for (y in on.height * 30 / 100 until on.height * 70 / 100) {
                        for (x in on.width * 35 / 100 until on.width * 73 / 100) {
                            val a = off.getRGB(x, y); val b = on.getRGB(x, y)
                            val delta = listOf(0, 8, 16).sumOf { kotlin.math.abs(((a ushr it) and 255) - ((b ushr it) and 255)) }
                            if (delta > 30) changed++
                        }
                    }
                    check(changed > 200) { "$material ${mode.commandName}: enabling translucent terrain changed only $changed screen pixels" }
                    println("TRANSPARENCY PASS $material ${mode.commandName}: $changed pixels")
                }
            }
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
                val original = net.irisshaders.iris.api.v0.IrisApi.getInstance().config.areShadersEnabled()
                for (enabled in listOf(!original, original)) {
                    context.runOnClient<RuntimeException> {
                        net.irisshaders.iris.api.v0.IrisApi.getInstance().config.setShadersEnabledAndApply(enabled)
                    }
                    context.waitTicks(30)
                    awaitDraw(context)
                    context.takeScreenshot("iris-toggle-$enabled")
                }
            }
            context.runOnClient<RuntimeException> {
                DifferangleClient.runtime.clear()
                CameraLayer.entries.forEach { DifferangleClient.runtime.setLayer(it, true) }
                DifferangleClient.runtime.switchMode(CameraMode.TEXTURE)
            }
        }
    }
    private fun awaitDraw(context: ClientGameTestContext) {
        context.waitFor({
            val runtime = DifferangleClient.runtime
            check(runtime.lastError == null) { runtime.lastError!! }
            runtime.statistics.screenDraws > 0 && runtime.drawCalls > 0
        }, 400)
        context.waitTicks(5)
    }
}
