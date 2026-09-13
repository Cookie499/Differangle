package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.astrorbits.differangle.camera.CameraLayer
import net.astrorbits.differangle.camera.CameraLayers
import net.astrorbits.differangle.mixin.client.ParticleEngineAccessor
import net.astrorbits.differangle.mixin.client.ParticleGroupAccessor
import net.minecraft.client.CloudStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.*
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.state.level.ParticlesRenderState
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.state.level.WeatherRenderState
import net.minecraft.util.ARGB
import net.minecraft.util.profiling.Profiler
import org.joml.Matrix4f
import java.nio.ByteBuffer

/** Independent feature buffers and snapshots: the main frame is still in flight at this event. */
class CameraNativeFeatures(private val layers: CameraLayers) : AutoCloseable {
    private class ProjectionUniform(val matrix: Matrix4f) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) { Std140Builder.intoBuffer(buffer).putMat4f(matrix) }
    }
    private class FogUniform(val e: CameraEnvironment, val cloudEnd: Float) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) {
            Std140Builder.intoBuffer(buffer).putVec4(e.fogColor)
                .putFloat(e.fog.environmentalStart).putFloat(e.fog.environmentalEnd)
                .putFloat(e.fog.renderDistanceStart).putFloat(e.fog.renderDistanceEnd)
                .putFloat(e.skyFogEnd).putFloat(cloudEnd).putVec2(0f, 0f)
        }
    }
    private class CameraClouds : CloudRenderer() {
        private var range = -1
        init {
            val resources = Minecraft.getInstance().resourceManager
            apply(prepare(resources, Profiler.get()), resources, Profiler.get())
        }
        fun updateRange(value: Int) { if (range != value) { range = value; markForRebuild() } }
    }
    private val client = Minecraft.getInstance()
    private val buffers = RenderBuffers(1)
    private val features = FeatureRenderDispatcher(buffers, client.modelManager, client.atlasManager,
        client.font, client.gameRenderer.gameRenderState())
    private val projections = DynamicUniformStorage<ProjectionUniform>("Differangle native projections", 64, 8)
    private val fogs = DynamicUniformStorage<FogUniform>("Differangle native fog", 48, 8)
    private val globals = GlobalSettingsUniform()
    private val weather = WeatherEffectRenderer()
    private var clouds: CameraClouds? = null
    var entityCount = 0; private set
    var blockEntityCount = 0; private set
    var particleCount = 0; private set
    var weatherColumns = 0; private set
    var cloudViews = 0; private set

    fun beginFrame() { entityCount = 0; blockEntityCount = 0; particleCount = 0; weatherColumns = 0; cloudViews = 0 }

    fun draw(context: CameraDrawContext, translucentTerrain: () -> Unit) {
        val level = client.level ?: return
        val target = requireNotNull(context.output.target)
        val camera = VirtualCamera(context.view.camera, level)
        val zeroToOne = RenderSystem.getDevice().deviceInfo.isZZeroToOne
        val state = camera.renderState(zeroToOne)
        val partial = client.deltaTracker.getGameTimeDeltaPartialTick(false)
        val dispatcher = client.levelRenderer.entityRenderDispatcher()
        val blockDispatcher = client.levelRenderer.blockEntityRenderDispatcher()
        val particles = ParticlesRenderState()
        val storage = SubmitNodeStorage()
        NativeCameraScope(target, camera, projections.writeUniform(ProjectionUniform(state.projectionMatrix)),
            fogs.writeUniform(FogUniform(context.view.environment,
                minOf(context.view.camera.farPlane, client.options.cloudRange().get() * 16f))), state.viewRotationMatrix, context).use {
            val dispatcherScope = CameraDispatcherScope(dispatcher, blockDispatcher, camera)
            try {
                globals.update(target.width, target.height, client.options.glintStrength().get(), level.gameTime,
                    client.deltaTracker, 0, state.pos, false)
                client.gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL)
                val poses = PoseStack()
                if (CameraLayer.ENTITIES in layers) {
                    for (entity in level.entitiesForRendering()) {
                        if (entity.isRemoved || !dispatcher.shouldRender(entity, state.cullFrustum, state.pos.x, state.pos.y, state.pos.z)) continue
                        val tick = if (level.tickRateManager().isEntityFrozen(entity)) 1f else partial
                        val renderState = dispatcher.extractEntity(entity, tick)
                        dispatcher.submit(renderState, state, renderState.x - state.pos.x,
                            renderState.y - state.pos.y, renderState.z - state.pos.z, poses, storage)
                        entityCount++
                    }
                }
                if (CameraLayer.BLOCK_ENTITIES in layers) {
                    fun submit(block: net.minecraft.world.level.block.entity.BlockEntity, global: Boolean) {
                        if (block.isRemoved) return
                        val renderState = blockDispatcher.tryExtractRenderState<net.minecraft.world.level.block.entity.BlockEntity, BlockEntityRenderState>(
                            block, partial, null, global) ?: return
                        poses.pushPose()
                        try {
                            poses.translate(block.blockPos.x - state.pos.x, block.blockPos.y - state.pos.y, block.blockPos.z - state.pos.z)
                            blockDispatcher.submit(renderState, poses, storage, state)
                            blockEntityCount++
                        } finally { poses.popPose() }
                    }
                    context.view.terrain.blockEntities.distinctBy { it.blockPos }.forEach { submit(it, false) }
                    level.globallyRenderedBlockEntities.forEach { submit(it, true) }
                }
                if (CameraLayer.PARTICLES in layers) {
                    // QuadParticleGroup normally reuses the main frame's mutable snapshot.
                    // Build a fresh one directly from live particles, without ticking or clearing the main snapshot.
                    val groups = (client.particleEngine as ParticleEngineAccessor).`differangle$getGroups`()
                    val quads = QuadParticleRenderState()
                    groups[ParticleRenderType.SINGLE_QUADS]?.let { group ->
                        for (particle in (group as ParticleGroupAccessor).`differangle$getParticles`()) {
                            if (particle is SingleQuadParticle && camera.cullFrustum.isVisible(particle.boundingBox)) {
                                particle.extract(quads, camera, partial); particleCount++
                            }
                        }
                    }
                    particles.add(quads)
                    for (type in listOf(ParticleRenderType.ITEM_PICKUP, ParticleRenderType.ELDER_GUARDIANS)) {
                        groups[type]?.let { group ->
                            particles.add(group.extractRenderState(camera.cullFrustum, camera, partial)); particleCount += group.size()
                        }
                    }
                    particles.submit(storage, state)
                }
                features.prepareFrame(storage).use { frame ->
                    frame.executeSolid()
                    frame.executeTranslucent()
                    translucentTerrain()
                    frame.executeTranslucentAfterTerrain()
                }
                if (CameraLayer.CLOUDS in layers && client.options.cloudStatus().get() != CloudStatus.OFF && ARGB.alpha(context.view.environment.cloudColor) > 0) {
                    val cloudRenderer = clouds ?: CameraClouds().also { clouds = it }
                    val range = minOf(client.options.cloudRange().get(), (context.view.camera.farPlane / 16f).toInt()).coerceAtLeast(1)
                    cloudRenderer.updateRange(range)
                    cloudRenderer.render(context.view.environment.cloudColor, client.options.cloudStatus().get(),
                        context.view.environment.cloudHeight, range, state.pos, level.gameTime, partial)
                    cloudViews++
                }
                if (CameraLayer.WEATHER in layers) {
                    val rain = WeatherRenderState()
                    weather.extractRenderState(level, partial, state.pos, rain)
                    weather.render(state.pos, rain)
                    weatherColumns += rain.rainColumns.size + rain.snowColumns.size
                }
            } finally {
                particles.reset()
                dispatcherScope.close()
                // Rotate secondary buffers after each camera; never touch the main FeatureRenderDispatcher.
                buffers.endFrame()
                clouds?.endFrame()
            }
        }
    }

    fun endFrame() { projections.endFrame(); fogs.endFrame() }
    override fun close() {
        features.close(); buffers.fixedBufferPack().close(); buffers.close()
        projections.close(); fogs.close(); globals.close(); weather.close(); clouds?.close()
    }
}
