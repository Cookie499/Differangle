package net.astrorbits.differangle.client.render.compat

import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.astrorbits.differangle.camera.CameraLayer
import net.astrorbits.differangle.camera.CameraLayers
import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.client.render.*
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform
import net.caffeinemc.mods.sodium.client.util.FogParameters
import net.irisshaders.iris.Iris
import net.irisshaders.iris.mixin.LevelRendererAccessor
import net.irisshaders.iris.pipeline.IrisRenderingPipeline
import net.irisshaders.iris.pipeline.PipelineManager
import net.irisshaders.iris.pipeline.WorldRenderingPhase
import net.irisshaders.iris.shadows.ShadowRenderer
import net.irisshaders.iris.uniforms.CapturedRenderingState
import net.irisshaders.iris.vertices.ImmediateState
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SkyRenderer
import net.minecraft.client.renderer.state.level.SkyRenderState
import net.minecraft.util.profiling.Profiler
import net.minecraft.world.level.dimension.DimensionType.Skybox
import net.minecraft.world.phys.AABB
import net.minecraft.world.level.block.entity.BlockEntity
import org.joml.Matrix4f
import kotlin.math.floor

/** Experimental Iris 1.11 backend. Each camera owns its attachments and temporal history. */
class IrisShaderCameras : AutoCloseable {
    private class Entry(val target: com.mojang.blaze3d.pipeline.RenderTarget, val pipeline: IrisRenderingPipeline, val sky: SkyRenderer, val uniforms: UniformBufferManager) {
        fun close() { sky.close(); uniforms.delete(); pipeline.destroy() }
    }
    private val entries = mutableMapOf<String, Entry>()
    private val prepared = mutableMapOf<CameraDefinition, Visibility>()
    private var mainGeneration: Any? = null
    var terrainDraws = 0; private set
    fun beginFrame() { terrainDraws = 0; prepared.clear() }
    private val client = Minecraft.getInstance()
    private val pipelineField = PipelineManager::class.java.getDeclaredField("pipeline").apply { isAccessible = true }
    private val initializedField = IrisRenderingPipeline::class.java.getDeclaredField("initializedBlockIds").apply { isAccessible = true }

    /** Shader rendering needs native lists and block entities, not expanded fallback TerrainDraws. */
    fun prepare(definition: CameraDefinition): SharedTerrainRenderer.PreparedTerrain {
        val visibility = Visibility(definition)
        prepared[definition] = visibility
        return SharedTerrainRenderer.PreparedTerrain(definition, emptyList(), visibility.sections, visibility.blockEntities.toList())
    }

    fun draw(context: CameraDrawContext, features: CameraNativeFeatures, layers: CameraLayers) {
        val manager = Iris.getPipelineManager()
        val main = manager.pipelineNullable ?: error("Iris has no active pipeline")
        val visibility = requireNotNull(prepared.remove(context.view.camera)) { "Shader terrain was not prepared" }
        if (mainGeneration !== main) { releasePipelines(); mainGeneration = main }
        val camera = visibility.camera
        val target = requireNotNull(context.output.target)
        val captured = CapturedRenderingState.INSTANCE
        val oldView = Matrix4f(captured.gbufferModelView)
        val oldProjection = Matrix4f(captured.gbufferProjection)
        val oldFog = captured.fogColor
        val oldTick = captured.tickDelta
        val oldSkip = ImmediateState.skipExtension.get()
        val oldLevel = ImmediateState.isRenderingLevel
        val oldExtended = ImmediateState.renderWithExtendedVertexFormat
        val oldBypass = ImmediateState.bypass
        val oldMultiply = ImmediateState.safeToMultiply
        val oldState = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        val oldShadowView = ShadowRenderer.MODELVIEW
        val oldShadowProjection = ShadowRenderer.PROJECTION
        val oldShadowFrustum = ShadowRenderer.FRUSTUM
        val oldShadowResolution = ShadowRenderer.RESOLUTION
        val oldShadowDistance = ShadowRenderer.renderDistance
        val oldShadowEntities = ShadowRenderer.visibleBlockEntities
        val state = camera.renderState(RenderSystem.getDevice().deviceInfo.isZZeroToOne)
        ShaderCameraContext.target = target
        ShaderCameraContext.observer = camera
        try {
            entries[context.view.camera.id]?.takeIf { it.target !== target }?.let {
                entries.remove(context.view.camera.id)
                it.close()
            }
            val entry = entries.getOrPut(context.view.camera.id) {
                val pack = Iris.getCurrentPack().orElseThrow()
                check(pack.bufferObjects.isEmpty()) { "Experimental camera shaders do not yet support shader packs with SSBOs" }
                val pipeline = IrisRenderingPipeline(pack.getProgramSet(Iris.getCurrentDimension()))
                // The main pipeline has already installed the same material map. Do not rebuild all chunks.
                initializedField.setBoolean(pipeline, true)
                Entry(target, pipeline, SkyRenderer(client.textureManager, client.atlasManager, target),
                    UniformBufferManager(requireNotNull(client.level), client.options.effectiveRenderDistance))
            }
            pipelineField.set(manager, entry.pipeline)
            ImmediateState.skipExtension.set(false)
            ImmediateState.isRenderingLevel = true
            ImmediateState.renderWithExtendedVertexFormat = true
            ImmediateState.bypass = false
            ImmediateState.safeToMultiply = true
            captured.setGbufferModelView(state.viewRotationMatrix)
            captured.setGbufferProjection(state.projectionMatrix)
            captured.setTickDelta(client.deltaTracker.getGameTimeDeltaPartialTick(false))
            val fog = context.view.environment.fogColor
            captured.setFogColor(fog.x, fog.y, fog.z)
            client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState = state
            val terrain = CameraTerrain(context, entry.uniforms, visibility)
            try {
                features.draw(context,
                    translucentTerrain = {
                        if (CameraLayer.TRANSLUCENT in layers) terrain.draw(true)
                    },
                    beforeWorld = {
                        entry.pipeline.beginLevelRendering()
                        Profiler.get().push("differangle_camera_shadows")
                        try { entry.pipeline.renderShadows(client.levelRenderer as LevelRendererAccessor, camera, state) }
                        finally { Profiler.get().pop() }
                        // Shadow rendering restores the player's projection; restore this camera's projection too.
                        captured.setGbufferModelView(state.viewRotationMatrix)
                        captured.setGbufferProjection(state.projectionMatrix)
                        entry.pipeline.onBeginClear()
                        drawSky(entry.sky, camera)
                        entry.pipeline.setPhase(WorldRenderingPhase.TERRAIN_SOLID)
                        terrain.draw(false)
                        entry.pipeline.setPhase(WorldRenderingPhase.NONE)
                    },
                    beforeTranslucents = { entry.pipeline.beginHand(); entry.pipeline.beginTranslucents() },
                    observer = camera,
                )
                entry.pipeline.finalizeLevelRendering()
                terrainDraws += terrain.draws
            } finally { terrain.close() }
        } finally {
            pipelineField.set(manager, main)
            client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState = oldState
            captured.setGbufferModelView(oldView); captured.setGbufferProjection(oldProjection)
            captured.setFogColor(oldFog.x.toFloat(), oldFog.y.toFloat(), oldFog.z.toFloat())
            captured.setTickDelta(oldTick)
            ShadowRenderer.MODELVIEW = oldShadowView; ShadowRenderer.PROJECTION = oldShadowProjection
            ShadowRenderer.FRUSTUM = oldShadowFrustum; ShadowRenderer.RESOLUTION = oldShadowResolution
            ShadowRenderer.renderDistance = oldShadowDistance; ShadowRenderer.visibleBlockEntities = oldShadowEntities
            ImmediateState.skipExtension.set(oldSkip)
            ImmediateState.isRenderingLevel = oldLevel; ImmediateState.renderWithExtendedVertexFormat = oldExtended
            ImmediateState.bypass = oldBypass; ImmediateState.safeToMultiply = oldMultiply
            ShaderCameraContext.target = null; ShaderCameraContext.observer = null
        }
    }

    private fun drawSky(sky: SkyRenderer, camera: VirtualCamera) {
        val state = SkyRenderState()
        sky.extractRenderState(requireNotNull(client.level), client.deltaTracker.getGameTimeDeltaPartialTick(false), camera, state)
        val poses = PoseStack()
        when (state.skybox) {
            Skybox.END -> sky.renderEndSky()
            Skybox.OVERWORLD -> {
                sky.renderSkyDisc(state.skyColor)
                sky.renderSunriseAndSunset(poses, state.sunAngle, state.sunriseAndSunsetColor)
                sky.renderSunMoonAndStars(poses, state.sunAngle, state.moonAngle, state.starAngle,
                    state.moonPhase, state.rainBrightness, state.starBrightness)
                if (state.shouldRenderDarkDisc) sky.renderDarkDisc()
            }
            else -> Unit
        }
    }

    /** One visibility traversal for shader lists, animated sprites and block entities. */
    private class Visibility(val definition: CameraDefinition) {
        private val manager = requireNotNull(SodiumTerrain.sectionManager(requireNotNull(SodiumWorldRenderer.instanceNullable())))
        val lists = linkedMapOf<RenderRegion, ChunkRenderList>()
        val blockEntities = linkedSetOf<BlockEntity>()
        val camera = VirtualCamera(definition, requireNotNull(Minecraft.getInstance().level))
        var sections = 0; private set
        val ordered: List<ChunkRenderList>
        init {
            val client = Minecraft.getInstance()
            val level = requireNotNull(client.level)
            val storage = requireNotNull(SodiumTerrain.sectionStorage(manager))
            val frustum = camera.cullFrustum
            val radius = client.options.effectiveRenderDistance
            val cx = floor(definition.position.x / 16).toInt()
            val cz = floor(definition.position.z / 16).toInt()
            for (x in cx - radius..cx + radius) for (z in cz - radius..cz + radius) {
                for (y in level.minSectionY..level.maxSectionY) {
                    val section = storage.getCurrent(x, y, z) ?: continue
                    if (!section.isBuilt || section.isDisposed) continue
                    if (!frustum.isVisible(AABB(x * 16.0, y * 16.0, z * 16.0, x * 16.0 + 16, y * 16.0 + 16, z * 16.0 + 16))) continue
                    val region = section.region
                    val index = section.sectionIndex
                    region.getAnimatedSprites(index)?.forEach { SpriteUtil.INSTANCE.markSpriteActive(it) }
                    region.getCulledBlockEntities(index)?.let { blockEntities.addAll(it) }
                    region.getGlobalBlockEntities(index)?.let { blockEntities.addAll(it) }
                    lists.getOrPut(region) { ChunkRenderList(region) }.add(index)
                    sections++
                }
            }
            ordered = lists.values.sortedBy {
                val r = it.region
                val dx = r.chunkX * 16.0 + 64 - definition.position.x
                val dy = r.chunkY * 16.0 + 32 - definition.position.y
                val dz = r.chunkZ * 16.0 + 64 - definition.position.z
                dx * dx + dy * dy + dz * dz
            }
        }
    }

    /** Independent visibility lists; never consume or replace the main camera's occlusion result. */
    private class CameraTerrain(val context: CameraDrawContext, val uniforms: UniformBufferManager,
                                val visibility: Visibility) : AutoCloseable {
        var draws = 0; private set
        private val manager = requireNotNull(SodiumTerrain.sectionManager(requireNotNull(SodiumWorldRenderer.instanceNullable())))
        private val definition = context.view.camera
        private val matrices = ChunkRenderMatrices(RendererCompatibility.projection(definition,
            RenderSystem.getDevice().deviceInfo.isZZeroToOne), definition.viewMatrix())
        private val fog = context.view.environment.let {
            FogParameters(it.fogColor.x, it.fogColor.y, it.fogColor.z, it.fogColor.w,
                it.fog.environmentalStart, it.fog.environmentalEnd, it.fog.renderDistanceStart, it.fog.renderDistanceEnd)
        }
        private var uniformsReady = false
        fun draw(translucent: Boolean) {
            visibility.lists.keys.forEach { it.clearAllCachedBatches() }
            if (!uniformsReady) {
                uniforms.prepareFrame(); uniforms.update(matrices, fog)
                uniformsReady = true
            }
            val ordered = visibility.ordered
            val iterable = ChunkRenderListIterable { reverse -> (if (reverse) ordered.asReversed() else ordered).iterator() }
            val passes = if (translucent) listOf(DefaultTerrainRenderPasses.TRANSLUCENT)
                else listOf(DefaultTerrainRenderPasses.SOLID, DefaultTerrainRenderPasses.CUTOUT)
            for (pass in passes) manager.chunkRenderer.render(matrices, iterable, pass,
                CameraTransform(definition.position.x, definition.position.y, definition.position.z), fog, true,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true), uniforms.uniformBuffer, uniforms.sectionTimeInfo)
            draws += visibility.lists.size * passes.size
        }
        override fun close() { visibility.lists.keys.forEach { it.clearAllCachedBatches() } }
    }

    fun endFrame() {
        prepared.clear()
        entries.entries.removeIf { (_, entry) ->
            if (entry.target.colorTexture == null) { entry.close(); true }
            else { entry.uniforms.endFrame(); false }
        }
    }
    private fun releasePipelines() { entries.values.forEach { it.close() }; entries.clear() }
    override fun close() { prepared.clear(); releasePipelines(); mainGeneration = null }
}
