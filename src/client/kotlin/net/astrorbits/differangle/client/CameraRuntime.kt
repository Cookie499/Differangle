package net.astrorbits.differangle.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.render.CameraWorldRenderer
import net.astrorbits.differangle.client.render.CameraEnvironmentSampler
import net.astrorbits.differangle.client.render.EmbeddedNativePipelines
import net.astrorbits.differangle.client.render.PreparedCameraView
import net.astrorbits.differangle.client.render.TextureCameraBackend
import net.astrorbits.differangle.client.render.compat.IrisCameraScope
import net.astrorbits.differangle.client.media.ClientMediaBackend
import net.astrorbits.differangle.client.media.TextureMediaSession
import net.astrorbits.differangle.media.MediaConfig
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.media.MediaRuntime
import net.astrorbits.differangle.media.MediaSourceType
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import org.joml.Vector3f
import org.slf4j.LoggerFactory

/** Owns one scene for the current client world, shared by both rendering algorithms. */
class CameraRuntime : AutoCloseable {
    private val config = ClientConfig()
    val system = CameraSystem(TextureCameraBackend(::drawTexture, ::drawSurface))
    val layers = CameraLayers()

    fun setLayer(layer: CameraLayer, enabled: Boolean) {
        layers.set(layer, enabled)
        reloadResources()
    }
    var mode = config.loadMode()
        private set
    var cameraShaders = config.loadCameraShaders()
        private set

    fun setCameraShaderRendering(enabled: Boolean) {
        RenderSystem.assertOnRenderThread()
        config.saveCameraShaders(enabled)
        cameraShaders = enabled
        reloadResources()
    }
    var lastError: String? = null
        private set

    /** The same failure as [lastError], kept as a component so the status line can stay translatable. */
    var lastFailure: Component? = null
        private set
    var statistics = FrameStatistics(0, 0, 0)
        private set

    /**
     * Last frame's native feature counts, snapshot out of the renderer.
     *
     * The command layer renders them through `differangle.status.content`, so the numbers stay
     * translatable and tests can assert on the values instead of on formatted text.
     */
    var nativeFeatures = NativeFeatureTotals()
        private set
    var sectionCount = 0
        private set
    var drawCalls = 0
        private set
    var cpuMillis = 0.0
        private set
    private var world: ClientLevel? = null
    private var renderer: CameraWorldRenderer? = null
    private val environments = CameraEnvironmentSampler()
    private var context: LevelRenderContext? = null
    private val prepared = mutableMapOf<Pair<String, Resolution>, PreparedCameraView>()
    private data class MirrorFrame(val target: TextureTarget, var definition: CameraDefinition, var renderedAt: Long = 0)
    private val mirrors = mutableMapOf<String, MirrorFrame>()
    private var media = MediaRuntime(ClientMediaBackend())
    private var deferredContext: LevelRenderContext? = null
    private val logger = LoggerFactory.getLogger("Differangle")

    fun syncWorld(client: Minecraft) {
        if (world !== client.level) {
            clear()
            world = client.level
        }
    }

    fun tick(client: Minecraft) {
        syncWorld(client)
        net.astrorbits.differangle.client.world.WorldClient.tick(client, this)
        if (mirrors.isNotEmpty() && system.screens().none { it.mirror && it.enabled }) reloadResources()
        world?.let { environments.tick(it, system.cameras()) }
    }

    fun syncMedia(requested: Map<String, MediaRequest>) {
        media.sync(requested.filterValues {
            it.config.sourceType in setOf(MediaSourceType.IMAGE, MediaSourceType.VIDEO, MediaSourceType.BILIBILI)
        })
        media.tick()
    }

    fun switchMode(value: CameraMode) {
        RenderSystem.assertOnRenderThread()
        config.saveMode(value)
        reloadResources()
        mode = value
    }

    /** F3+T and mode switches release resources while preserving Camera/Screen definitions. */
    fun reloadResources() {
        deferredContext = null
        system.invalidateFrames()
        mirrors.values.forEach { it.target.destroyBuffers() }
        mirrors.clear()
        media.close()
        media = MediaRuntime(ClientMediaBackend())
        renderer?.close()
        renderer = null
        prepared.clear()
        environments.clear()
        statistics = FrameStatistics(0, 0, 0)
        sectionCount = 0; drawCalls = 0; cpuMillis = 0.0
        lastError = null
        lastFailure = null
        nativeFeatures = NativeFeatureTotals()
    }

    fun clear() {
        system.clear()
        reloadResources()
    }

    /** Translatable backend mismatch, rendered into the status line and the pause notice. */
    fun compatibilityProblem(): Component? {
        val loader = FabricLoader.getInstance()
        for ((id, series) in listOf("sodium" to "0.9.", "iris" to "1.11.")) {
            val installed = loader.getModContainer(id).orElse(null) ?: continue
            val version = installed.metadata.version.friendlyString
            if (!version.startsWith(series)) return Component.translatable("differangle.compat.backend", id, series, version)
        }
        return null
    }

    fun render(renderContext: LevelRenderContext) {
        if (FabricLoader.getInstance().isModLoaded("iris") && IrisCameraScope.active()) {
            if (!IrisCameraScope.shadowPass()) deferredContext = renderContext
            return
        }
        renderNow(renderContext)
    }

    fun renderAfterLevel() {
        val ctx = deferredContext ?: return
        deferredContext = null
        IrisCameraScope().use { renderNow(ctx) }
    }

    private fun renderNow(renderContext: LevelRenderContext) {
        val client = Minecraft.getInstance()
        syncWorld(client)
        if (world == null || (system.screens().isEmpty() && net.astrorbits.differangle.client.world.WorldClient.debugCameras.isEmpty()) || lastError != null) return
        compatibilityProblem()?.let { fail(it); return }
        val dispatcher = renderContext.levelRenderer().sectionRenderDispatcher()
        if (dispatcher == null && !FabricLoader.getInstance().isModLoaded("sodium")) return
        val camera = renderContext.levelState().cameraRenderState
        val origin = Position(camera.pos.x, camera.pos.y, camera.pos.z)
        val cameras = system.cameras().associateBy { it.id }
        val surfaces = system.screens().filter {
            it.isFrontFacing(origin) && camera.cullFrustum.isVisible(bounds(it, origin))
        }
        fun isMedia(screen: ScreenDefinition) = media.session(screen.id) is TextureMediaSession
        val visible = surfaces.filter { screen ->
            screen.enabled && (isMedia(screen) || if (screen.mirror) mode == CameraMode.TEXTURE else cameras[screen.cameraId]?.enabled == true)
        }
        val start = System.nanoTime()
        context = renderContext
        dispatcher?.lock()
        try {
            val gpu = renderer ?: CameraWorldRenderer(layers, cameraShaders).also { renderer = it }
            gpu.beginFrame()
            val target = renderContext.gameRenderer().mainRenderTarget()
            net.astrorbits.differangle.client.world.WorldClient.blocks.forEach { block ->
                net.astrorbits.differangle.client.world.WorldGeometry.housing(block,origin,camera.viewRotationMatrix,target,gpu.compositor)
            }
            net.astrorbits.differangle.client.world.WorldGeometry.debug(origin,camera.viewRotationMatrix,target,gpu.compositor)
            surfaces.forEach { gpu.compositor.solid(it.modelMatrix(origin),camera.viewRotationMatrix,target) }
            if (mode == CameraMode.TEXTURE) {
                val regular = system.renderFrame(start, visible.filter { !it.mirror && !isMedia(it) }.map { it.id }, origin)
                var mirrorUpdates = 0
                var mirrorDraws = 0
                val requested = system.screens().filter { it.mirror && it.enabled }.associateBy { it.id }
                mirrors.entries.removeIf { (id, frame) ->
                    val screen = requested[id]
                    if (screen == null || screen.resolution != frame.definition.resolution) {
                        frame.target.destroyBuffers(); true
                    } else false
                }
                for (screen in visible.filter { it.mirror && !isMedia(it) }) {
                    val definition = MirrorView.camera(screen, origin, (client.options.effectiveRenderDistance * 16f).coerceAtLeast(32f)) ?: continue
                    val frame = mirrors.getOrPut(screen.id) {
                        MirrorFrame(TextureTarget("Differangle mirror ${screen.id}", definition.resolution.width,
                            definition.resolution.height, true, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM), definition)
                    }
                    if (frame.renderedAt == 0L || start - frame.renderedAt >= definition.intervalNanos) {
                        frame.definition = definition
                        drawTexture(definition, frame.target)
                        frame.renderedAt = start
                        mirrorUpdates++
                    }
                    drawSurface(screen, frame.target, origin)
                    mirrorDraws++
                }
                var mediaDraws = 0
                for (screen in visible.filter(::isMedia)) {
                    val image = (media.session(screen.id) as TextureMediaSession).textureView() ?: continue
                    gpu.compositor.surface(screen, origin, camera.viewRotationMatrix, target, image)
                    mediaDraws++
                }
                statistics = FrameStatistics(regular.cameraUpdates + mirrorUpdates, regular.screenDraws + mirrorDraws + mediaDraws,
                    regular.cachedCameraCount + mirrors.size)
            } else {
                val target = renderContext.gameRenderer().mainRenderTarget()
                for (screen in visible) {
                    val mediaSession = media.session(screen.id) as? TextureMediaSession
                    if (mediaSession != null) {
                        mediaSession.textureView()?.let { image ->
                            gpu.compositor.surface(screen, origin, camera.viewRotationMatrix, target, image)
                        }
                        continue
                    }
                    val source = cameras.getValue(screen.cameraId)
                    // Embedded draws this screen itself, so its own pixels shape the picture: the screen's
                    // resolution aspect is the stretch it shows, independent of what other screens ask for.
                    gpu.draw(prepare(source.copy(resolution = screen.resolution)),
                        gpu.compositor.embedded(screen, origin, camera.viewRotationMatrix, target))
                }
                statistics = FrameStatistics(prepared.size, visible.size, 0)
            }
            sectionCount = prepared.values.sumOf { it.terrain.sections }
            drawCalls = gpu.terrainDraws
            val native = gpu.nativeFeatures
            nativeFeatures = NativeFeatureTotals(native.entityCount, native.blockEntityCount, native.particleCount, native.weatherColumns, native.cloudViews)
        } catch (failure: Exception) {
            logger.error("Camera rendering failed in {} mode", mode.commandName, failure)
            fail(failureText(failure))
            system.invalidateFrames()
        } finally {
            try { renderer?.endFrame() } finally {
                prepared.clear()
                context = null
                dispatcher?.unlock()
                cpuMillis = (System.nanoTime() - start) / 1_000_000.0
            }
        }
    }

    /** One prepared view per camera and picture shape: Embedded gives every screen its own resolution. */
    private fun prepare(camera: CameraDefinition) = prepared.getOrPut(camera.id to camera.resolution) {
        val client = Minecraft.getInstance()
        val loadedDistance = (client.options.effectiveRenderDistance * 16).toFloat()
        renderer!!.prepare(camera, context!!.levelRenderer(), environments.sample(world!!, camera,
            client.deltaTracker.getGameTimeDeltaPartialTick(false), loadedDistance))
    }

    private fun drawTexture(camera: CameraDefinition, target: TextureTarget) {
        check(context != null) { "Camera renders must run in the world render event" }
        val view = prepare(camera)
        renderer!!.draw(view, renderer!!.compositor.texture(target, view.environment.fogColor))
    }

    private fun drawSurface(screen: ScreenDefinition, target: TextureTarget, origin: Position) {
        val ctx = context ?: error("Screen draws must run in the world render event")
        renderer!!.compositor.surface(screen, origin, ctx.levelState().cameraRenderState.viewRotationMatrix,
            ctx.gameRenderer().mainRenderTarget(), target.colorTextureView!!)
    }

    private fun fail(detail: Component?) {
        lastFailure = detail
        lastError = detail?.string
        if (detail == null) return
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.translatable("differangle.prefix").append(
                Component.translatable("differangle.render.paused", detail, mode.commandName),
            ),
        )
    }

    /** Renderer failures arrive as exceptions; a missing embedded pipeline carries its own key. */
    private fun failureText(failure: Exception): Component =
        if (failure is EmbeddedNativePipelines.UnadaptedPipelineException) {
            Component.translatable("differangle.render.pipeline", failure.pipeline().toString())
        } else {
            Component.literal(failure.message ?: failure.javaClass.simpleName)
        }

    override fun close() { clear(); world = null }

    private fun bounds(screen: ScreenDefinition, origin: Position): AABB {
        val matrix = screen.modelMatrix(origin)
        val points = listOf(-0.5f to -0.5f, -0.5f to 0.5f, 0.5f to -0.5f, 0.5f to 0.5f)
            .map { (x, y) -> matrix.transformPosition(Vector3f(x, y, 0f)) }
        return AABB(
            origin.x + points.minOf { it.x } - 0.01, origin.y + points.minOf { it.y } - 0.01, origin.z + points.minOf { it.z } - 0.01,
            origin.x + points.maxOf { it.x } + 0.01, origin.y + points.maxOf { it.y } + 0.01, origin.z + points.maxOf { it.z } + 0.01,
        )
    }
}

/** Last frame's native feature counts, snapshot out of the renderer so commands can report them. */
data class NativeFeatureTotals(
    val entities: Int = 0,
    val blockEntities: Int = 0,
    val particles: Int = 0,
    val weatherColumns: Int = 0,
    val cloudViews: Int = 0,
)
