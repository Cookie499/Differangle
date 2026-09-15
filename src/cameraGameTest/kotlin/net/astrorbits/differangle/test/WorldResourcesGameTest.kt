package net.astrorbits.differangle.test

import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.DifferangleClient
import net.astrorbits.differangle.client.world.ScreenEditor
import net.astrorbits.differangle.world.*
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.TypedEntityData

class WorldResourcesGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            world.server.runCommand("tp @a 0 -60 0 0 0")
            world.server.runCommand("fill -8 -61 0 8 -61 20 minecraft:stone")
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
            context.waitTicks(10)
            context.runOnClient<RuntimeException> { client ->
                val entity=client.level!!.getBlockEntity(BlockPos(0,-59,4)) as ScreenBlockEntity
                client.gui.setScreen(ScreenEditor(entity))
                val editor=client.gui.screen()!!
                editor.children().filterIsInstance<net.minecraft.client.gui.components.EditBox>().single { it.message.string == "宽度（格）" }.value="2.5"
            }
            context.waitTick()
            context.takeScreenshot("screen-editor-settings")
            context.clickScreenButton("位置与旋转")
            context.takeScreenshot("screen-editor-transform")
            context.clickScreenButton("应用")
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { check(it.gui.screen() == null) }
            world.server.runOnServer<RuntimeException> { server ->
                check(CameraController.screen(server.overworld(),BlockPos(0,-59,4)).config.width == 2.5f) { "GUI edit did not reach the authoritative server" }
            }
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
            world.server.runCommand("tp @a 0 -60 8 180 0")
            context.waitTicks(10)
            context.runOnClient<RuntimeException> { check(DifferangleClient.runtime.statistics.cameraUpdates == 0) }
            context.takeScreenshot("persistent-screen-back")
            world.server.runCommand("differangle camera remove $uuid")
            context.waitTicks(5)
            world.server.runOnServer<RuntimeException> { server ->
                check(CameraController.screen(server.overworld(),BlockPos(0,-59,4)).config.cameraUuid == uuid)
            }
        }
    }
}
