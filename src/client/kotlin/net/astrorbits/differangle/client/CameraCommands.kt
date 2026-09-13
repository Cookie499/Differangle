package net.astrorbits.differangle.client

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.astrorbits.differangle.camera.*
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import java.util.Locale

object CameraCommands {
    fun register(runtime: CameraRuntime) {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = literal("differangle").executes { ctx -> feedback(ctx, HELP) }
            val mode = literal("mode")
            CameraMode.entries.forEach { selected ->
                mode.then(literal(selected.commandName).executes { ctx -> run(ctx, runtime) {
                    runtime.compatibilityProblem()?.let { error(it) }
                    runtime.switchMode(selected)
                    "模式：${selected.commandName}。" + if (selected == CameraMode.EMBEDDED)
                        "实验模式，每帧按 Screen 绘制；不使用 Camera FPS/颜色缓存。" else "按 Camera FPS 刷新，同一 Camera 的 Screen 共享画面。"
                } })
            }
            root.then(mode)
            root.then(literal("status").executes { ctx -> run(ctx, runtime) {
                val s = runtime.statistics
                "模式=${runtime.mode.commandName} Camera=${runtime.system.cameras().size} Screen=${runtime.system.screens().size}\n" +
                    "本帧 Camera 更新=${s.cameraUpdates} Screen 绘制=${s.screenDraws} 缓存=${s.cachedCameraCount} 区块节=${runtime.sectionCount} Draw=${runtime.drawCalls}\n" +
                    "CPU=${String.format(Locale.ROOT, "%.2f", runtime.cpuMillis)}ms；仅已编译的不透明/镂空地形，无实体/水体/粒子。\n" +
                    "状态=${runtime.lastError ?: runtime.compatibilityProblem() ?: "就绪"}"
            } })
            root.then(literal("list").executes { ctx -> run(ctx, runtime) {
                runtime.system.cameras().joinToString("\n") { "Camera ${it.id}: FOV=${it.fov} FPS=${it.updateRate} ${it.resolution.width}×${it.resolution.height}" } +
                    "\n" + runtime.system.screens().joinToString("\n") { "Screen ${it.id} → ${it.cameraId} (${it.width}×${it.height})" }
            } })
            root.then(literal("clear").executes { ctx -> run(ctx, runtime) { runtime.clear(); "已清空 Camera、Screen 和缓存。" } })
            root.then(literal("demo").executes { ctx -> run(ctx, runtime) {
                runtime.compatibilityProblem()?.let { error(it) }
                val cameraId = unusedId("demo_camera", runtime.system.cameras().map { it.id })
                val screenId = unusedId("demo_screen", runtime.system.screens().map { it.id })
                require(runtime.system.cameras().size < 16 && runtime.system.screens().size < 64) { "最多 16 Camera / 64 Screen" }
                runtime.system.putCamera(hereCamera(ctx, cameraId))
                runtime.system.putScreen(hereScreen(ctx, screenId, cameraId))
                runtime.switchMode(runtime.mode)
                "已创建 $cameraId → $screenId，屏幕位于前方 4 格。移动或转身可观察独立视角；/differangle mode texture|embedded 切换。"
            } })

            val camera = literal("camera")
            camera.then(literal("here").then(argument("id", StringArgumentType.word()).executes { ctx -> run(ctx, runtime) {
                val id = word(ctx, "id")
                val old = runtime.system.cameras().find { it.id == id }
                require(old != null || runtime.system.cameras().size < 16) { "最多 16 Camera" }
                val here = hereCamera(ctx, id)
                runtime.system.putCamera(old?.copy(position = here.position, rotation = here.rotation) ?: here)
                "Camera $id 已放到当前眼睛位置和朝向。"
            } }))
            camera.then(literal("remove").then(cameraId(runtime).executes { ctx -> run(ctx, runtime) {
                val id = getCamera(ctx, runtime).id; runtime.system.removeCamera(id); "已移除 Camera $id 及其 Screen。"
            } }))
            camera.then(literal("fov").then(cameraId(runtime).then(argument("value", FloatArgumentType.floatArg(1f, 179f)).executes { ctx -> run(ctx, runtime) {
                runtime.system.putCamera(getCamera(ctx, runtime).copy(fov = FloatArgumentType.getFloat(ctx, "value"))); "FOV 已更新。"
            } })))
            camera.then(literal("fps").then(cameraId(runtime).then(argument("value", IntegerArgumentType.integer(1, 240)).executes { ctx -> run(ctx, runtime) {
                runtime.system.putCamera(getCamera(ctx, runtime).copy(updateRate = IntegerArgumentType.getInteger(ctx, "value"))); "FPS 已更新（仅 Texture 模式生效）。"
            } })))
            camera.then(literal("resolution").then(cameraId(runtime)
                .then(argument("width", IntegerArgumentType.integer(16, 2048)).then(argument("height", IntegerArgumentType.integer(16, 2048)).executes { ctx -> run(ctx, runtime) {
                    runtime.system.putCamera(getCamera(ctx, runtime).copy(resolution = Resolution(IntegerArgumentType.getInteger(ctx, "width"), IntegerArgumentType.getInteger(ctx, "height"))))
                    "分辨率已更新；宽高比用于两个模式。"
                } }))))
            camera.then(literal("pose").then(pose(cameraId(runtime)) { ctx -> run(ctx, runtime) {
                runtime.system.putCamera(getCamera(ctx, runtime).copy(position = position(ctx), rotation = rotation(ctx))); "Camera 位置和旋转已更新。"
            } }))
            root.then(camera)

            val screen = literal("screen")
            screen.then(literal("add").then(argument("id", StringArgumentType.word()).then(argument("camera", StringArgumentType.word())
                .suggests { _, builder -> runtime.system.cameras().forEach { builder.suggest(it.id) }; builder.buildFuture() }
                .executes { ctx -> run(ctx, runtime) {
                    require(runtime.system.screens().none { it.id == word(ctx, "id") }) { "Screen ID 已存在" }
                    require(runtime.system.screens().size < 64) { "最多 64 Screen" }
                    runtime.system.putScreen(hereScreen(ctx, word(ctx, "id"), word(ctx, "camera"))); "Screen 已放在前方 4 格。"
                } })))
            screen.then(literal("remove").then(screenId(runtime).executes { ctx -> run(ctx, runtime) {
                runtime.system.removeScreen(getScreen(ctx, runtime).id); "Screen 已移除。"
            } }))
            screen.then(literal("size").then(screenId(runtime).then(argument("width", FloatArgumentType.floatArg(0.1f, 128f))
                .then(argument("height", FloatArgumentType.floatArg(0.1f, 128f)).executes { ctx -> run(ctx, runtime) {
                    runtime.system.putScreen(getScreen(ctx, runtime).copy(width = FloatArgumentType.getFloat(ctx, "width"), height = FloatArgumentType.getFloat(ctx, "height")))
                    "Screen 尺寸已更新。"
                } }))))
            screen.then(literal("pose").then(pose(screenId(runtime)) { ctx -> run(ctx, runtime) {
                runtime.system.putScreen(getScreen(ctx, runtime).copy(position = position(ctx), rotation = rotation(ctx))); "Screen 位置和旋转已更新。"
            } }))
            root.then(screen)
            dispatcher.register(root)
        }
    }

    private fun cameraId(runtime: CameraRuntime) = argument("id", StringArgumentType.word()).suggests { _, builder ->
        runtime.system.cameras().forEach { builder.suggest(it.id) }; builder.buildFuture()
    }
    private fun screenId(runtime: CameraRuntime) = argument("id", StringArgumentType.word()).suggests { _, builder ->
        runtime.system.screens().forEach { builder.suggest(it.id) }; builder.buildFuture()
    }
    private fun getCamera(ctx: CommandContext<FabricClientCommandSource>, runtime: CameraRuntime) =
        runtime.system.cameras().find { it.id == word(ctx, "id") } ?: error("未知 Camera")
    private fun getScreen(ctx: CommandContext<FabricClientCommandSource>, runtime: CameraRuntime) =
        runtime.system.screens().find { it.id == word(ctx, "id") } ?: error("未知 Screen")
    private fun word(ctx: CommandContext<FabricClientCommandSource>, name: String) = StringArgumentType.getString(ctx, name)
    private fun position(ctx: CommandContext<FabricClientCommandSource>) = Position(
        DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"))
    private fun rotation(ctx: CommandContext<FabricClientCommandSource>) = Rotation.minecraftDegrees(
        FloatArgumentType.getFloat(ctx, "yaw"), FloatArgumentType.getFloat(ctx, "pitch"), FloatArgumentType.getFloat(ctx, "roll"))

    private fun <T : ArgumentBuilder<FabricClientCommandSource, T>> pose(parent: T, action: (CommandContext<FabricClientCommandSource>) -> Int): T = parent
        .then(argument("x", DoubleArgumentType.doubleArg(-30_000_000.0, 30_000_000.0))
            .then(argument("y", DoubleArgumentType.doubleArg(-20_000_000.0, 20_000_000.0))
                .then(argument("z", DoubleArgumentType.doubleArg(-30_000_000.0, 30_000_000.0))
                    .then(argument("yaw", FloatArgumentType.floatArg(-360f, 360f))
                        .then(argument("pitch", FloatArgumentType.floatArg(-90f, 90f))
                            .then(argument("roll", FloatArgumentType.floatArg(-360f, 360f)).executes { action(it) }))))))

    private fun hereCamera(ctx: CommandContext<FabricClientCommandSource>, id: String): CameraDefinition {
        val p = ctx.source.player
        val eye = p.eyePosition
        return CameraDefinition(id, Position(eye.x, eye.y, eye.z), Rotation.minecraftDegrees(p.yRot, p.xRot))
    }
    private fun hereScreen(ctx: CommandContext<FabricClientCommandSource>, id: String, camera: String): ScreenDefinition {
        val p = ctx.source.player
        val position = p.eyePosition.add(p.lookAngle.scale(4.0))
        return ScreenDefinition(id, camera, Position(position.x, position.y, position.z), Rotation.minecraftDegrees(p.yRot, p.xRot))
    }
    private fun unusedId(base: String, existing: List<String>) = generateSequence(1) { it + 1 }.map { "${base}_$it" }.first { it !in existing }
    private fun run(ctx: CommandContext<FabricClientCommandSource>, runtime: CameraRuntime, action: () -> String): Int = try {
        runtime.syncWorld(ctx.source.client)
        feedback(ctx, action())
    } catch (failure: IllegalArgumentException) { ctx.source.sendError(Component.literal(failure.message ?: "参数无效")); 0 }
      catch (failure: IllegalStateException) { ctx.source.sendError(Component.literal(failure.message ?: "操作失败")); 0 }
    private fun feedback(ctx: CommandContext<FabricClientCommandSource>, message: String): Int {
        ctx.source.sendFeedback(Component.literal("[Differangle] $message")); return 1
    }
    private const val HELP = "demo | mode texture/embedded | status | list | clear\ncamera here/remove/fov/fps/resolution/pose <id> ...\nscreen add <id> <camera> | remove/size/pose <id> ...\npose 参数：x y z yaw pitch roll（绝对坐标/角度）。仅客户端，定义在离开世界后清空。"
}
