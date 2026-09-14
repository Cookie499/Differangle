package net.astrorbits.differangle.client.world

import net.astrorbits.differangle.world.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ScreenEditor(private val entity: ScreenBlockEntity) : Screen(Component.literal("显示屏设置")) {
    private var page = 0
    private var enabled = entity.config.enabled
    private var error = ""
    private val values = entity.config.let { c -> mutableMapOf(
        "camera" to (c.cameraUuid?.toString() ?: ""), "width" to c.width.toString(), "height" to c.height.toString(),
        "resX" to c.resX.toString(), "resY" to c.resY.toString(), "fps" to c.fps.toString(), "depth" to c.frameDepth.toString(),
        "x" to c.offsetX.toString(), "y" to c.offsetY.toString(), "z" to c.offsetZ.toString(),
        "yaw" to c.yaw.toString(), "pitch" to c.pitch.toString(), "roll" to c.roll.toString()) }
    private val boxes = mutableMapOf<String,EditBox>()
    private val labels = mutableListOf<Triple<String,Int,Int>>()
    override fun init() {
        boxes.clear(); labels.clear()
        val left = width/2-150
        fun field(key: String, label: String, x: Int, y: Int, w: Int) {
            labels.add(Triple(label,x,y))
            boxes[key] = addRenderableWidget(EditBox(font,x,y+11,w,18,Component.literal(label)).also {
                it.setMaxLength(64); it.value = values.getValue(key)
                it.setResponder { text -> values[key] = text }
            })
        }
        field("camera","摄像机 UUID（留空解绑）",left,27,300)
        val fields = if (page == 0) listOf("width" to "宽度（格）","height" to "高度（格）","resX" to "分辨率宽","resY" to "分辨率高","fps" to "刷新上限 FPS","depth" to "边框厚度")
            else listOf("x" to "局部偏移 X","y" to "局部偏移 Y","z" to "局部偏移 Z","yaw" to "局部 Y 旋转（度）","pitch" to "局部 X 旋转（度）","roll" to "局部 Z 旋转（度）")
        fields.forEachIndexed { index, (key,label) -> field(key,label,left+(index%2)*154,64+(index/2)*35,146) }
        addRenderableWidget(Button.builder(Component.literal(if(enabled) "屏幕：开" else "屏幕：关")) {
            enabled = !enabled; it.message = Component.literal(if(enabled) "屏幕：开" else "屏幕：关")
        }.bounds(left,174,96,20).build())
        addRenderableWidget(Button.builder(Component.literal(if(page==0) "位置与旋转" else "尺寸与画面")) {
            page=1-page; rebuildWidgets()
        }.bounds(left+102,174,96,20).build())
        val canEdit = minecraft.player?.permissions()?.hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) == true
        addRenderableWidget(Button.builder(Component.literal("应用")) { apply() }.bounds(left+204,174,96,20).build()).active = canEdit
        if (!canEdit) error = "只读：需要管理员权限才能修改"
    }
    private fun apply() {
        try {
            fun f(k: String) = values.getValue(k).toFloat()
            fun d(k: String) = values.getValue(k).toDouble()
            fun i(k: String) = values.getValue(k).toInt()
            val binding = values.getValue("camera").trim()
            val config = ScreenConfig(if(binding.isEmpty()) null else ScreenConfig.uuid(binding) ?: error("UUID 格式无效"),
                f("width"),f("height"),i("resX"),i("resY"),i("fps"),enabled,d("x"),d("y"),d("z"),f("yaw"),f("pitch"),f("roll"),f("depth"))
            check(minecraft.level === entity.level && !entity.isRemoved) { "显示屏已卸载" }
            val p = entity.blockPos
            val args = listOf(config.cameraUuid ?: "none",config.width,config.height,config.resX,config.resY,config.fps,config.enabled,
                config.offsetX,config.offsetY,config.offsetZ,config.yaw,config.pitch,config.roll,config.frameDepth).joinToString(" ")
            minecraft.connection?.sendCommand("differangle screen edit ${p.x} ${p.y} ${p.z} ${entity.screenUuid} ${entity.revision} $args")
            onClose()
        } catch (_: NumberFormatException) { error="请输入有效数值" }
        catch (e: IllegalArgumentException) { error="数值超出范围：尺寸 0.125–64，分辨率 16–2048，FPS 1–240" }
        catch (e: IllegalStateException) { error=e.message ?: "无法保存" }
    }
    override fun extractRenderState(graphics: GuiGraphicsExtractor,mouseX: Int,mouseY: Int,partial: Float) {
        graphics.fill(0,0,width,height,0xDD101418.toInt())
        graphics.text(font,title,width/2-35,10,0xFFFFFFFF.toInt())
        labels.forEach { (text,x,y) -> graphics.text(font,text,x,y,0xFFD4DADF.toInt()) }
        super.extractRenderState(graphics,mouseX,mouseY,partial)
        if(error.isNotEmpty()) graphics.text(font,error,width/2-150,201,0xFFFF7777.toInt())
    }
    override fun isPauseScreen() = false
}
