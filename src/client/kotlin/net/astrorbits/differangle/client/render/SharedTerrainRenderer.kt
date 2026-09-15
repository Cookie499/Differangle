package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.VertexSorting
import net.astrorbits.differangle.camera.CameraDefinition
import net.minecraft.client.renderer.DynamicUniformStorage
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.CompiledSectionMesh
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import java.nio.ByteBuffer
import java.util.Optional
import java.util.OptionalDouble
import org.joml.Vector4f
import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.loader.api.FabricLoader
import net.astrorbits.differangle.client.render.compat.SodiumTerrain

/** Borrows vanilla compiled terrain buffers for one frame; never rebuilds or owns those buffers. */
class SharedTerrainRenderer : CameraRenderStage {
    private class SectionUniform(val offset: Vector4f) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) { Std140Builder.intoBuffer(buffer).putVec4(offset) }
    }
    class TerrainDraw(
        val vertices: GpuBuffer, val indices: GpuBuffer?, val indexType: IndexType?,
        val firstIndex: Int, val indexCount: Int, val baseVertex: Int,
        val section: GpuBufferSlice, val cutout: Boolean, val translucent: Boolean, val distanceSquared: Double,
        val sodiumFormat: VertexFormat? = null,
    )
    class PreparedTerrain(val camera: CameraDefinition, val draws: List<TerrainDraw>, val sections: Int,
                          val blockEntities: List<BlockEntity> = emptyList())

    private val sections = DynamicUniformStorage<SectionUniform>("Differangle sections", 16, 256)
    private val ownedIndices = mutableListOf<GpuBuffer>()
    var terrainDraws = 0
        private set

    fun beginFrame() { terrainDraws = 0 }
    override fun endFrame() {
        sections.endFrame()
        ownedIndices.forEach { it.close() }; ownedIndices.clear()
    }

    /** Caller holds SectionRenderDispatcher.lock across preparation AND execution for this frame. */
    fun prepare(camera: CameraDefinition, renderer: LevelRenderer, translucent: Boolean = true): PreparedTerrain {
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            return SodiumTerrain.prepare(camera, translucent) { x, y, z ->
                sections.writeUniform(SectionUniform(Vector4f(x, y, z, 0f)))
            }
        }
        val area = renderer.viewArea() ?: return PreparedTerrain(camera, emptyList(), 0)
        val dispatcher = renderer.sectionRenderDispatcher() ?: return PreparedTerrain(camera, emptyList(), 0)
        val frustum = Frustum(camera.viewMatrix(), camera.projectionMatrix())
        frustum.prepare(camera.position.x, camera.position.y, camera.position.z)
        val center = area.cameraSectionPos
        val radius = area.viewDistance
        val draws = mutableListOf<TerrainDraw>()
        val blockEntities = mutableListOf<BlockEntity>()
        var sectionCount = 0
        // Scan the existing ViewArea, including compiled sections outside the player's frustum.
        for (x in center.x() - radius..center.x() + radius) {
            for (z in center.z() - radius..center.z() + radius) {
                for (y in area.minSectionY()..area.maxSectionY()) {
                    val section = area.getRenderSectionAt(BlockPos(x * 16, y * 16, z * 16)) ?: continue
                    if (!frustum.isVisible(section.boundingBox)) continue
                    val mesh = section.getSectionMesh()
                    blockEntities.addAll(mesh.renderableBlockEntities)
                    val origin = section.renderOrigin
                    var offset: GpuBufferSlice? = null
                    for (layer in ChunkSectionLayer.entries) {
                        if (layer == ChunkSectionLayer.TRANSLUCENT && !translucent) continue
                        val draw = mesh.getSectionDraw(layer) ?: continue
                        val slice = dispatcher.getRenderSectionSlice(mesh, layer) ?: continue
                        if (draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) continue
                        if (offset == null) {
                            offset = sections.writeUniform(SectionUniform(Vector4f(
                                (origin.x - camera.position.x).toFloat(),
                                (origin.y - camera.position.y).toFloat(),
                                (origin.z - camera.position.z).toFloat(), 0f,
                            )))
                            sectionCount++
                        }
                        val custom = draw.hasCustomIndexBuffer()
                        var indices = if (custom) slice.indexBuffer()!! else null
                        var type = if (custom) draw.indexType() else null
                        var firstIndex = if (custom) (slice.indexBufferOffset() / type!!.bytes).toInt() else 0
                        val isTranslucent = layer == ChunkSectionLayer.TRANSLUCENT
                        if (isTranslucent) {
                            // Never resort vanilla's shared index buffer for a secondary camera.
                            val sortState = (mesh as? CompiledSectionMesh)?.transparencyState ?: continue
                            ByteBufferBuilder(draw.indexCount() * sortState.indexType().bytes).use { builder ->
                                sortState.buildSortedIndexBuffer(builder, VertexSorting.byDistance(
                                    (camera.position.x - origin.x).toFloat(), (camera.position.y - origin.y).toFloat(),
                                    (camera.position.z - origin.z).toFloat()))!!.use { result ->
                                    indices = RenderSystem.getDevice().createBuffer({ "Differangle translucent ${camera.id}" },
                                        GpuBuffer.USAGE_INDEX, result.byteBuffer()).also { ownedIndices.add(it) }
                                }
                            }
                            type = sortState.indexType()
                            firstIndex = 0
                        }
                        draws += TerrainDraw(slice.vertexBuffer(), indices, type,
                            firstIndex,
                            draw.indexCount(), (slice.vertexBufferOffset() / layer.vertexFormat().vertexSize).toInt(),
                            offset, layer == ChunkSectionLayer.CUTOUT, isTranslucent,
                            origin.distToCenterSqr(camera.position.x, camera.position.y, camera.position.z))
                    }
                }
            }
        }
        return PreparedTerrain(camera, draws, sectionCount, blockEntities)
    }

    override fun draw(context: CameraDrawContext) = drawLayer(context, false)

    fun drawLayer(context: CameraDrawContext, translucent: Boolean) {
        val terrain = context.view.terrain
        val draws = terrain.draws.filter { it.translucent == translucent }.let {
            if (translucent) it.sortedByDescending { draw -> draw.distanceSquared } else it
        }
        val output = context.output
        if (draws.isEmpty()) return
        val embedded = output.embedded
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        val sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        val maxIndices = draws.filter { it.indices == null }.maxOfOrNull { it.indexCount } ?: 0
        val defaultIndices = if (maxIndices > 0) sequential.getBuffer(maxIndices) else null
        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "Differangle ${if (embedded) "embedded" else "texture"} ${terrain.camera.id}" },
            output.color, Optional.empty(), output.cameraDepth, OptionalDouble.empty(),
        ).use { pass ->
            pass.bindTexture("Sampler0", context.blockAtlas, sampler)
            pass.bindTexture("Sampler2", context.lightmap, sampler)
            pass.setUniform("CameraView", context.viewUniform)
            pass.setUniform("CameraEnvironment", context.view.environmentUniform)
            if (embedded) {
                pass.setUniform("Projection", NativeCameraScope.mainProjection())
                pass.bindTexture("ScreenVisibility", output.screenVisibility, sampler)
            }
            for (draw in draws) {
                pass.setPipeline(if (draw.sodiumFormat != null) CameraPipelines.sodiumTerrain(draw.sodiumFormat, embedded, translucent, draw.cutout) else when {
                    embedded && translucent -> CameraPipelines.embeddedTranslucent
                    translucent -> CameraPipelines.textureTranslucent
                    embedded && draw.cutout -> CameraPipelines.embeddedCutout
                    embedded -> CameraPipelines.embeddedSolid
                    draw.cutout -> CameraPipelines.textureCutout
                    else -> CameraPipelines.textureSolid
                })
                pass.setUniform("SectionOffset", draw.section)
                pass.setVertexBuffer(0, draw.vertices.slice())
                pass.setIndexBuffer(draw.indices ?: defaultIndices!!, draw.indexType ?: sequential.type())
                pass.drawIndexed(draw.indexCount, 1, draw.firstIndex, draw.baseVertex, 0)
                terrainDraws++
            }
        }
    }

    override fun close() { ownedIndices.forEach { it.close() }; ownedIndices.clear(); sections.close() }
}
