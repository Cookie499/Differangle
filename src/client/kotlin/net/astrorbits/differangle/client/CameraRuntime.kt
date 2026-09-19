package net.astrorbits.differangle.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.render.CameraWorldRenderer
import net.astrorbits.differangle.client.render.CameraEnvironmentSampler
import net.astrorbits.differangle.client.render.EmbeddedNativePipelines
import net.astrorbits.differangle.client.render.MainViewEye
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
import kotlin.math.abs
import kotlin.math.sqrt

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
    var budgetFallbacks = 0
        private set
    var mirrorTargetCount = 0
        private set
    var mirrorTargetPixels = 0L
        private set
    private var world: ClientLevel? = null
    private var renderer: CameraWorldRenderer? = null
    private val environments = CameraEnvironmentSampler()
    private var context: LevelRenderContext? = null
    private val prepared = mutableMapOf<Pair<String, Resolution>, PreparedCameraView>()
    private data class MirrorFrame(
        val target: TextureTarget,
        var definition: CameraDefinition,
        var lastUsedNanos: Long,
        var renderedAtNanos: Long,
    )
    private val mirrors = mutableMapOf<String, MirrorFrame>()
    private val embeddedCooldowns = mutableMapOf<String, Long>()
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
        embeddedCooldowns.clear()
        media.close()
        media = MediaRuntime(ClientMediaBackend())
        renderer?.close()
        renderer = null
        prepared.clear()
        environments.clear()
        statistics = FrameStatistics(0, 0, 0)
        sectionCount = 0; drawCalls = 0; cpuMillis = 0.0
        budgetFallbacks = 0; mirrorTargetCount = 0; mirrorTargetPixels = 0L
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
        val mirrorEye = MainViewEye.resolve(camera, client.gameRenderer.gameRenderState().optionsRenderState.bobView)
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
        var recoverableFailure: Exception? = null
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
                val mirrorCandidates = visible.filter { it.mirror && !isMedia(it) }
                    .sortedByDescending { apparentArea(it, origin) }
                val admittedMirrors = admitMirrors(mirrorCandidates)
                val admittedIds = admittedMirrors.mapTo(mutableSetOf()) { it.id }
                mirrors.entries.removeIf { (id, frame) ->
                    if (id in admittedIds || start - frame.lastUsedNanos < MIRROR_IDLE_NANOS) false
                    else { frame.target.destroyBuffers(); true }
                }
                makeMirrorRoom(admittedMirrors)
                val updateOrder = admittedMirrors.sortedWith(
                    compareBy<ScreenDefinition> { mirrors[it.id]?.renderedAtNanos ?: Long.MIN_VALUE }
                        .thenByDescending { apparentArea(it, origin) },
                )
                for ((index, screen) in updateOrder.withIndex()) {
                    val definition = MirrorView.camera(screen, mirrorEye, (client.options.effectiveRenderDistance * 16f).coerceAtLeast(32f)) ?: continue
                    var frame = mirrors[screen.id]
                    try {
                        if (frame == null && index < MAX_MIRROR_UPDATES_PER_FRAME && canAllocateMirror(definition.resolution)) {
                            frame = MirrorFrame(TextureTarget("Differangle mirror ${screen.id}", definition.resolution.width,
                                definition.resolution.height, true, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM), definition,
                                start, Long.MIN_VALUE)
                            mirrors[screen.id] = frame
                        }
                    } catch (failure: Exception) {
                        logger.error("Mirror {} target allocation failed; this mirror remains uncached", screen.id, failure)
                        continue
                    }
                    if (frame == null) continue
                    frame.lastUsedNanos = start
                    if (index < MAX_MIRROR_UPDATES_PER_FRAME) {
                        try {
                            // The freshest mirrors follow the interpolated main eye. Lower-priority mirrors
                            // retain their last complete frame instead of exhausting the GPU mid-frame.
                            frame.definition = definition
                            drawTexture(definition, frame.target)
                            frame.renderedAtNanos = start
                            mirrorUpdates++
                        } catch (failure: Exception) {
                            mirrors.remove(screen.id)
                            runCatching { frame.target.destroyBuffers() }
                            logger.error("Mirror {} rendering failed; only this mirror was disabled for the frame", screen.id, failure)
                            continue
                        }
                    }
                    try {
                        drawSurface(screen, frame.target, origin)
                        mirrorDraws++
                    } catch (failure: Exception) {
                        mirrors.remove(screen.id)
                        runCatching { frame.target.destroyBuffers() }
                        logger.error("Mirror {} composition failed; only this mirror was disabled for the frame", screen.id, failure)
                    }
                }
                mirrorTargetCount = mirrors.size
                mirrorTargetPixels = mirrorPixels()
                budgetFallbacks = mirrorCandidates.size - mirrorUpdates
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
                val cameraScreens = visible.filter { !isMedia(it) }
                embeddedCooldowns.entries.removeIf { (id, until) -> until <= start || cameraScreens.none { it.id == id } }
                val direct = cameraScreens.asSequence()
                    .filter { embeddedCooldowns[it.id]?.let { until -> until > start } != true }
                    .sortedByDescending { apparentArea(it, origin) }
                    .take(MAX_EMBEDDED_DRAWS_PER_FRAME)
                    .toList()
                val directIds = direct.mapTo(mutableSetOf()) { it.id }
                budgetFallbacks = cameraScreens.size - direct.size
                // Embedded cost grows with the number of screens. Excess screens use the ordinary
                // shared Texture cache, which updates at most one camera per main frame.
                val fallback = system.renderFrame(start, cameraScreens.filter { it.id !in directIds }.map { it.id }, origin)
                var directDraws = 0
                for (screen in visible.filter(::isMedia)) {
                    val mediaSession = media.session(screen.id) as? TextureMediaSession
                    mediaSession?.textureView()?.let { image ->
                        gpu.compositor.surface(screen, origin, camera.viewRotationMatrix, target, image)
                    }
                }
                for (screen in direct) {
                    val source = cameras.getValue(screen.cameraId)
                    try {
                        // Embedded draws this screen itself, so its own pixels shape the picture: the screen's
                        // resolution aspect is the stretch it shows, independent of what other screens ask for.
                        gpu.draw(prepare(source.copy(resolution = screen.resolution)),
                            gpu.compositor.embedded(screen, origin, camera.viewRotationMatrix, target))
                        directDraws++
                    } catch (failure: Exception) {
                        embeddedCooldowns[screen.id] = start + EMBEDDED_FAILURE_COOLDOWN_NANOS
                        logger.error("Embedded screen {} rendering failed; falling back to Texture temporarily", screen.id, failure)
                        system.frame(screen.cameraId)?.let { drawSurface(screen, it.target.renderTarget, origin) }
                    }
                }
                statistics = FrameStatistics(fallback.cameraUpdates + directDraws,
                    fallback.screenDraws + directDraws + visible.count(::isMedia), fallback.cachedCameraCount)
                mirrorTargetCount = mirrors.size
                mirrorTargetPixels = mirrorPixels()
            }
            sectionCount = prepared.values.sumOf { it.terrain.sections }
            drawCalls = gpu.terrainDraws
            val native = gpu.nativeFeatures
            nativeFeatures = NativeFeatureTotals(native.entityCount, native.blockEntityCount, native.particleCount, native.weatherColumns, native.cloudViews)
        } catch (failure: Exception) {
            logger.error("Camera rendering failed in {} mode", mode.commandName, failure)
            if (failure is EmbeddedNativePipelines.UnadaptedPipelineException) {
                fail(failureText(failure))
            } else {
                recoverableFailure = failure
            }
        } finally {
            try {
                renderer?.endFrame()
            } catch (failure: Exception) {
                logger.error("Camera renderer frame cleanup failed; rebuilding GPU resources", failure)
                recoverableFailure?.addSuppressed(failure) ?: run { recoverableFailure = failure }
            } finally {
                prepared.clear()
                context = null
                dispatcher?.unlock()
                cpuMillis = (System.nanoTime() - start) / 1_000_000.0
            }
            recoverableFailure?.let(::recoverGpuResources)
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

    /** A transient allocation/submission failure rebuilds secondary resources without hiding every screen forever. */
    private fun recoverGpuResources(failure: Exception) {
        lastFailure = failureText(failure)
        lastError = null
        runCatching { system.invalidateFrames() }
            .onFailure { failure.addSuppressed(it) }
        mirrors.values.forEach { frame ->
            runCatching { frame.target.destroyBuffers() }.onFailure { failure.addSuppressed(it) }
        }
        mirrors.clear()
        mirrorTargetCount = 0
        mirrorTargetPixels = 0L
        runCatching { renderer?.close() }.onFailure { failure.addSuppressed(it) }
        renderer = null
    }

    /** Approximate projected area; sufficient for stable overload prioritisation without a GPU query. */
    private fun apparentArea(screen: ScreenDefinition, eye: Position): Double {
        val dx = eye.x - screen.position.x
        val dy = eye.y - screen.position.y
        val dz = eye.z - screen.position.z
        val distanceSquared = (dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01)
        val normal = screen.rotation.quaternion().transform(org.joml.Vector3d(0.0, 0.0, 1.0))
        val facing = abs(normal.x * dx + normal.y * dy + normal.z * dz) / sqrt(distanceSquared)
        return screen.width.toDouble() * screen.height.toDouble() * facing / distanceSquared
    }

    /** Admit the largest visible mirrors without exceeding persistent color+depth target limits. */
    private fun admitMirrors(candidates: List<ScreenDefinition>): List<ScreenDefinition> {
        var totalPixels = 0L
        val result = ArrayList<ScreenDefinition>(minOf(candidates.size, MAX_MIRROR_TARGETS))
        for (screen in candidates) {
            val requested = pixels(screen.resolution)
            if (result.size >= MAX_MIRROR_TARGETS || totalPixels + requested > MAX_MIRROR_TARGET_PIXELS) continue
            result += screen
            totalPixels += requested
        }
        return result
    }

    /** Hidden cached targets are the first resources evicted when newly visible mirrors need room. */
    private fun makeMirrorRoom(admitted: List<ScreenDefinition>) {
        val protected = admitted.mapTo(mutableSetOf()) { it.id }
        val missing = admitted.filter { it.id !in mirrors }
        val missingPixels = missing.sumOf { pixels(it.resolution) }
        val removable = mirrors.entries.filter { it.key !in protected }.sortedBy { it.value.lastUsedNanos }.iterator()
        while ((mirrors.size + missing.size > MAX_MIRROR_TARGETS ||
                mirrorPixels() + missingPixels > MAX_MIRROR_TARGET_PIXELS) && removable.hasNext()) {
            val entry = removable.next()
            mirrors.remove(entry.key)?.target?.destroyBuffers()
        }
    }

    private fun canAllocateMirror(resolution: Resolution): Boolean =
        mirrors.size < MAX_MIRROR_TARGETS && mirrorPixels() + pixels(resolution) <= MAX_MIRROR_TARGET_PIXELS

    private fun mirrorPixels(): Long = mirrors.values.sumOf { pixels(it.definition.resolution) }
    private fun pixels(resolution: Resolution): Long = resolution.width.toLong() * resolution.height

    private companion object {
        const val MAX_MIRROR_UPDATES_PER_FRAME = 2
        const val MAX_EMBEDDED_DRAWS_PER_FRAME = 2
        const val MAX_MIRROR_TARGETS = 8
        const val MAX_MIRROR_TARGET_PIXELS = 8L * 1024L * 1024L
        const val MIRROR_IDLE_NANOS = 5_000_000_000L
        const val EMBEDDED_FAILURE_COOLDOWN_NANOS = 5_000_000_000L
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
