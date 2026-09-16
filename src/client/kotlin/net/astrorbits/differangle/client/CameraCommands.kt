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

/**
 * Client half of `/differangle`.
 *
 * Only this side can touch the clipboard and the local renderer, so `copy`, `mode`, `layer`, `status`,
 * `list` and `preview` live here; every world mutation is forwarded to the server as the same command
 * text. All player-facing text comes from translation keys in `assets/differangle/lang`.
 */
object CameraCommands {
    /** Fabric 交给客户端的命令表；补全用的原版命令表需要从这里再合并一次，见 [syncCompletion]。 */
    private var clientDispatcher: CommandDispatcher<FabricClientCommandSource>? = null

    /** 已经合并过的那份原版命令表；客户端每次收到命令包都会整体换新实例。 */
    private var completionTarget: CommandDispatcher<FabricClientCommandSource>? = null

    fun register(runtime: CameraRuntime) {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            clientDispatcher = dispatcher
            completionTarget = null
            val root = literal("differangle").executes { ctx -> feedback(ctx, text("differangle.help")) }
            val preview = literal("preview").executes { ctx -> feedback(ctx, text("differangle.preview.help")) }
            // Fabric owns this client root. Explicitly forward world commands so its parser
            // cannot swallow server subcommands (including edits submitted by the screen GUI).
            // The suggestion list is what makes `/differangle camera <TAB>` readable; the server
            // tree is merged separately by `syncCompletion` and supplies the argument values.
            for (kind in listOf("camera", "screen")) {
                root.then(literal(kind).executes { ctx -> feedback(ctx, text("differangle.$kind.help")) }
                    .then(argument("worldOptions", StringArgumentType.greedyString())
                    .suggests { _, builder ->
                        if (!builder.remaining.contains(' ')) worldSubcommands(kind)
                            .filter { it.startsWith(builder.remainingLowerCase) }.forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx -> forward(ctx, kind) }))
            }
            // The clipboard belongs to the client, so the tokens printed by the server's `camera list` run this.
            root.then(literal("copy").then(argument("text", StringArgumentType.greedyString()).executes { ctx -> run(ctx, runtime) {
                val copied = word(ctx, "text").trim()
                ctx.source.client.keyboardHandler.setClipboard(copied)
                text("differangle.copy.done", copied)
            } }))
            val mode = literal("mode").executes { ctx -> run(ctx, runtime) { Component.translatable("differangle.mode.summary", modeName(runtime),
                Component.literal(CameraMode.entries.joinToString("|") { it.commandName })) } }
            CameraMode.entries.forEach { selected ->
                mode.then(literal(selected.commandName).executes { ctx -> run(ctx, runtime) {
                    runtime.compatibilityProblem()?.let { error(it) }
                    runtime.switchMode(selected)
                    Component.translatable("differangle.mode.done", modeName(runtime),
                        text(if (selected == CameraMode.EMBEDDED) "differangle.mode.embedded" else "differangle.mode.texture"))
                } })
            }
            root.then(mode)
            root.then(literal("shaders")
                .executes { ctx -> feedback(ctx, text("differangle.shaders.status", runtime.cameraShaders)) }
                .then(argument("enabled", BoolArgumentType.bool()).executes { ctx -> run(ctx, runtime) {
                    runtime.setCameraShaderRendering(BoolArgumentType.getBool(ctx, "enabled"))
                    text("differangle.shaders.status", runtime.cameraShaders)
                } }))
            val layer = literal("layer").executes { ctx -> run(ctx, runtime) { Component.translatable("differangle.layer.summary",
                Component.literal(runtime.layers.summary()), Component.literal(CameraLayer.entries.joinToString("|") { it.commandName })) } }
            CameraLayer.entries.forEach { selected ->
                layer.then(literal(selected.commandName).then(argument("enabled", BoolArgumentType.bool()).executes { ctx -> run(ctx, runtime) {
                    runtime.setLayer(selected, BoolArgumentType.getBool(ctx, "enabled"))
                    Component.literal(runtime.layers.summary())
                } }))
            }
            root.then(layer)
            root.then(literal("status").executes { ctx -> run(ctx, runtime) {
                val s = runtime.statistics
                val n = runtime.nativeFeatures
                Component.translatable(
                    "differangle.status.body",
                    Component.literal(runtime.mode.commandName), runtime.system.cameras().size, runtime.system.screens().size,
                    s.cameraUpdates, s.screenDraws, s.cachedCameraCount, runtime.sectionCount, runtime.drawCalls,
                    Component.literal(String.format(Locale.ROOT, "%.2f", runtime.cpuMillis)),
                    Component.translatable("differangle.status.content", n.entities, n.blockEntities, n.particles, n.weatherColumns, n.cloudViews),
                    Component.literal(runtime.layers.summary()),
                    runtime.lastFailure ?: runtime.compatibilityProblem() ?: Component.translatable("differangle.status.ready"),
                )
            } })
            // Local (client-side) Camera/Screen definitions, not the world entities.
            root.then(literal("list").executes { ctx -> run(ctx, runtime) {
                Component.literal(
                    runtime.system.cameras().joinToString("\n") { "Camera ${it.id}: FOV=${it.fov} FPS=${it.updateRate} ${it.resolution.width}×${it.resolution.height}" } +
                        "\n" + runtime.system.screens().joinToString("\n") { "Screen ${it.id} -> ${it.cameraId} (${it.width}×${it.height})" },
                )
            } })
            preview.then(literal("clear").executes { ctx -> run(ctx, runtime) { runtime.clear(); text("differangle.preview.cleared") } })
            preview.then(literal("demo").executes { ctx -> run(ctx, runtime) {
                runtime.compatibilityProblem()?.let { error(it) }
                val cameraId = unusedId("demo_camera", runtime.system.cameras().map { it.id })
                val screenId = unusedId("demo_screen", runtime.system.screens().map { it.id })
                require(runtime.system.cameras().size < 16 && runtime.system.screens().size < 64) { "16 cameras / 64 screens" }
                runtime.system.putCamera(hereCamera(ctx, cameraId))
                runtime.system.putScreen(hereScreen(ctx, screenId, cameraId))
                runtime.switchMode(runtime.mode)
                text("differangle.preview.demo.done", cameraId, screenId)
            } })

            val camera = literal("camera").executes { ctx -> feedback(ctx, text("differangle.preview.camera.help")) }
            camera.then(literal("here").then(argument("id", StringArgumentType.word()).executes { ctx -> run(ctx, runtime) {
                val id = word(ctx, "id")
                val old = runtime.system.cameras().find { it.id == id }
                require(old != null || runtime.system.cameras().size < 16) { "16 cameras" }
                val here = hereCamera(ctx, id)
                runtime.system.putCamera(old?.copy(position = here.position, rotation = here.rotation) ?: here)
                text("differangle.preview.camera.here.done", id)
            } }))
            camera.then(literal("remove").then(cameraId(runtime).executes { ctx -> run(ctx, runtime) {
                val id = getCamera(ctx, runtime).id
                runtime.system.removeCamera(id)
                text("differangle.preview.camera.remove.done", id)
            } }))
            camera.then(literal("fov").then(cameraId(runtime).then(argument("value", FloatArgumentType.floatArg(1f, 179f)).executes { ctx -> run(ctx, runtime) {
                runtime.system.putCamera(getCamera(ctx, runtime).copy(fov = FloatArgumentType.getFloat(ctx, "value")))
                text("differangle.preview.camera.fov.done")
            } })))
            camera.then(literal("fps").then(cameraId(runtime).then(argument("value", IntegerArgumentType.integer(1, 240)).executes { ctx -> run(ctx, runtime) {
                runtime.system.putCamera(getCamera(ctx, runtime).copy(updateRate = IntegerArgumentType.getInteger(ctx, "value")))
                text("differangle.preview.camera.fps.done")
            } })))
            camera.then(literal("resolution").then(cameraId(runtime)
                .then(argument("width", IntegerArgumentType.integer(16, 2048)).then(argument("height", IntegerArgumentType.integer(16, 2048)).executes { ctx -> run(ctx, runtime) {
                    runtime.system.putCamera(getCamera(ctx, runtime).copy(
                        resolution = Resolution(IntegerArgumentType.getInteger(ctx, "width"), IntegerArgumentType.getInteger(ctx, "height"))))
                    text("differangle.preview.camera.resolution.done")
                } }))))
            camera.then(literal("pose").then(pose(cameraId(runtime)) { ctx -> run(ctx, runtime) {
                runtime.system.putCamera(getCamera(ctx, runtime).copy(position = position(ctx), rotation = rotation(ctx)))
                text("differangle.preview.camera.pose.done")
            } }))
            preview.then(camera)

            val screen = literal("screen").executes { ctx -> feedback(ctx, text("differangle.preview.screen.help")) }
            screen.then(literal("add").then(argument("id", StringArgumentType.word()).then(argument("camera", StringArgumentType.word())
                .suggests { _, builder -> runtime.system.cameras().forEach { builder.suggest(it.id) }; builder.buildFuture() }
                .executes { ctx -> run(ctx, runtime) {
                    require(runtime.system.screens().none { it.id == word(ctx, "id") }) { "screen id exists" }
                    require(runtime.system.screens().size < 64) { "64 screens" }
                    runtime.system.putScreen(hereScreen(ctx, word(ctx, "id"), word(ctx, "camera")))
                    text("differangle.preview.screen.add.done")
                } })))
            screen.then(literal("remove").then(screenId(runtime).executes { ctx -> run(ctx, runtime) {
                val id = getScreen(ctx, runtime).id
                runtime.system.removeScreen(id)
                text("differangle.preview.screen.remove.done")
            } }))
            screen.then(literal("resolution").then(screenId(runtime)
                .then(argument("width", IntegerArgumentType.integer(16, 4096))
                    .then(argument("height", IntegerArgumentType.integer(16, 4096)).executes { ctx -> run(ctx, runtime) {
                        runtime.system.putScreen(getScreen(ctx, runtime).copy(resolution = Resolution(
                            IntegerArgumentType.getInteger(ctx, "width"), IntegerArgumentType.getInteger(ctx, "height"))))
                        text("differangle.preview.screen.resolution.done")
                    } }))))
            screen.then(literal("size").then(screenId(runtime).then(argument("width", FloatArgumentType.floatArg(0.1f, 128f))
                .then(argument("height", FloatArgumentType.floatArg(0.1f, 128f)).executes { ctx -> run(ctx, runtime) {
                    runtime.system.putScreen(getScreen(ctx, runtime).copy(
                        width = FloatArgumentType.getFloat(ctx, "width"), height = FloatArgumentType.getFloat(ctx, "height")))
                    text("differangle.preview.screen.size.done")
                } }))))
            screen.then(literal("pose").then(pose(screenId(runtime)) { ctx -> run(ctx, runtime) {
                runtime.system.putScreen(getScreen(ctx, runtime).copy(position = position(ctx), rotation = rotation(ctx)))
                text("differangle.preview.screen.pose.done")
            } }))
            preview.then(screen)
            root.then(preview)
            dispatcher.register(root)
        }
    }

    /**
     * 只把本模组的 differangle 命令树合并进原版客户端命令表（聊天补全与用法提示用的那一份）。
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
        val modRoot = source.root.getChild("differangle") ?: return
        target.root.addChild(completionCopy(modRoot))
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
        runtime.system.cameras().find { it.id == word(ctx, "id") } ?: error("unknown camera")
    private fun getScreen(ctx: CommandContext<FabricClientCommandSource>, runtime: CameraRuntime) =
        runtime.system.screens().find { it.id == word(ctx, "id") } ?: error("unknown screen")
    private fun word(ctx: CommandContext<FabricClientCommandSource>, name: String) = StringArgumentType.getString(ctx, name)

    /** Hands the world half of the command to the server; only that side has the entities. */
    private fun forward(ctx: CommandContext<FabricClientCommandSource>, kind: String): Int {
        val rest = word(ctx, "worldOptions").trim()
        if (rest.isEmpty()) return feedback(ctx, text("differangle.$kind.help"))
        val connection = ctx.source.client.connection ?: return 0
        // sendCommand re-enters Fabric's client dispatcher and would recursively execute this branch.
        connection.send(net.minecraft.network.protocol.game.ServerboundChatCommandPacket("differangle $kind $rest"))
        return 1
    }

    /**
     * Words offered after `/differangle camera ` and `/differangle screen `.
     *
     * Only the client sees this suggestion provider, so it stays a plain word list; the UUIDs and names
     * of the individual arguments come from the server tree merged in by `syncCompletion`.
     */
    private fun worldSubcommands(kind: String) = if (kind == "camera") CAMERA_SUBCOMMANDS else SCREEN_SUBCOMMANDS

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
    private fun unusedId(base: String, existing: List<String>) =
        generateSequence(1) { it + 1 }.map { index -> "${base}_$index" }.first { it !in existing }
    private fun modeName(runtime: CameraRuntime) = text("differangle.mode.name.${runtime.mode.commandName}")

    private fun text(name: String, vararg args: Any) = Component.translatable(name, *args)

    private fun run(ctx: CommandContext<FabricClientCommandSource>, runtime: CameraRuntime, action: () -> Component): Int = try {
        runtime.syncWorld(ctx.source.client)
        feedback(ctx, action())
    } catch (failure: IllegalArgumentException) { ctx.source.sendError(Component.literal(failure.message ?: "invalid")); 0 }
      catch (failure: IllegalStateException) { ctx.source.sendError(Component.literal(failure.message ?: "failed")); 0 }

    private fun feedback(ctx: CommandContext<FabricClientCommandSource>, message: Component): Int {
        ctx.source.sendFeedback(Component.translatable("differangle.prefix").append(message)); return 1
    }

    private val CAMERA_SUBCOMMANDS = listOf(
        "list", "create", "status", "remove", "tp", "enabled", "invisible", "fov", "fps", "near", "far", "pose", "move", "look",
    )
    private val SCREEN_SUBCOMMANDS = listOf("bind", "enabled", "configure", "transform", "status")
}
