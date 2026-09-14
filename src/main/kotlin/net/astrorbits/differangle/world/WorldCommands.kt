package net.astrorbits.differangle.world

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import net.astrorbits.differangle.camera.*

object WorldCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            val root = Commands.literal("differangle").requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                .executes { it.source.sendSuccess({ Component.literal(HELP) },false); 1 }
            for (kind in listOf("camera","screen")) root.then(Commands.literal(kind)
                .then(Commands.argument("options",StringArgumentType.greedyString()).executes { ctx ->
                    try {
                        val parts = StringArgumentType.getString(ctx,"options").trim().split(Regex("\\s+"))
                        val message = if (kind == "camera") camera(ctx.source,parts) else screen(ctx.source,parts)
                        ctx.source.sendSuccess({ Component.literal(message) },false)
                        1
                    } catch (ex: IllegalArgumentException) { ctx.source.sendFailure(Component.literal(ex.message ?: "参数无效")); 0 }
                    catch (ex: IllegalStateException) { ctx.source.sendFailure(Component.literal(ex.message ?: "操作失败")); 0 }
                    catch (_: IndexOutOfBoundsException) { ctx.source.sendFailure(Component.literal(HELP)); 0 }
                }))
            dispatcher.register(root)
        }
    }
    private fun number(p: List<String>, index: Int) = p[index].toDouble().also { require(it.isFinite()) }
    private fun angle(p: List<String>, index: Int) = number(p,index).toFloat().also { require(it.isFinite()) }
    private fun bool(value: String) = value.toBooleanStrict()
    private fun camera(source: CommandSourceStack, p: List<String>): String {
        val level = source.level
        if (p[0] == "list") return level.allEntities.filterIsInstance<CameraEntity>().joinToString("\n") { "${it.customName?.string}: ${it.uuid} enabled=${it.enabled}" }
        if (p[0] == "create") {
            val player = source.playerOrException
            val eye = player.eyePosition
            return CameraController.create(level,p[1],Position(eye.x,eye.y,eye.z),Rotation.minecraftDegrees(player.yRot,player.xRot),p.getOrNull(2)?.let(::bool) ?: true).uuid.toString()
        }
        val entity = CameraController.find(level,p[1])
        when(p[0]) {
            "remove" -> CameraController.remove(level,p[1])
            "enabled" -> CameraController.setEnabled(level,p[1],bool(p[2]))
            "invisible" -> CameraController.setInvisible(level,p[1],bool(p[2]))
            "fov" -> entity.fov = angle(p,2)
            "fps" -> entity.fps = p[2].toInt()
            "pose", "move", "look" -> {
                val position = if (p[0] == "look") Position(entity.x,entity.y,entity.z) else Position(number(p,2),number(p,3),number(p,4))
                val rotation = when(p[0]) {
                    "pose" -> Rotation.minecraftDegrees(angle(p,5),angle(p,6),angle(p,7))
                    "look" -> Rotation.minecraftDegrees(angle(p,2),angle(p,3),angle(p,4))
                    else -> entity.viewRotation
                }
                val at = if (p[0] == "pose") 8 else 5
                CameraController.setPose(level,p[1],position,rotation,p.getOrNull(at)?.toInt() ?: 0,p.getOrNull(at+1) ?: "linear")
            }
            "status" -> return "${entity.uuid}: ${entity.x}, ${entity.y}, ${entity.z}; FOV=${entity.fov}; FPS=${entity.fps}; enabled=${entity.enabled}"
            else -> error(HELP)
        }
        return "摄像机已更新：${entity.uuid}"
    }
    private fun screen(source: CommandSourceStack,raw: List<String>): String {
        val pos = BlockPos(raw[1].toInt(),raw[2].toInt(),raw[3].toInt())
        val entity = CameraController.screen(source.level,pos)
        val p = if (raw[0] == "edit") {
            require(raw[4] == entity.screenUuid.toString() && raw[5].toLong() == entity.revision) { "显示屏已被替换或修改，请重新打开设置" }
            raw.take(4) + raw.drop(6)
        } else raw
        val old = entity.config
        val updated = when(p[0]) {
            "bind" -> old.copy(cameraUuid = if (p[4] == "none") null else ScreenConfig.uuid(p[4]) ?: error("无效的 Camera UUID"))
            "enabled" -> old.copy(enabled = bool(p[4]))
            "configure" -> old.copy(width=angle(p,4),height=angle(p,5),resX=p[6].toInt(),resY=p[7].toInt(),fps=p[8].toInt())
            "transform" -> old.copy(offsetX=number(p,4),offsetY=number(p,5),offsetZ=number(p,6),yaw=angle(p,7),pitch=angle(p,8),roll=angle(p,9))
            "edit" -> ScreenConfig(if (p[4] == "none") null else ScreenConfig.uuid(p[4]) ?: error("无效的 Camera UUID"),
                angle(p,5),angle(p,6),p[7].toInt(),p[8].toInt(),p[9].toInt(),bool(p[10]),
                number(p,11),number(p,12),number(p,13),angle(p,14),angle(p,15),angle(p,16),angle(p,17))
            "status" -> return "Screen ${entity.screenUuid}: $old"
            else -> error(HELP)
        }
        CameraController.configure(source.level,pos,updated)
        return "显示屏已更新：${entity.screenUuid}"
    }
    private const val HELP = "/differangle camera create <name> [invisible] | list | remove/enabled/invisible/fov/fps/status <UUID或名称> ...\n" +
        "/differangle camera pose <id> <x y z yaw pitch roll> [ticks] [step|linear|smoothstep]\n" +
        "/differangle camera move/look <id> <x y z或yaw pitch roll> [ticks] [easing]\n" +
        "/differangle screen bind <x y z> <camera UUID|none> | configure <x y z> <width height resX resY fps> | transform <x y z> <offsetX offsetY offsetZ yaw pitch roll> | enabled <x y z> <true|false> | status <x y z>"
}
