package net.astrorbits.differangle.client.world

import net.astrorbits.differangle.world.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Screen-base settings, opened by right-clicking a base.
 *
 * Every label and message is a translation key (`differangle.screen.editor.*`), so the editor follows the
 * player's language like the commands do. `apply()` only validates locally and hands the whole
 * configuration to the server through the same `differangle screen edit` command the command layer uses,
 * which keeps the authoritative checks in one place.
 */
class ScreenEditor(private val entity: ScreenBlockEntity) : Screen(Component.translatable("differangle.screen.editor.title")) {
    private var page = 0
    private var enabled = entity.config.enabled
    private var error: Component? = null
    private val values = entity.config.let { c -> mutableMapOf(
        "camera" to (c.cameraUuid?.toString() ?: ""), "width" to c.width.toString(), "height" to c.height.toString(),
        "resX" to c.resX.toString(), "resY" to c.resY.toString(), "fps" to c.fps.toString(), "depth" to c.frameDepth.toString(),
        "x" to c.offsetX.toString(), "y" to c.offsetY.toString(), "z" to c.offsetZ.toString(),
        "yaw" to c.yaw.toString(), "pitch" to c.pitch.toString(), "roll" to c.roll.toString()) }
    private val boxes = mutableMapOf<String,EditBox>()
    private val labels = mutableListOf<Triple<Component,Int,Int>>()
    override fun init() {
        boxes.clear(); labels.clear()
        val left = width/2-150
        fun field(key: String, label: Component, x: Int, y: Int, w: Int) {
            labels.add(Triple(label,x,y))
            boxes[key] = addRenderableWidget(EditBox(font,x,y+11,w,18,label).also {
                it.setMaxLength(64); it.value = values.getValue(key)
                it.setResponder { text -> values[key] = text }
            })
        }
        field("camera",label("camera"),left,27,300)
        val fields = if (page == 0) listOf("width","height","resX","resY","fps","depth")
            else listOf("x","y","z","yaw","pitch","roll")
        fields.forEachIndexed { index, key -> field(key,label(key),left+(index%2)*154,64+(index/2)*35,146) }
        addRenderableWidget(Button.builder(enabledLabel()) {
            enabled = !enabled
            it.message = enabledLabel()
        }.bounds(left,174,96,20).build())
        addRenderableWidget(Button.builder(Component.translatable(if (page == 0) "differangle.screen.editor.page.transform" else "differangle.screen.editor.page.screen")) {
            page = 1-page; rebuildWidgets()
        }.bounds(left+102,174,96,20).build())
        val canEdit = minecraft.player?.permissions()?.hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) == true
        addRenderableWidget(Button.builder(Component.translatable("differangle.screen.editor.apply")) { apply() }.bounds(left+204,174,96,20).build()).active = canEdit
        if (!canEdit) error = Component.translatable("differangle.screen.editor.readonly")
    }
    private fun apply() {
        try {
            fun f(k: String) = values.getValue(k).toFloat()
            fun d(k: String) = values.getValue(k).toDouble()
            fun i(k: String) = values.getValue(k).toInt()
            val binding = values.getValue("camera").trim()
            val config = ScreenConfig(
                if (binding.isEmpty()) null else ScreenConfig.uuid(binding) ?: throw IllegalArgumentException("differangle.error.uuid"),
                f("width"),f("height"),i("resX"),i("resY"),i("fps"),enabled,d("x"),d("y"),d("z"),f("yaw"),f("pitch"),f("roll"),f("depth"))
            check(minecraft.level === entity.level && !entity.isRemoved) { "differangle.error.screen.unloaded" }
            val p = entity.blockPos
            val args = listOf(config.cameraUuid ?: "none",config.width,config.height,config.resX,config.resY,config.fps,config.enabled,
                config.offsetX,config.offsetY,config.offsetZ,config.yaw,config.pitch,config.roll,config.frameDepth).joinToString(" ")
            minecraft.connection?.sendCommand("differangle screen edit ${p.x} ${p.y} ${p.z} ${entity.screenUuid} ${entity.revision} $args")
            onClose()
        } catch (_: NumberFormatException) { error = Component.translatable("differangle.screen.editor.error.number") }
        catch (_: IllegalArgumentException) { error = Component.translatable("differangle.screen.editor.error.range") }
        catch (e: IllegalStateException) { error = reason(e.message) }
    }
    override fun extractRenderState(graphics: GuiGraphicsExtractor,mouseX: Int,mouseY: Int,partial: Float) {
        graphics.fill(0,0,width,height,0xDD101418.toInt())
        graphics.text(font,title,width/2-35,10,0xFFFFFFFF.toInt())
        labels.forEach { (text,x,y) -> graphics.text(font,text,x,y,0xFFD4DADF.toInt()) }
        super.extractRenderState(graphics,mouseX,mouseY,partial)
        error?.let { graphics.text(font,it,width/2-150,201,0xFFFF7777.toInt()) }
    }
    override fun isPauseScreen() = false

    private fun enabledLabel() = Component.translatable(if (enabled) "differangle.screen.editor.enabled.on" else "differangle.screen.editor.enabled.off")

    /** Controller and config checks throw translation keys; anything else is shown verbatim. */
    private fun reason(message: String?): Component =
        if (message == null) Component.translatable("differangle.error.failed")
        else if (message.startsWith("differangle.")) Component.translatable(message)
        else Component.literal(message)

    companion object {
        /** The label component of one field, so the editor and its tests agree on a single source. */
        fun label(key: String): Component = Component.translatable("differangle.screen.editor.field.$key")
    }
}
