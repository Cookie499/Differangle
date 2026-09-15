package net.astrorbits.differangle.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.astrorbits.differangle.camera.*
import net.astrorbits.differangle.client.render.CameraWorldRenderer
import net.astrorbits.differangle.client.render.CameraEnvironmentSampler
import net.astrorbits.differangle.client.render.PreparedCameraView
import net.astrorbits.differangle.client.render.TextureCameraBackend
import net.astrorbits.differangle.client.render.compat.IrisCameraScope
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
    val system = CameraSystem(TextureCameraBackend(::drawTexture, ::drawSurface))
    val layers = CameraLayers()
    var contentStatistics = "本帧尚未更新摄像头"
        private set

    fun setLayer(layer: CameraLayer, enabled: Boolean) {
        layers.set(layer, enabled)
        reloadResources()
    }
    var mode = CameraMode.TEXTURE
        private set
    var lastError: String? = null
        private set
    var statistics = FrameStatistics(0, 0, 0)
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
    private val prepared = mutableMapOf<String, PreparedCameraView>()
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
        world?.let { environments.tick(it, system.cameras()) }
    }

    fun switchMode(value: CameraMode) {
        RenderSystem.assertOnRenderThread()
        reloadResources()
        mode = value
    }

    /** F3+T and mode switches release resources while preserving Camera/Screen definitions. */
    fun reloadResources() {
        deferredContext = null
        system.invalidateFrames()
        renderer?.close()
        renderer = null
        prepared.clear()
        environments.clear()
        statistics = FrameStatistics(0, 0, 0)
        sectionCount = 0; drawCalls = 0; cpuMillis = 0.0
        lastError = null
        contentStatistics = "本帧尚未更新摄像头"
    }

    fun clear() {
        system.clear()
        reloadResources()
    }

    fun compatibilityProblem(): String? {
        val loader = FabricLoader.getInstance()
        for ((id, series) in listOf("sodium" to "0.9.", "iris" to "1.11.")) {
            val installed = loader.getModContainer(id).orElse(null) ?: continue
            val version = installed.metadata.version.friendlyString
            if (!version.startsWith(series)) return "摄像机后端适配 $id ${series}x，当前安装的是 $version。"
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
        val visible = surfaces.filter { it.enabled && cameras[it.cameraId]?.enabled == true }
        val start = System.nanoTime()
        context = renderContext
        dispatcher?.lock()
        try {
            val gpu = renderer ?: CameraWorldRenderer(layers).also { renderer = it }
            gpu.beginFrame()
            val target = renderContext.gameRenderer().mainRenderTarget()
            net.astrorbits.differangle.client.world.WorldClient.blocks.forEach { block ->
                net.astrorbits.differangle.client.world.WorldGeometry.housing(block,origin,camera.viewRotationMatrix,target,gpu.compositor)
            }
            net.astrorbits.differangle.client.world.WorldGeometry.debug(origin,camera.viewRotationMatrix,target,gpu.compositor)
            surfaces.forEach { gpu.compositor.solid(it.modelMatrix(origin),camera.viewRotationMatrix,target) }
            if (mode == CameraMode.TEXTURE) {
                statistics = system.renderFrame(start, visible.map { it.id }, origin)
            } else {
                val target = renderContext.gameRenderer().mainRenderTarget()
                for (screen in visible) {
                    gpu.draw(prepare(cameras.getValue(screen.cameraId)),
                        gpu.compositor.embedded(screen, origin, camera.viewRotationMatrix, target))
                }
                statistics = FrameStatistics(prepared.size, visible.size, 0)
            }
            sectionCount = prepared.values.sumOf { it.terrain.sections }
            drawCalls = gpu.terrainDraws
            val native = gpu.nativeFeatures
            contentStatistics = "实体=${native.entityCount} 方块实体=${native.blockEntityCount} 粒子=${native.particleCount} 降水列=${native.weatherColumns} 云视图=${native.cloudViews}"
        } catch (failure: Exception) {
            logger.error("Camera rendering failed in {} mode", mode.commandName, failure)
            fail(failure.message ?: failure.javaClass.simpleName)
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

    private fun prepare(camera: CameraDefinition) = prepared.getOrPut(camera.id) {
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

    private fun fail(message: String) {
        lastError = message
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("[Differangle] 渲染暂停：$message 使用 /differangle mode ${mode.commandName} 重试。"),
        )
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
