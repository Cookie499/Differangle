package net.astrorbits.differangle.test

import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.camera.Position
import net.astrorbits.differangle.camera.Rotation
import net.astrorbits.differangle.camera.ScreenDefinition
import net.astrorbits.differangle.client.DifferangleClient
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Regression: the camera's terrain must be selected from the camera's own radius and frustum, never from the main
 * view's occlusion-culled render list. Sodium's render lists only contain what the *main* camera can see, so using
 * them makes terrain disappear from the screen whenever the player happens to look away from it.
 */
class CameraTerrainCullingGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            for (command in listOf(
                "tp @a 0 -60 0 0 0",
                "weather clear",
                "time set noon",
                "gamerule minecraft:advance_time false",
                // Solid ground only behind the player (negative Z), so a north-facing player cannot see it.
                "fill -8 -61 -40 8 -58 -4 minecraft:stone",
            )) world.server.runCommand(command)
            context.waitTicks(80)
            context.runOnClient<RuntimeException> { client ->
                val runtime = DifferangleClient.runtime
                runtime.syncWorld(client)
                runtime.system.putCamera(CameraDefinition("culling", Position(0.5, -59.0, 0.5), Rotation.minecraftDegrees(180f, 0f), updateRate = 60))
                runtime.system.putScreen(ScreenDefinition("culling", "culling", Position(0.0, -58.38, 4.0), Rotation.minecraftDegrees(0f, 0f)))
            }
            awaitDraw(context)

            // Player looks along +Z: the hand-placed ground is entirely behind the main camera.
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                check(runtime.lastError == null) { runtime.lastError!! }
                check(runtime.sectionCount > 0) {
                    "camera rendered ${runtime.sectionCount} sections while the main view looked away from that terrain"
                }
            }
            context.takeScreenshot("camera-terrain-main-view-away")

            // Sanity check: turning the player towards the same ground must not reduce what the camera draws.
            world.server.runCommand("tp @a 0 -60 0 180 0")
            context.waitTicks(30)
            awaitDraw(context)
            context.runOnClient<RuntimeException> {
                val runtime = DifferangleClient.runtime
                check(runtime.lastError == null) { runtime.lastError!! }
                check(runtime.sectionCount > 0) {
                    "camera rendered ${runtime.sectionCount} sections while the main view looked at that terrain"
                }
            }
            context.takeScreenshot("camera-terrain-main-view-towards")
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.clear() }
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
