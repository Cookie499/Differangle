package net.astrorbits.differangle.client.world

import net.astrorbits.differangle.world.*
import net.astrorbits.differangle.media.AudioAttenuation
import net.astrorbits.differangle.media.MediaConfig
import net.astrorbits.differangle.media.MediaSourceType
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
    private var mirror = entity.config.mirror
    private var sourceType = entity.config.media.sourceType
    private var audioEnabled = entity.config.media.audioEnabled
    private var attenuation = entity.config.media.attenuation
    private var playing = entity.config.media.playing
    private var loop = entity.config.media.loop
    private var error: Component? = null
    private val values = entity.config.let { c -> mutableMapOf(
        "camera" to (c.cameraUuid?.toString() ?: ""), "width" to c.width.toString(), "height" to c.height.toString(),
        "resX" to c.resX.toString(), "resY" to c.resY.toString(), "fps" to c.fps.toString(), "depth" to c.frameDepth.toString(),
        "x" to c.offsetX.toString(), "y" to c.offsetY.toString(), "z" to c.offsetZ.toString(),
        "yaw" to c.yaw.toString(), "pitch" to c.pitch.toString(), "roll" to c.roll.toString(),
        "url" to c.media.sourceUrl, "position" to c.media.positionSeconds.toString(), "volume" to c.media.volume.toString(),
        "distance" to c.media.audibleDistance.toString(), "maxHeight" to c.media.maxVideoHeight.toString()) }
    private val boxes = mutableMapOf<String,EditBox>()
    private val labels = mutableListOf<Triple<Component,Int,Int>>()
    override fun init() {
        boxes.clear(); labels.clear()
        val left = width/2-150
        fun field(key: String, label: Component, x: Int, y: Int, w: Int, maxLength: Int = 64) {
            labels.add(Triple(label,x,y))
            boxes[key] = addRenderableWidget(EditBox(font,x,y+11,w,18,label).also {
                it.setMaxLength(maxLength); it.value = values.getValue(key)
                it.setResponder { text -> values[key] = text }
            })
        }
        if (page < 2) {
            field("camera",label("camera"),left,27,198)
            addRenderableWidget(Button.builder(Component.translatable("differangle.screen.editor.mirror", mirror)) {
                mirror = !mirror
                it.message = Component.translatable("differangle.screen.editor.mirror", mirror)
            }.bounds(left+204,38,96,20).build())
            val fields = if (page == 0) listOf("width","height","resX","resY","fps","depth")
                else listOf("x","y","z","yaw","pitch","roll")
            fields.forEachIndexed { index, key -> field(key,label(key),left+(index%2)*154,64+(index/2)*35,146) }
        } else {
            field("url", label("url"), left, 22, 300, MediaConfig.MAX_URL_LENGTH)
            addRenderableWidget(Button.builder(mediaLabel("source", sourceType.serializedName)) {
                sourceType = MediaSourceType.entries[(sourceType.ordinal + 1) % MediaSourceType.entries.size]
                it.message = mediaLabel("source", sourceType.serializedName)
            }.bounds(left,62,146,20).build())
            addRenderableWidget(Button.builder(mediaLabel("audio", audioEnabled)) {
                audioEnabled = !audioEnabled; it.message = mediaLabel("audio", audioEnabled)
            }.bounds(left+154,62,146,20).build())
            addRenderableWidget(Button.builder(mediaLabel("playing", playing)) {
                playing = !playing; it.message = mediaLabel("playing", playing)
            }.bounds(left,87,95,20).build())
            addRenderableWidget(Button.builder(mediaLabel("loop", loop)) {
                loop = !loop; it.message = mediaLabel("loop", loop)
            }.bounds(left+102,87,95,20).build())
            addRenderableWidget(Button.builder(mediaLabel("attenuation", attenuation.serializedName)) {
                attenuation = if (attenuation == AudioAttenuation.LINEAR) AudioAttenuation.NONE else AudioAttenuation.LINEAR
                it.message = mediaLabel("attenuation", attenuation.serializedName)
            }.bounds(left+204,87,96,20).build())
            listOf("volume","distance","position","maxHeight").forEachIndexed { index, key ->
                field(key,label(key),left+(index%2)*154,112+(index/2)*35,146)
            }
        }
        addRenderableWidget(Button.builder(enabledLabel()) {
            enabled = !enabled
            it.message = enabledLabel()
        }.bounds(left,174,96,20).build())
        addRenderableWidget(Button.builder(Component.translatable(when (page) {
            0 -> "differangle.screen.editor.page.transform"
            1 -> "differangle.screen.editor.page.media"
            else -> "differangle.screen.editor.page.screen"
        })) {
            page = (page+1)%3; rebuildWidgets()
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
            val media = MediaConfig(
                sourceType, if (sourceType == MediaSourceType.CAMERA) "" else values.getValue("url").trim(),
                playing, loop, d("position"), audioEnabled, f("volume"), attenuation, f("distance"), i("maxHeight"),
            )
            val config = ScreenConfig(
                if (binding.isEmpty()) null else ScreenConfig.uuid(binding) ?: throw IllegalArgumentException("differangle.error.uuid"),
                f("width"),f("height"),i("resX"),i("resY"),i("fps"),enabled,d("x"),d("y"),d("z"),f("yaw"),f("pitch"),f("roll"),f("depth"),mirror,
                media)
            check(minecraft.level === entity.level && !entity.isRemoved) { "differangle.error.screen.unloaded" }
            val p = entity.blockPos
            val args = listOf(config.cameraUuid ?: "none",config.width,config.height,config.resX,config.resY,config.fps,config.enabled,
                config.offsetX,config.offsetY,config.offsetZ,config.yaw,config.pitch,config.roll,config.frameDepth,config.mirror,
                media.sourceType.serializedName, media.sourceUrl.ifBlank { "none" }, media.playing, media.loop, media.positionSeconds,
                media.audioEnabled, media.volume, media.attenuation.serializedName, media.audibleDistance, media.maxVideoHeight).joinToString(" ")
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
        fun mediaLabel(key: String, value: Any): Component = Component.translatable("differangle.screen.editor.media.$key", value)
    }
}
