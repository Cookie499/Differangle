package net.astrorbits.differangle.test

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.CameraRuntime
import net.astrorbits.differangle.client.DifferangleClient
import net.astrorbits.differangle.client.media.TextureMediaSession
import net.astrorbits.differangle.client.world.ScreenEditor
import net.astrorbits.differangle.media.MediaConfig
import net.astrorbits.differangle.media.MediaFit
import net.astrorbits.differangle.media.MediaRuntime
import net.astrorbits.differangle.world.*
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.TypedEntityData
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import javax.imageio.ImageIO

class WorldResourcesGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.runOnClient<RuntimeException> {
            val runtime = DifferangleClient.runtime
            val original = runtime.mode
            try {
                for (mode in CameraMode.entries) {
                    runtime.switchMode(mode)
                    net.astrorbits.differangle.client.CameraRuntime().use { restored ->
                        check(restored.mode == mode) { "Render mode was not restored from client config: $mode" }
                    }
                }
                println("CONFIG PASS: both render modes survive runtime recreation")
            } finally {
                runtime.switchMode(original)
            }
        }
        context.worldBuilder().create().use { world ->
            world.server.runCommand("tp @a 0 -60 0 0 0")
            world.server.runCommand("fill -8 -61 -8 8 -61 20 minecraft:stone")
            world.server.runCommand("fill -2 -60 12 2 -57 12 minecraft:gold_block")
            world.server.runCommand("setblock 0 -59 4 differangle:screen_base[facing=north]")
            context.waitTicks(60)
            context.runOnClient<RuntimeException> { DifferangleClient.runtime.switchMode(CameraMode.TEXTURE) }
            val uuid = world.server.computeOnServer<java.util.UUID,RuntimeException> { server ->
                server.playerList.op(server.playerList.players.single().nameAndId())
                val level=server.overworld()
                val entity=CameraController.create(level,"test-camera",Position(.5,-58.4,6.0),Rotation.minecraftDegrees(0f,0f),false)
                CameraController.bind(level,BlockPos(0,-59,4),entity.uuid)
                entity.uuid
            }
            context.waitFor({ DifferangleClient.runtime.statistics.screenDraws > 0 },400)
            // Vanilla /data controls the same synced Entity flag as the mod's convenience command.
            world.server.runCommand("data merge entity $uuid {Invisible:1b}")
            context.waitFor({ client -> client.level!!.entitiesForRendering().filterIsInstance<CameraEntity>()
                .any { it.uuid == uuid && it.isInvisible && it.enabled } },100)
            world.server.runOnServer<RuntimeException> { server ->
                val level = server.overworld()
                val camera = CameraController.find(level, uuid.toString())
                for (invisible in listOf(true, false)) {
                    CameraController.setInvisible(level, uuid.toString(), invisible)
                    val output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess())
                    camera.saveWithoutId(output)
                    val tag = output.buildResult()
                    check(tag.getBooleanOr("Invisible", !invisible) == invisible)
                    check(!tag.contains("invisible"))
                    val restored = CameraEntity(WorldResources.cameraType, level)
                    restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag))
                    check(restored.isInvisible == invisible && restored.uuid == uuid)
                    tag.remove("Invisible")
                    restored.isInvisible = true
                    restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag))
                    check(!restored.isInvisible) { "Missing Invisible must use vanilla's false default" }
                }
            }
            world.server.runCommand("data merge entity $uuid {Invisible:1b}")
            world.server.runCommand("data remove entity $uuid Invisible")
            context.waitFor({ client -> client.level!!.entitiesForRendering().filterIsInstance<CameraEntity>()
                .any { it.uuid == uuid && !it.isInvisible && it.enabled } },100)
            context.takeScreenshot("persistent-screen-front")
            // Real /clone carries the entire NBT payload; only placed Screen identity changes.
            world.server.runCommand("clone 0 -59 4 0 -59 4 3 -59 4")
            context.waitTicks(10)
            world.server.runOnServer<RuntimeException> { server ->
                val level=server.overworld()
                val first=CameraController.screen(level,BlockPos(0,-59,4))
                val second=CameraController.screen(level,BlockPos(3,-59,4))
                check(first.screenUuid != second.screenUuid)
                check(first.config == second.config && second.config.cameraUuid == uuid)
                val saved=first.saveWithoutMetadata(level.registryAccess())
                val restored=ScreenBlockEntity(first.blockPos,first.blockState)
                check(TypedEntityData.of(WorldResources.screenType,saved).loadInto(restored,level.registryAccess()))
                check(restored.screenUuid == first.screenUuid && restored.config == first.config)
                // Simulate an NBT-bearing block item placement, including same-position data.
                val item=ItemStack(WorldResources.screenItem)
                item.set(DataComponents.BLOCK_ENTITY_DATA,TypedEntityData.of(WorldResources.screenType,saved))
                check(item.get(DataComponents.BLOCK_ENTITY_DATA)!!.loadInto(second,level.registryAccess()))
                val old=second.screenUuid
                WorldResources.screenBlock.setPlacedBy(level,second.blockPos,second.blockState,null,item)
                check(second.screenUuid != old && second.config.cameraUuid == uuid)
            }
            world.server.runCommand("setblock 0 -59 8 differangle:screen_base[facing=north]")
            world.server.runCommand("setblock 0 -59 0 minecraft:stone")
            world.server.runCommand("tp @a 0.85 -60 -8 0 0")
            world.server.runCommand("item replace entity @a weapon.mainhand with differangle:screen_remote")
            context.waitTicks(10)
            context.input.pressKey { it.keyUse }
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { check(it.gui.screen() == null) { "Remote selected a blocked screen" } }
            world.server.runCommand("setblock 0 -59 0 minecraft:air")
            context.waitTicks(5)
            context.input.pressKey { it.keyUse }
            context.waitForScreen(ScreenEditor::class.java)
            context.runOnClient<RuntimeException> { client ->
                val editor=client.gui.screen()!!
                editor.children().filterIsInstance<EditBox>().single { it.message == ScreenEditor.label("width") }.value = "2.5"
            }
            context.waitTick()
            context.takeScreenshot("screen-editor-settings")
            context.clickScreenButton(Component.translatable("differangle.screen.editor.page.transform").string)
            context.runOnClient<RuntimeException> { client ->
                val fields = client.gui.screen()!!.children().filterIsInstance<EditBox>()
                val offsets = listOf("x", "y", "z").map { key -> fields.single { it.message == ScreenEditor.label(key) } }
                val rotations = listOf("pitch", "yaw", "roll").map { key -> fields.single { it.message == ScreenEditor.label(key) } }
                check(offsets.map { it.y }.distinct().single() < rotations.map { it.y }.distinct().single())
                check(offsets.map { it.x } == rotations.map { it.x } && offsets.zipWithNext().all { (a, b) -> a.x < b.x })
            }
            context.takeScreenshot("screen-editor-transform")
            context.clickScreenButton(Component.translatable("differangle.screen.editor.apply").string)
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { check(it.gui.screen() == null) }
            world.server.runOnServer<RuntimeException> { server ->
                check(CameraController.screen(server.overworld(),BlockPos(0,-59,4)).config.width == 2.5f) { "GUI edit did not reach the authoritative server" }
                check(CameraController.screen(server.overworld(),BlockPos(0,-59,8)).config.width == 2f) { "Remote edited the farther screen" }
            }
            println("SCREEN REMOTE PASS: blocked target skipped, nearest visible screen edited, transform rows aligned")
            world.server.runCommand("differangle camera enabled $uuid false")
            context.waitFor({ DifferangleClient.runtime.system.cameras().any { it.id==uuid.toString() && !it.enabled } },100)
            context.runOnClient<RuntimeException> {
                check(DifferangleClient.runtime.system.frame(uuid.toString()) == null)
                check(DifferangleClient.runtime.lastError == null)
            }
            context.takeScreenshot("persistent-screen-disabled")
            world.server.runCommand("differangle camera enabled $uuid true")
            context.waitFor({ DifferangleClient.runtime.system.frame(uuid.toString()) != null },100)
            world.server.runCommand("differangle camera move $uuid 2 -58.4 6 10 smoothstep")
            context.waitTicks(15)
            world.server.runOnServer<RuntimeException> { server -> check(kotlin.math.abs(CameraController.find(server.overworld(),uuid.toString()).x-2.0)<1e-6) }
            val position = world.server.computeOnServer<Position,RuntimeException> { server ->
                val camera = CameraController.find(server.overworld(), uuid.toString())
                Position(camera.x, camera.y, camera.z)
            }
            world.server.runCommand("differangle camera look $uuid 40 15 5")
            world.server.runOnServer<RuntimeException> { server ->
                val camera = CameraController.find(server.overworld(), uuid.toString())
                check(Position(camera.x, camera.y, camera.z) == position) { "look changed camera position" }
            }
            val rotation = world.server.computeOnServer<Rotation,RuntimeException> { server ->
                CameraController.find(server.overworld(), uuid.toString()).viewRotation
            }
            world.server.runCommand("differangle camera move $uuid 3 -58.4 6")
            world.server.runOnServer<RuntimeException> { server ->
                check(CameraController.find(server.overworld(), uuid.toString()).viewRotation == rotation) { "move changed camera rotation" }
            }
            world.server.runCommand("tp @a 0 -60 8 180 0")
            context.waitTicks(10)
            context.runOnClient<RuntimeException> { check(DifferangleClient.runtime.statistics.cameraUpdates == 0) }
            context.takeScreenshot("persistent-screen-back")
            world.server.runCommand("differangle camera remove $uuid")
            context.waitTicks(5)
            world.server.runOnServer<RuntimeException> { server ->
                check(CameraController.screen(server.overworld(),BlockPos(0,-59,4)).config.cameraUuid == uuid)
            }

            withFakeMedia(context) {
                val screenPos = BlockPos(0, -59, 4)
                fun localMedia(client: Minecraft): MediaConfig? =
                    (client.level?.getBlockEntity(screenPos) as? ScreenBlockEntity)?.config?.media
                try {
                    world.server.runCommand("tp @a 0.85 -60 -8 0 0")
                    world.server.runCommand("differangle screen source 0 -59 4 video http://127.0.0.1/remote-test.mp4")
                    world.server.runCommand("differangle screen playback 0 -59 4 true true 7")
                    context.waitFor({ (localMedia(it)?.positionSeconds ?: 0.0) >= 7.0 }, 100)
                    context.waitTicks(5)
                    context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_4)
                    context.waitFor({ localMedia(it)?.playing == false }, 100)
                    val paused = context.computeOnClient<Double, RuntimeException> { localMedia(it)!!.positionSeconds }
                    check(paused > 7.0) { "Pause did not preserve the current position." }

                    context.runOnClient<RuntimeException> {
                        val key = checkNotNull(KeyMapping.get("key.differangle.remote_playback"))
                        key.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_P))
                        KeyMapping.resetMapping()
                    }
                    context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_4)
                    context.waitTicks(5)
                    context.runOnClient<RuntimeException> { check(localMedia(it)?.playing == false) { "The old mouse binding still works." } }
                    context.input.pressKey(GLFW.GLFW_KEY_P)
                    context.waitFor({ localMedia(it)?.playing == true }, 100)
                    world.server.runOnServer<RuntimeException> { server ->
                        val media = CameraController.screen(server.overworld(), screenPos).config.media
                        check(media.playing && media.loop && media.positionSeconds == paused) { "Resume changed the paused position or loop setting." }
                    }
                    println("REMOTE PLAYBACK PASS: mouse pause and rebound resume preserved position")

                    context.waitFor({ localMedia(it)?.durationMillis == 10_000L }, 100)
                    context.waitFor({ DifferangleClient.runtime.statistics.screenDraws > 0 }, 100)
                    context.input.pressKey { it.keyUse }
                    context.waitForScreen(ScreenEditor::class.java)
                    context.clickScreenButton(Component.translatable("differangle.screen.editor.page.media").string)
                    context.clickScreenButton(Component.translatable("differangle.screen.editor.playback.pause").string)
                    context.waitFor({ localMedia(it)?.playing == false }, 100)
                    val cursor = context.computeOnClient<Pair<Double, Double>, RuntimeException> { client ->
                        val editor = client.gui.screen()!!
                        val slider = editor.children().filterIsInstance<AbstractSliderButton>().single()
                        val window = client.window
                        (slider.x + slider.width / 2.0) * window.screenWidth / editor.width to
                            (slider.y + slider.height / 2.0) * window.screenHeight / editor.height
                    }
                    context.input.setCursorPos(cursor.first, cursor.second)
                    context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_1)
                    context.waitFor({ kotlin.math.abs((localMedia(it)?.positionSeconds ?: 0.0) - 5.0) < 0.1 }, 100)
                    context.waitTick()
                    context.takeScreenshot("screen-media-playback")
                    world.server.runCommand("differangle screen playback 0 -59 4 true false 9.8")
                    context.waitFor({ localMedia(it)?.finished == true }, 100)
                    context.waitFor({ DifferangleClient.runtime.statistics.screenDraws == 0 }, 100)
                    context.waitTick()
                    context.takeScreenshot("screen-media-finished")
                    context.clickScreenButton(Component.translatable("differangle.screen.editor.playback.replay").string)
                    context.waitFor({ localMedia(it)?.let { media -> media.playing && media.positionSeconds == 0.0 } == true }, 100)
                    context.runOnClient<RuntimeException> { it.gui.setScreen(null) }
                    println("MEDIA CONTROLS PASS: pause, seek, end without a retained frame, and replay")

                    context.waitFor({ DifferangleClient.runtime.statistics.screenDraws > 0 }, 100)
                    context.runOnClient<RuntimeException> { it.gui.hud.chat.clearMessages(false) }
                    context.waitTick()
                    val stretch = context.takeScreenshot("screen-media-stretch")
                    context.input.pressKey { it.keyUse }
                    context.waitForScreen(ScreenEditor::class.java)
                    context.clickScreenButton(Component.translatable("differangle.screen.editor.page.media").string)
                    context.clickScreenButton(Component.translatable("differangle.screen.editor.fit.stretch").string)
                    context.clickScreenButton(Component.translatable("differangle.screen.editor.apply").string)
                    context.waitFor({ localMedia(it)?.fit == MediaFit.FIT }, 100)
                    context.runOnClient<RuntimeException> { it.gui.hud.chat.clearMessages(false) }
                    context.waitTicks(2)
                    val fit = context.takeScreenshot("screen-media-fit")
                    val stretchPixels = countTestFramePixels(stretch)
                    val fitPixels = countTestFramePixels(fit)
                    check(fitPixels > 0 && fitPixels * 1.5 < stretchPixels) {
                        "Aspect fit did not reduce the rendered frame enough: stretch=$stretchPixels fit=$fitPixels."
                    }
                    println("MEDIA FIT PASS: saved fit mode reduced the rendered frame from $stretchPixels to $fitPixels pixels")
                } finally {
                    world.server.runCommand("differangle screen source 0 -59 4 camera")
                    context.waitFor({ localMedia(it)?.sourceType?.isMedia == false }, 100)
                }
            }
        }
    }

    private fun countTestFramePixels(path: Path): Int {
        val image = checkNotNull(ImageIO.read(path.toFile()))
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width).count { pixel ->
            pixel ushr 16 and 255 > 200 && pixel ushr 8 and 255 < 50 && pixel and 255 > 200
        }
    }

    private fun withFakeMedia(context: ClientGameTestContext, test: () -> Unit) {
        val field = CameraRuntime::class.java.getDeclaredField("media").apply { isAccessible = true }
        val runtime = DifferangleClient.runtime
        val fake = MediaRuntime { request ->
            object : TextureMediaSession {
                override val request = request
                override val playbackDurationMillis = 10_000L
                override val failure: String? = null
                private val texture = DynamicTexture({ "Media test frame" },
                    NativeImage(1, 1, false).apply { setPixel(0, 0, 0xFFFF00FF.toInt()) })
                override fun textureView() = texture.textureView
                override fun tick() = Unit
                override fun close() = texture.close()
            }
        }
        val original = context.computeOnClient<MediaRuntime, RuntimeException> {
            (field.get(runtime) as MediaRuntime).also { field.set(runtime, fake) }
        }
        try {
            test()
        } finally {
            context.runOnClient<RuntimeException> {
                fake.close()
                field.set(runtime, original)
                val key = checkNotNull(KeyMapping.get("key.differangle.remote_playback"))
                key.setKey(key.defaultKey)
                KeyMapping.resetMapping()
            }
        }
    }
}
