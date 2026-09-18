package net.astrorbits.differangle.world

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.astrorbits.differangle.camera.Clickable
import net.astrorbits.differangle.camera.Position
import net.astrorbits.differangle.camera.Rotation
import net.astrorbits.differangle.media.AudioAttenuation
import net.astrorbits.differangle.media.MediaConfig
import net.astrorbits.differangle.media.MediaSourceType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import java.util.Locale

/**
 * World side of `/differangle`.
 *
 * Every camera subcommand addresses one entity by UUID (short names are still accepted as a fallback).
 * Each mutating subcommand is a real literal branch, so Brigadier parses, validates and completes it,
 * and `camera list` prints clickable tokens that rebuild exactly those commands.
 *
 * All player-facing text comes from translation keys in `assets/differangle/lang`; nothing user-visible
 * is written inline here, so a resource pack can retranslate the whole command surface.
 */
object WorldCommands {
    /** Text for camera actions is shared with the client-only `copy` command's documentation. */
    private const val KEY = "differangle.camera"

    /**
     * Name of the greedy argument every `screen` branch carries; the branch literal names the operation.
     *
     * The token is part of the wire format: the client-side `ScreenEditor` submits the same flat text, and
     * [handleScreen] looks the payload up by this exact name. Brigadier resolves arguments by name and throws
     * for an unknown one, so a branch declaring a different name fails the whole command with vanilla's generic
     * "unexpected error" instead of a readable message. Declaring and reading it through one constant keeps the
     * two sides in step, and the lookup sits inside the handler's catch, so a divergence degrades gracefully.
     */
    private const val SCREEN_PAYLOAD = "target"

    /** Tab completion for camera arguments: UUID first, then every camera name, so both target styles are visible. */
    private val CAMERA_IDS: SuggestionProvider<CommandSourceStack> = SuggestionProvider { ctx, builder ->
        val cameras = ctx.source.level.allEntities.filterIsInstance<CameraEntity>()
        cameras.forEach { builder.suggest(it.uuid.toString()) }
        cameras.mapNotNull { it.customName?.string }.distinct().forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(root())
        }
    }

    private fun root(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("differangle")
        .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
        .executes { feedback(it, Component.translatable("differangle.help")) }
        .then(camera())
        .then(screen())

    // ---------------------------------------------------------------- camera

    private fun camera(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("camera")
        .executes { feedback(it, Component.translatable("$KEY.help")) }
        .then(Commands.literal("list").executes { context -> respond(context) { cameraList(context) } })
        .then(Commands.literal("create")
            .then(Commands.argument("name", StringArgumentType.word())
                .then(Commands.literal("invisible").executes { context -> respond(context) { createCamera(context, true) } })
                .executes { context -> respond(context) { createCamera(context, false) } }))
        .then(Commands.literal("status").then(id().executes { context -> respond(context) { cameraLine(camera(context)) } }))
        .then(Commands.literal("remove").then(id().executes { context -> respond(context) {
            val entity = camera(context)
            CameraController.remove(context.source.level, entity.uuid.toString())
            Component.translatable("$KEY.remove.done", handle(entity))
        } }))
        .then(Commands.literal("tp").then(id().executes { context -> respond(context) { teleport(context) } }))
        .then(Commands.literal("enabled").then(id().then(Commands.argument("value", BoolArgumentType.bool())
            .executes { context -> respond(context) {
                val entity = camera(context)
                entity.enabled = BoolArgumentType.getBool(context, "value")
                cameraLine(entity)
            } })))
        .then(Commands.literal("invisible").then(id().then(Commands.argument("value", BoolArgumentType.bool())
            .executes { context -> respond(context) {
                val entity = camera(context)
                entity.isInvisible = BoolArgumentType.getBool(context, "value")
                cameraLine(entity)
            } })))
        .then(Commands.literal("fov").then(id().then(Commands.argument("value", FloatArgumentType.floatArg(1f, 179f))
            .executes { context -> respond(context) {
                val entity = camera(context)
                entity.fov = FloatArgumentType.getFloat(context, "value")
                cameraLine(entity)
            } })))
        .then(Commands.literal("fps").then(id().then(Commands.argument("value", IntegerArgumentType.integer(1, 240))
            .executes { context -> respond(context) {
                val entity = camera(context)
                entity.fps = IntegerArgumentType.getInteger(context, "value")
                cameraLine(entity)
            } })))
        .then(Commands.literal("near").then(id().then(Commands.argument("value", FloatArgumentType.floatArg(0.01f, 512f))
            .executes { context -> respond(context) {
                val entity = camera(context)
                entity.nearPlane = FloatArgumentType.getFloat(context, "value")
                cameraLine(entity)
            } })))
        .then(Commands.literal("far").then(id().then(Commands.argument("value", FloatArgumentType.floatArg(1f, 65536f))
            .executes { context -> respond(context) {
                val entity = camera(context)
                entity.farPlane = FloatArgumentType.getFloat(context, "value")
                cameraLine(entity)
            } })))
        .then(Commands.literal("pose")
            .then(id().then(Commands.argument("x", DoubleArgumentType.doubleArg(-30_000_000.0, 30_000_000.0))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg(-20_000_000.0, 20_000_000.0))
                    .then(Commands.argument("z", DoubleArgumentType.doubleArg(-30_000_000.0, 30_000_000.0))
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-360f, 360f))
                            .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90f, 90f))
                                .then(Commands.argument("roll", FloatArgumentType.floatArg(-360f, 360f))
                                    .then(tween { context, ticks, easing -> respond(context) { pose(context, ticks, easing) } })
                                    .executes { context -> respond(context) { pose(context, 0, "linear") } }))))))))
        .then(Commands.literal("move").then(id().then(coords { context, ticks, easing -> respond(context) { move(context, ticks, easing) } })))
        .then(Commands.literal("look").then(id().then(angles { context, ticks, easing -> respond(context) { look(context, ticks, easing) } })))
        // Kept for existing worlds, scripts and game tests: `camera <sub> <id> ...` in one greedy argument.
        .then(Commands.argument("legacy", StringArgumentType.greedyString()).executes { legacy(it) })

    /** Header line plus one clickable line per camera. */
    private fun cameraList(context: CommandContext<CommandSourceStack>): Component {
        val cameras = context.source.level.allEntities.filterIsInstance<CameraEntity>().sortedBy { it.uuid.toString() }
        if (cameras.isEmpty()) return Component.translatable("$KEY.list.empty")
        return Clickable.line(
            Component.translatable("$KEY.list.header", cameras.size).withStyle(ChatFormatting.GRAY),
            *cameras.map { entry(it) }.toTypedArray(),
        )
    }

    private fun entry(entity: CameraEntity): Component {
        val id = entity.uuid.toString()
        return Clickable.line(
            Clickable.copy(Component.translatable("$KEY.copy.name", entity.customName?.string ?: entity.uuid.toString()), id, ChatFormatting.AQUA, Component.translatable("$KEY.copy.tip", id)),
            Clickable.command(Component.translatable("$KEY.copy.button"), "/differangle copy $id", ChatFormatting.DARK_AQUA,
                Component.translatable("$KEY.copy.tip", id)),
            Clickable.command(Component.translatable("$KEY.enabled.state", entity.enabled), "/differangle camera enabled $id ${!entity.enabled}",
                ChatFormatting.YELLOW, Component.translatable("$KEY.enabled.tip", !entity.enabled)),
            Clickable.command(Component.translatable("$KEY.list.tp"), "/differangle camera tp $id", ChatFormatting.GREEN,
                Component.translatable("$KEY.list.tp.tip")),
            Clickable.command(Component.translatable("$KEY.list.remove"), "/differangle camera remove $id", ChatFormatting.RED,
                Component.translatable("$KEY.list.remove.tip")),
            Component.translatable(
                "$KEY.list.body", id, entity.x.coord(), entity.y.coord(), entity.z.coord(),
                Component.translatable(if (entity.isInvisible) "differangle.flag.no" else "differangle.flag.yes"), entity.fov.coord(), entity.fps,
            ).withStyle(ChatFormatting.GRAY),
        ).withStyle(if (entity.enabled) ChatFormatting.WHITE else ChatFormatting.DARK_GRAY)
    }

    private fun createCamera(context: CommandContext<CommandSourceStack>, invisible: Boolean): Component {
        val player = context.source.playerOrException
        val name = StringArgumentType.getString(context, "name")
        val eye = player.eyePosition
        val entity = CameraController.create(
            context.source.level, name, Position(eye.x, eye.y, eye.z), Rotation.minecraftDegrees(player.yRot, player.xRot), invisible,
        )
        return Component.translatable("$KEY.create.done", name, handle(entity))
    }

    /** The name token copies the UUID, so every reply is a usable handle. */
    private fun cameraLine(entity: CameraEntity): Component = Clickable.line(
        Clickable.copy(Component.translatable("$KEY.copy.name", entity.customName?.string ?: entity.uuid.toString()), uuid(entity), ChatFormatting.AQUA,
            Component.translatable("$KEY.copy.tip", uuid(entity))),
        Clickable.command(Component.translatable("$KEY.copy.button"), "/differangle copy ${uuid(entity)}", ChatFormatting.DARK_AQUA,
            Component.translatable("$KEY.copy.tip", uuid(entity))),
        Clickable.command(Component.translatable("$KEY.enabled.state", entity.enabled), "/differangle camera enabled ${uuid(entity)} ${!entity.enabled}",
            ChatFormatting.YELLOW, Component.translatable("$KEY.enabled.tip", !entity.enabled)),
        Component.translatable(
            "$KEY.status.body", entity.x.coord(), entity.y.coord(), entity.z.coord(), entity.fov.coord(), entity.fps,
            Component.translatable(if (entity.isInvisible) "differangle.flag.no" else "differangle.flag.yes"),
        ).withStyle(ChatFormatting.GRAY),
    )

    private fun teleport(context: CommandContext<CommandSourceStack>): Component {
        val player = context.source.playerOrException
        val entity = camera(context)
        val x = entity.x
        val y = entity.y
        val z = entity.z
        player.teleportTo(x, y, z)
        return Component.translatable("$KEY.tp.done", handle(entity))
    }

    private fun pose(context: CommandContext<CommandSourceStack>, ticks: Int, easing: String): Component {
        val entity = camera(context)
        val target = Position(double(context, "x"), double(context, "y"), double(context, "z"))
        val rotation = Rotation.minecraftDegrees(float(context, "yaw"), float(context, "pitch"), float(context, "roll"))
        return moved(context, entity, target, rotation, ticks, easing)
    }

    /** `move` changes the position only, so it keeps the rotation the camera already has. */
    private fun move(context: CommandContext<CommandSourceStack>, ticks: Int, easing: String): Component {
        val entity = camera(context)
        val target = Position(double(context, "x"), double(context, "y"), double(context, "z"))
        return moved(context, entity, target, entity.viewRotation, ticks, easing)
    }

    /** `look` changes the rotation only, so it keeps the position the camera already has. */
    private fun look(context: CommandContext<CommandSourceStack>, ticks: Int, easing: String): Component {
        val entity = camera(context)
        val rotation = Rotation.minecraftDegrees(float(context, "yaw"), float(context, "pitch"), float(context, "roll"))
        return moved(context, entity, Position(entity.x, entity.y, entity.z), rotation, ticks, easing)
    }

    /** Shared body of `pose`, `move` and `look`: apply the pose, then report the timing and the camera handle. */
    private fun moved(context: CommandContext<CommandSourceStack>, entity: CameraEntity, target: Position, rotation: Rotation,
                      ticks: Int, easing: String): Component {
        CameraController.setPose(context.source.level, entity.uuid.toString(), target, rotation, ticks, easing)
        val timing = if (ticks == 0) Component.translatable("$KEY.pose.immediate")
        else Component.translatable("$KEY.pose.timed", ticks, Component.translatable("$KEY.pose.easing.$easing"))
        return Clickable.line(Component.translatable("$KEY.pose.done", timing, handle(entity)))
    }

    /** `Camera【name】` with the UUID as the copy payload. */
    private fun handle(entity: CameraEntity): Component =
        Clickable.copy(Component.translatable("$KEY.copy.name", entity.customName?.string ?: entity.uuid.toString()), uuid(entity), ChatFormatting.AQUA,
            Component.translatable("$KEY.copy.tip", uuid(entity)))

    // ---------------------------------------------------------------- screen

    private fun screen(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("screen")
        .executes { feedback(it, Component.translatable("differangle.screen.help")) }
        .then(screenOp("bind"))
        .then(screenOp("enabled"))
        .then(screenOp("configure"))
        .then(screenOp("transform"))
        .then(screenOp("status"))
        .then(screenOp("mirror"))
        .then(screenOp("source"))
        .then(screenOp("audio"))
        .then(screenOp("playback"))
        // Submitted by the screen GUI; the payload carries the revision guard, so it has no tab completion.
        .then(screenOp("edit"))

    /**
     * One `screen` branch: the literal names the operation and [SCREEN_PAYLOAD] carries its flat arguments.
     *
     * The operation travels as a branch literal rather than as a token inside the payload, so Brigadier parses,
     * validates and completes each one. `edit` is built the same way, which is what lets the screen GUI submit
     * its settings as a single line of text.
     */
    private fun screenOp(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).then(Commands.argument(SCREEN_PAYLOAD, StringArgumentType.greedyString())
            .executes { handleScreen(it, name) })

    // ---------------------------------------------------------------- shared plumbing

    private fun id(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("camera", StringArgumentType.word()).suggests(CAMERA_IDS)

    /** `move` shares the optional tween tail of `pose`, so a scripted move keeps working through either form. */
    private fun coords(run: (CommandContext<CommandSourceStack>, Int, String) -> Int): RequiredArgumentBuilder<CommandSourceStack, Double> =
        Commands.argument("x", DoubleArgumentType.doubleArg(-30_000_000.0, 30_000_000.0))
            .then(Commands.argument("y", DoubleArgumentType.doubleArg(-20_000_000.0, 20_000_000.0))
                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-30_000_000.0, 30_000_000.0))
                    .then(tween(run)).executes { run(it, 0, "linear") }))

    private fun angles(run: (CommandContext<CommandSourceStack>, Int, String) -> Int): RequiredArgumentBuilder<CommandSourceStack, Float> =
        Commands.argument("yaw", FloatArgumentType.floatArg(-360f, 360f))
            .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90f, 90f))
                .then(Commands.argument("roll", FloatArgumentType.floatArg(-360f, 360f))
                    .then(tween(run)).executes { run(it, 0, "linear") }))

    /** Optional tail of `pose`, `move` and `look`: an integer tick count followed by one of the three easing names. */
    private fun tween(run: (CommandContext<CommandSourceStack>, Int, String) -> Int): RequiredArgumentBuilder<CommandSourceStack, Int> =
        Commands.argument("ticks", IntegerArgumentType.integer(0, 72000))
            .then(Commands.literal("step").executes { run(it, IntegerArgumentType.getInteger(it, "ticks"), "step") })
            .then(Commands.literal("linear").executes { run(it, IntegerArgumentType.getInteger(it, "ticks"), "linear") })
            .then(Commands.literal("smoothstep").executes { run(it, IntegerArgumentType.getInteger(it, "ticks"), "smoothstep") })
            .executes { run(it, IntegerArgumentType.getInteger(it, "ticks"), "linear") }

    private fun double(context: CommandContext<CommandSourceStack>, name: String) = DoubleArgumentType.getDouble(context, name)
    private fun float(context: CommandContext<CommandSourceStack>, name: String) = FloatArgumentType.getFloat(context, name)

    private fun camera(context: CommandContext<CommandSourceStack>): CameraEntity =
        CameraController.find(context.source.level, StringArgumentType.getString(context, "camera"))

    /** Keeps every branch honest: failures report the reason instead of the usage banner. */
    private fun respond(context: CommandContext<CommandSourceStack>, body: () -> Component): Int = try {
        context.source.sendSuccess({ body() }, false)
        1
    } catch (failure: IllegalArgumentException) {
        context.source.sendFailure(reason(failure.message, "differangle.error.invalid")); 0
    } catch (failure: IllegalStateException) {
        context.source.sendFailure(reason(failure.message, "differangle.error.failed")); 0
    }

    /** Controller checks throw translation keys; anything else is passed through as literal text. */
    private fun reason(message: String?, fallback: String): Component =
        if (message == null) Component.translatable(fallback)
        else if (message.startsWith(KEY) || message.startsWith("differangle.")) Component.translatable(message)
        else Component.literal(message)

    private fun feedback(context: CommandContext<CommandSourceStack>, message: Component): Int {
        context.source.sendSuccess({ message }, false)
        return 1
    }

    // ---------------------------------------------------------------- flags and legacy bridge

    private fun number(p: List<String>, index: Int) = p[index].toDouble().also { require(it.isFinite()) }
    private fun angle(p: List<String>, index: Int) = number(p, index).toFloat().also { require(it.isFinite()) }
    private fun flag(value: String) = value.toBooleanStrict()
    private fun uuid(entity: CameraEntity) = entity.uuid.toString()
    private fun Double.coord() = String.format(Locale.ROOT, "%.2f", this)
    private fun Float.coord() = String.format(Locale.ROOT, "%.2f", this)

    /**
     * `camera <sub> <UUID> ...` as a single greedy string: the shape every existing script, world function
     * and game test uses. The structured branches above always win when both could apply.
     *
     * `copy` exists only on the client, because the clipboard lives there.
     */
    private fun legacy(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val parts = StringArgumentType.getString(context, "legacy").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return feedback(context, Component.translatable("$KEY.help"))
        return try {
            when (parts[0]) {
                "list" -> respond(context) { cameraList(context) }
                "create" -> {
                    require(parts.size >= 2) { "camera create <name> [true|false]" }
                    val invisible = parts.getOrNull(2)?.let(::flag) ?: true
                    val entity = CameraController.create(
                        source.level, parts[1], Position(source.position.x, source.position.y, source.position.z),
                        Rotation.minecraftDegrees(source.rotation.y, source.rotation.x), invisible,
                    )
                    respond(context) { Component.translatable("$KEY.create.done", parts[1], handle(entity)) }
                }
                else -> {
                    require(parts.size >= 2) { "camera ${parts[0]} <UUID> ..." }
                    val entity = CameraController.find(source.level, parts[1])
                    when (parts[0]) {
                        "remove" -> { CameraController.remove(source.level, uuid(entity)); respond(context) { cameraLine(entity) } }
                        "enabled" -> { entity.enabled = flag(parts[2]); respond(context) { cameraLine(entity) } }
                        "invisible" -> { entity.isInvisible = flag(parts[2]); respond(context) { cameraLine(entity) } }
                        "fov" -> { entity.fov = angle(parts, 2); respond(context) { cameraLine(entity) } }
                        "fps" -> { entity.fps = parts[2].toInt(); respond(context) { cameraLine(entity) } }
                        "pose", "move", "look" -> {
                            val position = if (parts[0] == "look") Position(entity.x, entity.y, entity.z) else Position(number(parts, 2), number(parts, 3), number(parts, 4))
                            val rotation = when (parts[0]) {
                                "pose" -> Rotation.minecraftDegrees(angle(parts, 5), angle(parts, 6), angle(parts, 7))
                                "look" -> Rotation.minecraftDegrees(angle(parts, 2), angle(parts, 3), angle(parts, 4))
                                else -> entity.viewRotation
                            }
                            val at = if (parts[0] == "pose") 8 else 5
                            CameraController.setPose(
                                source.level, uuid(entity), position, rotation,
                                parts.getOrNull(at)?.toInt() ?: 0, parts.getOrNull(at + 1) ?: "linear",
                            )
                            respond(context) { cameraLine(entity) }
                        }
                        "status" -> respond(context) { cameraLine(entity) }
                        "tp" -> {
                            source.playerOrException.teleportTo(entity.x, entity.y, entity.z)
                            respond(context) { Component.translatable("$KEY.tp.done", handle(entity)) }
                        }
                        else -> throw IllegalArgumentException("camera ${parts[0]}: ${Component.translatable("$KEY.help").string}")
                    }
                }
            }
        } catch (failure: IllegalArgumentException) {
            source.sendFailure(reason(failure.message, "differangle.error.invalid")); 0
        } catch (failure: IllegalStateException) {
            source.sendFailure(reason(failure.message, "differangle.error.failed")); 0
        } catch (failure: IndexOutOfBoundsException) {
            source.sendFailure(Component.translatable("$KEY.help")); 0
        }
    }

    /**
     * Flat argument lists behind every `screen` branch.
     *
     * Brigadier consumes the branch literal, so the payload starts at the first argument; [op] restores that
     * token and the rest keeps exactly the shape the screen GUI and world functions submit. `edit` additionally
     * carries the screen UUID and the revision the GUI read, so a stale editor cannot overwrite a newer change.
     */
    private fun handleScreen(context: CommandContext<CommandSourceStack>, op: String): Int {
        val source = context.source
        return try {
            val raw = listOf(op) + StringArgumentType.getString(context, SCREEN_PAYLOAD).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            when (op) {
                "bind", "enabled", "configure", "transform", "status", "mirror", "source", "audio", "playback" -> {
                    val required = when (op) {
                        "bind", "enabled", "mirror" -> 5
                        "configure" -> 9
                        "transform" -> 10
                        "audio" -> 8
                        "playback" -> 7
                        "source" -> -1
                        else -> 4
                    }
                    if (op == "source") require(raw.size in 5..6) { "screen source <x> <y> <z> <camera|bilibili|video|image> [url]" }
                    else require(raw.size == required) { "screen $op" }
                    val pos = BlockPos(raw[1].toInt(), raw[2].toInt(), raw[3].toInt())
                    val entity = CameraController.screen(source.level, pos)
                    val old = entity.config
                    val updated = when (op) {
                        "bind" -> old.copy(cameraUuid = if (raw[4] == "none") null else ScreenConfig.uuid(raw[4]) ?: error("bad camera UUID"))
                        "enabled" -> old.copy(enabled = flag(raw[4]))
                        "mirror" -> old.copy(mirror = flag(raw[4]))
                        "source" -> {
                            val type = MediaSourceType.parse(raw[4])
                            require(raw.size == if (type.isMedia) 6 else 5) {
                                "screen source <x> <y> <z> <camera|bilibili|video|image> [url]"
                            }
                            val url = raw.getOrNull(5) ?: ""
                            old.copy(media = old.media.copy(sourceType = type, sourceUrl = url).anchored(source.level.gameTime))
                        }
                        "audio" -> old.copy(media = old.media.copy(
                            audioEnabled = flag(raw[4]), volume = raw[5].toFloat(),
                            attenuation = AudioAttenuation.parse(raw[6]), audibleDistance = raw[7].toFloat(),
                        ))
                        "playback" -> old.copy(media = old.media.copy(
                            playing = flag(raw[4]), loop = flag(raw[5]), positionSeconds = raw[6].toDouble(),
                        ).anchored(source.level.gameTime))
                        "configure" -> old.copy(width = angle(raw, 4), height = angle(raw, 5), resX = raw[6].toInt(), resY = raw[7].toInt(), fps = raw[8].toInt())
                        "transform" -> old.copy(offsetX = number(raw, 4), offsetY = number(raw, 5), offsetZ = number(raw, 6), yaw = angle(raw, 7), pitch = angle(raw, 8), roll = angle(raw, 9))
                        "status" -> return feedback(context, Component.translatable("differangle.screen.status", entity.screenUuid.toString(), old.toString()))
                        else -> error("screen $op")
                    }
                    CameraController.configure(source.level, pos, updated)
                    feedback(context, Component.translatable("differangle.screen.updated", entity.screenUuid.toString()))
                }
                "edit" -> {
                    require(raw.size >= 6) { "screen edit" }
                    val pos = BlockPos(raw[1].toInt(), raw[2].toInt(), raw[3].toInt())
                    val entity = CameraController.screen(source.level, pos)
                    require(raw[4] == entity.screenUuid.toString() && raw[5].toLong() == entity.revision) { "screen revision" }
                    val p = raw.take(4) + raw.drop(6)
                    require(p.size in setOf(18, 19, 29)) { "screen edit" }
                    val requestedMedia = if (p.size == 29) MediaConfig(
                        MediaSourceType.parse(p[19]), if (p[20] == "none") "" else p[20],
                        flag(p[21]), flag(p[22]), number(p, 23), flag(p[24]), angle(p, 25),
                        AudioAttenuation.parse(p[26]), angle(p, 27), p[28].toInt(),
                    ) else entity.config.media
                    val oldMedia = entity.config.media
                    val timelineChanged = requestedMedia.sourceType != oldMedia.sourceType ||
                        requestedMedia.sourceUrl != oldMedia.sourceUrl || requestedMedia.playing != oldMedia.playing ||
                        requestedMedia.loop != oldMedia.loop || requestedMedia.positionSeconds != oldMedia.positionSeconds
                    val media = if (!timelineChanged) requestedMedia.copy(positionGameTime = oldMedia.positionGameTime)
                    else {
                        // When the editor changed play/loop but left the position field untouched, continue
                        // from the position the authoritative clock has reached instead of jumping backwards.
                        val position = if (requestedMedia.positionSeconds == oldMedia.positionSeconds)
                            oldMedia.positionAt(source.level.gameTime) else requestedMedia.positionSeconds
                        requestedMedia.anchored(source.level.gameTime, position)
                    }
                    val updated = ScreenConfig(
                        if (p[4] == "none") null else ScreenConfig.uuid(p[4]) ?: error("bad camera UUID"),
                        angle(p, 5), angle(p, 6), p[7].toInt(), p[8].toInt(), p[9].toInt(), flag(p[10]),
                        number(p, 11), number(p, 12), number(p, 13), angle(p, 14), angle(p, 15), angle(p, 16), angle(p, 17),
                        p.getOrNull(18)?.let(::flag) ?: entity.config.mirror,
                        media,
                    )
                    CameraController.configure(source.level, pos, updated)
                    feedback(context, Component.translatable("differangle.screen.updated", entity.screenUuid.toString()))
                }
                else -> feedback(context, Component.translatable("differangle.screen.help"))
            }
        } catch (failure: IllegalArgumentException) {
            source.sendFailure(reason(failure.message, "differangle.error.invalid")); 0
        } catch (failure: IllegalStateException) {
            source.sendFailure(reason(failure.message, "differangle.error.failed")); 0
        } catch (failure: IndexOutOfBoundsException) {
            source.sendFailure(Component.translatable("differangle.screen.help")); 0
        }
    }
}
