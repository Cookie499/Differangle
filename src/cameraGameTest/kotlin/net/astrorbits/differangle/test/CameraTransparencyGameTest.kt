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
            world.server.runCommand("fill -6 -60 18 6 -58 30 minecraft:air")
            world.server.runCommand("summon minecraft:pig 0.5 -60 26 {NoAI:1b,NoGravity:1b,Invulnerable:1b}")
            world.server.runCommand("fill -2 -60 28 2 -58 28 minecraft:red_concrete")
            context.waitTicks(40)
            context.runOnClient<RuntimeException> {
                DifferangleClient.runtime.setLayer(CameraLayer.ENTITIES, true)
            }
            for ((name, z, yaw) in listOf(Triple("close", 0.8, 0f), Triple("side", 4.0, 75f), Triple("edge", 4.0, 85f))) {
                val images = mutableListOf<java.awt.image.BufferedImage>()
                context.runOnClient<RuntimeException> {
                    val runtime = DifferangleClient.runtime
                    runtime.system.putScreen(runtime.system.screens().single().copy(position = Position(0.5, -58.38, z), rotation = Rotation.minecraftDegrees(yaw, 0f)))
                    runtime.system.putCamera(runtime.system.cameras().single().copy(resolution = Resolution(1024, 576)))
                }
                for (mode in CameraMode.entries) {
                    context.runOnClient<RuntimeException> { DifferangleClient.runtime.switchMode(mode) }
                    awaitDraw(context)
                    repeat(8) {
                        context.waitTicks(1)
                        context.runOnClient<RuntimeException> {
                            val runtime = DifferangleClient.runtime
                            check(runtime.drawCalls == 0 || Regex("实体=(\\d+)").find(runtime.contentStatistics)!!.groupValues[1].toInt() > 0) {
                                "Remote entity disappeared at $name in $mode"
                            }
                        }
                    }
                    images += ImageIO.read(context.takeScreenshot("view-$name-${mode.commandName}").toFile())
                }
                val corners = context.computeOnClient<List<org.joml.Vector2f>, RuntimeException> { client ->
                    val state = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
                    val screen = DifferangleClient.runtime.system.screens().single()
                    val matrix = org.joml.Matrix4f(state.projectionMatrix).mul(state.viewRotationMatrix)
                        .mul(screen.modelMatrix(Position(state.pos.x, state.pos.y, state.pos.z)))
                    listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f).map { (x, y) ->
                        val p = matrix.transform(org.joml.Vector4f(x, y, 0f, 1f))
                        org.joml.Vector2f((p.x / p.w * 0.5f + 0.5f) * images[0].width, (0.5f - p.y / p.w * 0.5f) * images[0].height)
                    }
                }
                var sampled = 0; var different = 0
                for (y in images[0].height / 10 until images[0].height * 7 / 10) for (x in 0 until images[0].width) {
                    val signs = corners.indices.map { i ->
                        val a = corners[i]; val b = corners[(i + 1) % 4]
                        (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
                    }
                    if (!(signs.all { it > 10f } || signs.all { it < -10f })) continue
                    sampled++
                    val a = images[0].getRGB(x, y); val b = images[1].getRGB(x, y)
                    if (listOf(0, 8, 16).sumOf { kotlin.math.abs(((a ushr it) and 255) - ((b ushr it) and 255)) } > 80) different++
                }
                check(sampled > 200 && different < sampled * 0.15) { "$name: Texture / Embedded disagree at $different / $sampled screen pixels" }
                println("VIEW PASS $name: $different / $sampled different screen pixels")
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
