package net.astrorbits.differangle.client.world

import net.astrorbits.differangle.client.DifferangleClient
import net.astrorbits.differangle.media.AudioAttenuation
import net.astrorbits.differangle.media.MediaConfig
import net.astrorbits.differangle.media.MediaFit
import net.astrorbits.differangle.media.MediaSourceType
import net.astrorbits.differangle.world.ScreenBlockEntity
import net.astrorbits.differangle.world.ScreenConfig
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions

class ScreenEditor(private val entity: ScreenBlockEntity) : Screen(Component.translatable("differangle.screen.editor.title")) {
    private enum class Page(val key: String) { SCREEN("screen"), TRANSFORM("transform"), MEDIA("media") }

    private var page = Page.SCREEN
    private val screenUuid = entity.screenUuid
    private val originalConfig = entity.config
    private var enabled = entity.config.enabled
    private var mirror = entity.config.mirror
    private var sourceType = entity.config.media.sourceType
    private var audioEnabled = entity.config.media.audioEnabled
    private var attenuation = entity.config.media.attenuation
    private var fit = entity.config.media.fit
    private var playButton: Button? = null
    private var restartButton: Button? = null
    private var loopButton: Button? = null
    private var timeline: TimelineSlider? = null
    private var error: Component? = null
    private val values = entity.config.let { c -> mutableMapOf(
        "camera" to (c.cameraUuid?.toString() ?: ""), "width" to c.width.toString(), "height" to c.height.toString(),
        "resX" to c.resX.toString(), "resY" to c.resY.toString(), "fps" to c.fps.toString(), "depth" to c.frameDepth.toString(),
        "x" to c.offsetX.toString(), "y" to c.offsetY.toString(), "z" to c.offsetZ.toString(),
        "yaw" to c.yaw.toString(), "pitch" to c.pitch.toString(), "roll" to c.roll.toString(),
        "url" to c.media.sourceUrl, "volume" to c.media.volume.toString(),
        "distance" to c.media.audibleDistance.toString(), "maxHeight" to c.media.maxVideoHeight.toString()) }
    private val labels = mutableListOf<Triple<Component,Int,Int>>()
    override fun init() {
        labels.clear()
        playButton = null
        restartButton = null
        loopButton = null
        timeline = null
        val left = width / 2 - 150
        fun field(key: String, x: Int, y: Int, w: Int, maxLength: Int = 64) {
            val label = label(key)
            labels.add(Triple(label, x, y))
            addRenderableWidget(EditBox(font, x, y + 11, w, 18, label).also {
                it.setMaxLength(maxLength)
                it.value = values.getValue(key)
                it.setResponder { text -> values[key] = text }
            })
        }
        Page.entries.forEachIndexed { index, tab ->
            addRenderableWidget(Button.builder(Component.translatable("differangle.screen.editor.page.${tab.key}")) {
                page = tab
                rebuildWidgets()
            }.bounds(left + index * 102, 23, 96, 20).build()).active = page != tab
        }
        when (page) {
            Page.SCREEN -> {
                field("camera", left, 50, 300)
                listOf("width", "height", "resX", "resY", "fps", "depth").forEachIndexed { index, key ->
                    field(key, left + (index % 2) * 154, 84 + (index / 2) * 35, 146)
                }
            }
            Page.TRANSFORM -> {
                listOf("x", "y", "z").forEachIndexed { index, key -> field(key, left + index * 102, 50, 96) }
                listOf("pitch", "yaw", "roll").forEachIndexed { index, key -> field(key, left + index * 102, 85, 96) }
            }
            Page.MEDIA -> {
                field("url", left, 50, 300, MediaConfig.MAX_URL_LENGTH)
                addRenderableWidget(Button.builder(mediaLabel("source", sourceType.serializedName)) {
                    sourceType = MediaSourceType.entries[(sourceType.ordinal + 1) % MediaSourceType.entries.size]
                    it.message = mediaLabel("source", sourceType.serializedName)
                }.bounds(left, 83, 96, 20).build())
                addRenderableWidget(Button.builder(mediaLabel("audio", audioEnabled)) {
                    audioEnabled = !audioEnabled
                    it.message = mediaLabel("audio", audioEnabled)
                }.bounds(left + 102, 83, 96, 20).build())
                addRenderableWidget(Button.builder(mediaLabel("attenuation", attenuation.serializedName)) {
                    attenuation = if (attenuation == AudioAttenuation.LINEAR) AudioAttenuation.NONE else AudioAttenuation.LINEAR
                    it.message = mediaLabel("attenuation", attenuation.serializedName)
                }.bounds(left + 204, 83, 96, 20).build())
                listOf("volume", "distance", "maxHeight").forEachIndexed { index, key ->
                    field(key, left + index * 102, 108, 96)
                }
                playButton = addRenderableWidget(Button.builder(playbackLabel("play")) {
                    control(if (sessionEnded() && !entity.config.media.finished) "restart" else "toggle")
                }.bounds(left, 143, 96, 20).build())
                restartButton = addRenderableWidget(Button.builder(playbackLabel("restart")) {
                    control("restart")
                }.bounds(left + 102, 143, 96, 20).build())
                loopButton = addRenderableWidget(Button.builder(playbackLabel("loop.off")) {
                    control("loop")
                }.bounds(left + 204, 143, 96, 20).build())
                timeline = addRenderableWidget(TimelineSlider(left, 167))
                tick()
            }
        }
        addRenderableWidget(Button.builder(enabledLabel()) {
            enabled = !enabled
            it.message = enabledLabel()
        }.bounds(left, 198, 96, 20).build())
        if (page == Page.SCREEN) {
            addRenderableWidget(Button.builder(Component.translatable("differangle.screen.editor.mirror", mirror)) {
                mirror = !mirror
                it.message = Component.translatable("differangle.screen.editor.mirror", mirror)
            }.bounds(left + 102, 198, 96, 20).build())
        }
        if (page == Page.MEDIA) {
            addRenderableWidget(Button.builder(fitLabel()) {
                fit = if (fit == MediaFit.STRETCH) MediaFit.FIT else MediaFit.STRETCH
                it.message = fitLabel()
            }.bounds(left + 102, 198, 96, 20).build()).active = canEdit()
        }
        addRenderableWidget(Button.builder(Component.translatable("differangle.screen.editor.apply")) { apply() }
            .bounds(left + 204, 198, 96, 20).build()).active = canEdit()
        if (!canEdit()) error = Component.translatable("differangle.screen.editor.readonly")
    }

    private fun canEdit() = minecraft.player?.permissions()?.hasPermission(Permissions.COMMANDS_GAMEMASTER) == true
    private fun session() = DifferangleClient.runtime.mediaSession(screenUuid.toString())
    private fun sessionEnded() = entity.config.media.finished || session()?.playbackEnded == true
    private fun control(action: String) {
        if (entity.screenUuid == screenUuid && canEdit()) WorldClient.sendMediaControl(minecraft, entity, action)
    }

    override fun tick() {
        val slider = timeline ?: return
        val media = entity.config.media
        val sourceMatches = sourceType == media.sourceType && values.getValue("url").trim() == media.sourceUrl
        val canControl = canEdit() && !entity.isRemoved && entity.screenUuid == screenUuid &&
            !entity.config.mirror && sourceMatches && media.sourceType.isVideo
        val ended = sessionEnded()
        playButton?.active = canControl
        restartButton?.active = canControl
        loopButton?.active = canControl
        playButton?.message = playbackLabel(when {
            ended -> "replay"
            media.playing -> "pause"
            else -> "play"
        })
        loopButton?.message = playbackLabel(if (media.loop) "loop.on" else "loop.off")
        val durationMillis = media.durationMillis.takeIf { it > 0L } ?: session()?.playbackDurationMillis ?: 0L
        slider.active = canControl && durationMillis > 0L
        if (!sourceMatches || entity.config.mirror || !media.sourceType.isVideo) {
            slider.refresh(0.0, 0.0)
            return
        }
        val duration = durationMillis / 1000.0
        val now = minecraft.level?.gameTime ?: media.positionGameTime
        val progress = if (ended && duration > 0.0) duration else media.playbackPositionAt(now)
        val shownPosition = if (duration > 0.0 && media.durationMillis == 0L && !ended && media.loop) progress % duration else progress
        slider.refresh(shownPosition, duration)
    }

    private inner class TimelineSlider(x: Int, y: Int) : AbstractSliderButton(x, y, 300, 20, Component.empty(), 0.0) {
        private var duration = 0.0
        private var position = 0.0
        private var dragging = false
        private var seekRevision = -1L
        private var previewTicks = 0

        fun refresh(position: Double, duration: Double) {
            if (dragging) return
            if (active && entity.revision == seekRevision && previewTicks > 0) {
                previewTicks--
                return
            }
            this.duration = duration
            this.position = if (duration > 0.0) position.coerceAtMost(duration) else position
            value = if (duration > 0.0) this.position / duration else 0.0
            updateMessage()
        }

        override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
            dragging = true
            super.onClick(event, doubleClick)
        }

        override fun onRelease(event: MouseButtonEvent) {
            dragging = false
            if (active) applyValue()
            super.onRelease(event)
        }

        override fun applyValue() {
            position = value * duration
            if (!dragging && active) {
                seekRevision = entity.revision
                previewTicks = 20
                control("seek $position")
            }
        }

        override fun updateMessage() {
            message = Component.literal("${formatTime(position)} / ${if (duration > 0.0) formatTime(duration) else "--:--"}")
        }
    }

    private fun formatTime(seconds: Double): String {
        val whole = seconds.toLong()
        return "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
    }

    private fun playbackLabel(key: String) = Component.translatable("differangle.screen.editor.playback.$key")
    private fun fitLabel() = Component.translatable("differangle.screen.editor.fit.${fit.serializedName}")

    private fun apply() {
        try {
            check(minecraft.level === entity.level && !entity.isRemoved) { "differangle.error.screen.unloaded" }
            val current = entity.config
            val playback = current.media
            val originalMedia = originalConfig.media
            val comparableMedia = playback.copy(
                playing = originalMedia.playing, loop = originalMedia.loop, positionSeconds = originalMedia.positionSeconds,
                positionGameTime = originalMedia.positionGameTime, durationMillis = originalMedia.durationMillis,
            )
            if (entity.screenUuid != screenUuid || current.copy(media = comparableMedia) != originalConfig) {
                error = Component.translatable("differangle.screen.editor.error.changed")
                return
            }
            fun f(k: String) = values.getValue(k).toFloat()
            fun d(k: String) = values.getValue(k).toDouble()
            fun i(k: String) = values.getValue(k).toInt()
            val binding = values.getValue("camera").trim()
            val media = playback.copy(
                sourceType = sourceType, sourceUrl = if (sourceType == MediaSourceType.CAMERA) "" else values.getValue("url").trim(),
                audioEnabled = audioEnabled, volume = f("volume"), attenuation = attenuation,
                audibleDistance = f("distance"), maxVideoHeight = i("maxHeight"), fit = fit,
            )
            val config = ScreenConfig(
                if (binding.isEmpty()) null else ScreenConfig.uuid(binding) ?: throw IllegalArgumentException("differangle.error.uuid"),
                f("width"),f("height"),i("resX"),i("resY"),i("fps"),enabled,d("x"),d("y"),d("z"),f("yaw"),f("pitch"),f("roll"),f("depth"),mirror,
                media)
            val p = entity.blockPos
            val args = listOf(config.cameraUuid ?: "none",config.width,config.height,config.resX,config.resY,config.fps,config.enabled,
                config.offsetX,config.offsetY,config.offsetZ,config.yaw,config.pitch,config.roll,config.frameDepth,config.mirror,
                media.sourceType.serializedName, media.sourceUrl.ifBlank { "none" }, media.playing, media.loop, media.positionSeconds,
                media.audioEnabled, media.volume, media.attenuation.serializedName, media.audibleDistance, media.maxVideoHeight,
                media.fit.serializedName).joinToString(" ")
            minecraft.connection?.sendCommand("differangle screen edit ${p.x} ${p.y} ${p.z} $screenUuid ${entity.revision} $args")
            onClose()
        } catch (_: NumberFormatException) { error = Component.translatable("differangle.screen.editor.error.number") }
        catch (_: IllegalArgumentException) { error = Component.translatable("differangle.screen.editor.error.range") }
        catch (e: IllegalStateException) { error = reason(e.message) }
    }
    override fun extractRenderState(graphics: GuiGraphicsExtractor,mouseX: Int,mouseY: Int,partial: Float) {
        graphics.fill(0,0,width,height,0xDD101418.toInt())
        graphics.text(font, title, (width - font.width(title)) / 2, 8, 0xFFFFFFFF.toInt())
        labels.forEach { (text,x,y) -> graphics.text(font,text,x,y,0xFFD4DADF.toInt()) }
        super.extractRenderState(graphics,mouseX,mouseY,partial)
        error?.let { graphics.text(font, it, width / 2 - 150, 224, 0xFFFF7777.toInt()) }
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
