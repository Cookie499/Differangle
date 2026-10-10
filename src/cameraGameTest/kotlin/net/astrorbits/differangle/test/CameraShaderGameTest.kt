package net.astrorbits.differangle.test

import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.DifferangleClient
import net.astrorbits.differangle.client.render.NativeCameraScope
import net.astrorbits.differangle.client.render.ShaderCameraContext
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.irisshaders.iris.api.v0.IrisApi
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/** Opt-in GPU test: independent moving shader cameras, toggles, resized targets and resource reload. */
class CameraShaderGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        check(IrisApi.getInstance().isShaderPackInUse)
        context.worldBuilder().create().use { world ->
            for (command in listOf("tp @a 0.5 -60 0.5 0 0", "time set noon", "weather clear",
                "gamerule minecraft:advance_time false", "fill -8 -61 16 8 -61 34 quartz_block",
                "fill -3 -60 30 3 -57 30 red_concrete", "fill -3 -60 18 3 -57 18 blue_concrete",
                "fill -2 -60 25 -1 -58 25 blue_stained_glass", "fill 1 -60 25 2 -60 26 water",
                "summon minecraft:pig 0.5 -60 26 {NoAI:1b,NoGravity:1b}")) world.server.runCommand(command)
            context.waitTicks(100)
            context.runOnClient<RuntimeException> { client ->
                val runtime = DifferangleClient.runtime
                runtime.syncWorld(client)
                runtime.setCameraShaderRendering(true)
                runtime.switchMode(CameraMode.TEXTURE)
                runtime.system.putCamera(CameraDefinition("shader-a", Position(.5, -58.0, 21.0), Rotation.minecraftDegrees(0f, 8f), updateRate = 20))
                runtime.system.putCamera(CameraDefinition("shader-b", Position(.5, -58.0, 28.0), Rotation.minecraftDegrees(180f, 8f), updateRate = 20))
                runtime.system.putScreen(ScreenDefinition("shader-a", "shader-a", Position(-1.4, -58.38, 5.0), Rotation.minecraftDegrees(0f, 0f), width = 3.4f, height = 2.0f))
                runtime.system.putScreen(ScreenDefinition("shader-b", "shader-b", Position(2.4, -58.38, 5.0), Rotation.minecraftDegrees(0f, 0f), width = 3.4f, height = 2.0f))
            }
            await(context)
            val enabled = screenshot(context, "shader-two-cameras")
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.setCameraShaderRendering(false) }
            await(context)
            val basic = screenshot(context, "shader-basic-comparison")
            val changes = changed(enabled, basic)
            check(changes > 1000) { "Shader camera output did not differ from basic rendering: $changes" }
            println("SHADER PIXELS PASS: $changes changed screen pixels")
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.setCameraShaderRendering(true) }
            await(context)
            movingCameras(context)
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                runtime.system.putCamera(runtime.system.cameras().first { it.id == "shader-a" }.copy(resolution = Resolution(640, 360)))
            }
            await(context)
            screenshot(context, "shader-resized-camera")
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.reloadResources() }
            await(context)
            screenshot(context, "shader-resource-reload")
            for (enabled in listOf(false, true)) {
                context.runOnClient<RuntimeException> { IrisApi.getInstance().config.setShadersEnabledAndApply(enabled) }
                await(context)
                screenshot(context, "shader-iris-toggle-$enabled")
            }
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                check(NativeCameraScope.target == null && ShaderCameraContext.target == null)
                runtime.clear()
            }
            context.waitTicks(20)
            screenshot(context, "shader-main-after-clear")
            println("SHADER LIFECYCLE PASS: moving cameras, resize, resource reload, Iris off/on, clear")
        }
    }
    private fun movingCameras(context: ClientGameTestContext) {
        val initial = mutableMapOf<String, CameraFrame<net.astrorbits.differangle.client.render.TextureCameraTarget>>()
        val definitions = mutableListOf<CameraDefinition>()
        context.runOnClient<RuntimeException> {
            val system = DifferangleClient.runtime.system
            definitions.addAll(system.cameras())
            definitions.forEach { initial[it.id] = requireNotNull(system.frame(it.id)) }
        }
        repeat(40) { tick ->
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                for (camera in definitions) {
                    runtime.system.putCamera(camera.copy(
                        position = camera.position.copy(x = camera.position.x + (tick % 8 - 4) * 0.1),
                        rotation = Rotation.minecraftDegrees(if (camera.id == "shader-a") tick % 10 - 5f else 175f + tick % 10, 8f),
                    ))
                    check(runtime.system.frame(camera.id)?.target === initial.getValue(camera.id).target) {
                        "Moving ${camera.id} discarded its shader target/history"
                    }
                }
                check(runtime.lastFailure == null) { "Moving shader camera failed: ${runtime.lastFailure}" }
            }
            context.waitTick()
            if (tick % 10 == 9) screenshot(context, "shader-moving-$tick")
        }
        context.runOnClient<RuntimeException> {
            val runtime = DifferangleClient.runtime
            definitions.forEach { camera ->
                val frame = requireNotNull(runtime.system.frame(camera.id))
                check(frame.target === initial.getValue(camera.id).target)
                check(frame.renderedAtNanos > initial.getValue(camera.id).renderedAtNanos) {
                    "Moving ${camera.id} stopped refreshing"
                }
                runtime.system.putCamera(camera)
            }
            check(runtime.lastFailure == null) { "Moving shader camera failed: ${runtime.lastFailure}" }
        }
        await(context)
        println("SHADER MOTION PASS: 40 pose updates retained both targets and refreshed their output")
    }
    private fun await(context: ClientGameTestContext) {
        context.waitFor({
            val runtime = DifferangleClient.runtime
            check(runtime.lastError == null) { runtime.lastError!! }
            runtime.statistics.screenDraws == 2 && runtime.drawCalls > 0
        }, 600)
        context.waitTicks(40)
        context.runOnClient<RuntimeException> { check(DifferangleClient.runtime.lastError == null) }
    }
    private fun screenshot(context: ClientGameTestContext, name: String) = ImageIO.read(context.takeScreenshot(name).toFile())
    private fun changed(a: BufferedImage, b: BufferedImage): Int {
        var count = 0
        for (y in a.height * 35 / 100 until a.height * 65 / 100) for (x in a.width * 20 / 100 until a.width * 80 / 100) {
            val delta = listOf(0, 8, 16).sumOf { kotlin.math.abs(((a.getRGB(x, y) ushr it) and 255) - ((b.getRGB(x, y) ushr it) and 255)) }
            if (delta > 45) count++
        }
        return count
    }
}

