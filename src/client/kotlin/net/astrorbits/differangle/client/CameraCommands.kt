package net.astrorbits.differangle.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.CommandNode
import net.astrorbits.differangle.camera.*
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.Locale

object CameraCommands {
    /** Fabric 交给客户端的命令表；补全用的原版命令表需要从这里再合并一次，见 [syncCompletion]。 */
    private var clientDispatcher: CommandDispatcher<FabricClientCommandSource>? = null

    /** 已经合并过的那份原版命令表；客户端每次收到命令包都会整体换新实例。 */
    private var completionTarget: CommandDispatcher<FabricClientCommandSource>? = null

    fun register(runtime: CameraRuntime) {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            clientDispatcher = dispatcher
            val root = literal("differangle").executes { ctx -> feedback(ctx, HELP) }
            val preview = literal("preview").executes { ctx -> feedback(ctx, PREVIEW_HELP) }
            // Fabric owns this client root. Explicitly forward world commands so its parser
            // cannot swallow server subcommands (including edits submitted by the screen GUI).
            for (kind in listOf("camera", "screen")) {
                root.then(literal(kind).then(argument("worldOptions", StringArgumentType.greedyString()).executes { ctx ->
                    ctx.source.client.connection?.send(net.minecraft.network.protocol.game.ServerboundChatCommandPacket(
                        "differangle $kind ${StringArgumentType.getString(ctx, "worldOptions")}"))
                    1
                }))
            }
            val mode = literal("mode").executes { ctx -> run(ctx, runtime) { modeSummary(runtime) } }
            CameraMode.entries.forEach { selected ->
                mode.then(literal(selected.commandName).executes { ctx -> run(ctx, runtime) {
                    runtime.compatibilityProblem()?.let { error(it) }
                    runtime.switchMode(selected)
                    "模式：${selected.commandName}。" + if (selected == CameraMode.EMBEDDED)
                        "实验模式，每帧按 Screen 绘制；不使用 Camera FPS/颜色缓存。" else "按 Camera FPS 刷新，同一 Camera 的 Screen 共享画面。"
                } })
            }
            root.then(mode)
            val layer = literal("layer").executes { ctx -> run(ctx, runtime) { layerSummary(runtime) } }
            CameraLayer.entries.forEach { selected ->
                layer.then(literal(selected.commandName).then(argument("enabled", BoolArgumentType.bool()).executes { ctx -> run(ctx, runtime) {
                    runtime.setLayer(selected, BoolArgumentType.getBool(ctx, "enabled"))
                    runtime.layers.summary()
                } }))
            }
            root.then(layer)
            root.then(literal("status").executes { ctx -> run(ctx, runtime) {
                val s = runtime.statistics
                "模式=${runtime.mode.commandName} Camera=${runtime.system.cameras().size} Screen=${runtime.system.screens().size}\n" +
                    "本帧 Camera 更新=${s.cameraUpdates} Screen 绘制=${s.screenDraws} 缓存=${s.cachedCameraCount} 区块节=${runtime.sectionCount} Draw=${runtime.drawCalls}\n" +
                    "CPU=${String.format(Locale.ROOT, "%.2f", runtime.cpuMillis)}ms；${runtime.contentStatistics}\n" +
                    "图层=${runtime.layers.summary()}\n" +
                    "实际路径=${runtime.mode.commandName}\n" +
                    "状态=${runtime.lastError ?: runtime.compatibilityProblem() ?: "就绪"}"
            } })
            root.then(literal("list").executes { ctx -> run(ctx, runtime) {
                runtime.system.cameras().joinToString("\n") { "Camera ${it.id}: FOV=${it.fov} FPS=${it.updateRate} ${it.resolution.width}×${it.resolution.height}" } +
                    "\n" + runtime.system.screens().joinToString("\n") { "Screen ${it.id} → ${it.cameraId} (${it.width}×${it.height})" }
            } })
            preview.then(literal("clear").executes { ctx -> run(ctx, runtime) { runtime.clear(); "已清空临时预览；世界资源将在下一 tick 恢复。" } })
            preview.then(literal("demo").executes { ctx -> run(ctx, runtime) {
                runtime.compatibilityProblem()?.let { error(it) }
                val cameraId = unusedId("demo_camera", runtime.system.cameras().map { it.id })
                val screenId = unusedId("demo_screen", runtime.system.screens().map { it.id })
                require(runtime.system.cameras().size < 16 && runtime.system.screens().size < 64) { "最多 16 Camera / 64 Screen" }
                runtime.system.putCamera(hereCamera(ctx, cameraId))
                runtime.system.putScreen(hereScreen(ctx, screenId, cameraId))
                runtime.switchMode(runtime.mode)
                "已创建 $cameraId → $screenId，屏幕位于前方 4 格。移动或转身可观察独立视角；/differangle mode texture|embedded 切换。"
            } })

            val camera = literal("camera").executes { ctx -> feedback(ctx, PREVIEW_CAMERA_HELP) }
            camera.then(literal("here").then(argument("id", StringArgumentType.word()).executes { ctx -> run(ctx, runtime) {
                val id = word(ctx, "id")
                val old = runtime.system.cameras().find { it.id == id }
                require(old != null || runtime.system.cameras().size < 16) { "最多 16 Camera" }
                val here = hereCamera(ctx, id)
                runtime.system.putCamera(old?.copy(position = here.position, rotation = here.rotation) ?: here)
                "Camera $id 已放到当前眼睛位置和朝向。"
            } }))
            camera.then(literal("remove").then(cameraId(runtime).executes { ctx -> run(ctx, runtime) {
                val id = getCamera(ctx, runtime).id; runtime.system.removeCamera(id); "已移除临时 Camera $id，关联 Screen 保留绑定并黑屏。"
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
            preview.then(camera)

            val screen = literal("screen").executes { ctx -> feedback(ctx, PREVIEW_SCREEN_HELP) }
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
            preview.then(screen)
            root.then(preview)
            dispatcher.register(root)
        }
    }

    /**
     * 把客户端命令树合并进原版客户端命令表（聊天补全与用法提示用的那一份）。
     *
     * Fabric 也会在服务端命令树到达后做一次复制，但它是先 `addChild` 再填充子节点；Brigadier 遇到同名节点
     * 只做合并、不会替换，于是与服务端同名的 `differangle` 根节点（WorldCommands 注册）会把整棵客户端子树
     * 丢掉，`mode`/`layer`/`status`/`list`/`preview` 便不会出现在补全里。命令本身仍能执行，因为 Fabric 用
     * 独立的 activeDispatcher 执行客户端命令。这里在命令表换成新实例后重新合并一份完整副本。
     */
    fun syncCompletion(client: Minecraft) {
        val connection = client.player?.connection ?: return
        @Suppress("UNCHECKED_CAST")
        val target = connection.commands as CommandDispatcher<FabricClientCommandSource>
        if (target === completionTarget) return
        completionTarget = target
        mergeInto(target)
    }

    private fun mergeInto(target: CommandDispatcher<FabricClientCommandSource>) {
        val source = clientDispatcher ?: return
        if (source === target) return
        val root = target.root
        for (child in source.root.children) root.addChild(completionCopy(child))
    }

    /** 复制一份仅用于补全的节点：权限放开、命令为空实现，避免通过原版命令表重复执行。 */
    private fun completionCopy(node: CommandNode<FabricClientCommandSource>): CommandNode<FabricClientCommandSource> {
        val builder = node.createBuilder()
        builder.requires { true }
        if (builder.command != null) builder.executes { 0 }
        val copy = builder.build()
        for (child in node.children) copy.addChild(completionCopy(child))
        return copy
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
    private fun modeSummary(runtime: CameraRuntime) =
        "当前模式=${runtime.mode.commandName}；可选：${CameraMode.entries.joinToString("|") { it.commandName }}。"
    private fun layerSummary(runtime: CameraRuntime) =
        "图层 ${runtime.layers.summary()}\n用法：/differangle layer <${CameraLayer.entries.joinToString("|") { it.commandName }}> <true|false>"
    private fun run(ctx: CommandContext<FabricClientCommandSource>, runtime: CameraRuntime, action: () -> String): Int = try {
        runtime.syncWorld(ctx.source.client)
        feedback(ctx, action())
    } catch (failure: IllegalArgumentException) { ctx.source.sendError(Component.literal(failure.message ?: "参数无效")); 0 }
      catch (failure: IllegalStateException) { ctx.source.sendError(Component.literal(failure.message ?: "操作失败")); 0 }
    private fun feedback(ctx: CommandContext<FabricClientCommandSource>, message: String): Int {
        ctx.source.sendFeedback(Component.literal("[Differangle] $message")); return 1
    }
    private const val HELP = "世界资源：/differangle camera create <name> | camera list | screen bind <x y z> <UUID>；右键显示屏基座打开设置。\n" +
        "本地渲染：/differangle mode texture|embedded；layer <图层> <true|false>；status；list。\n" +
        "临时原型：/differangle preview demo|clear|camera|screen ...（不保存）。"
    private const val PREVIEW_HELP = "临时原型（不写入世界）：\n" +
        "demo 生成一组临时 Camera/Screen；clear 清空；\n" +
        "camera here|remove|fov|fps|resolution|pose ...；screen add|remove|size|pose ..."
    private const val PREVIEW_CAMERA_HELP = "临时 Camera：here <id> | remove <id> | fov <id> <1-179> | fps <id> <1-240> | " +
        "resolution <id> <宽> <高> | pose <id> <x y z yaw pitch roll>"
    private const val PREVIEW_SCREEN_HELP = "临时 Screen：add <id> <cameraId> | remove <id> | size <id> <宽> <高> | " +
        "pose <id> <x y z yaw pitch roll>"
}
