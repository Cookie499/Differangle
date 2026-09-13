package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.camera.Position
import net.astrorbits.differangle.camera.ScreenDefinition
import net.minecraft.client.renderer.DynamicUniformStorage
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.core.BlockPos
import java.nio.ByteBuffer
import java.util.Optional
import java.util.OptionalDouble
import org.joml.Matrix4f
import org.joml.Vector4f

/** Borrows vanilla compiled terrain buffers for one frame; never rebuilds or owns those buffers. */
class SharedTerrainRenderer : CameraRenderStage {
    private class SectionUniform(val offset: Vector4f) : DynamicUniformStorage.DynamicUniform {
        override fun write(buffer: ByteBuffer) { Std140Builder.intoBuffer(buffer).putVec4(offset) }
    }
    class TerrainDraw(
        val vertices: GpuBuffer, val indices: GpuBuffer?, val indexType: IndexType?,
        val firstIndex: Int, val indexCount: Int, val baseVertex: Int,
        val section: GpuBufferSlice, val cutout: Boolean,
    )
    class PreparedTerrain(val camera: CameraDefinition, val draws: List<TerrainDraw>, val sections: Int)

    private val sections = DynamicUniformStorage<SectionUniform>("Differangle sections", 16, 256)
    var terrainDraws = 0
        private set

    fun beginFrame() { terrainDraws = 0 }
    override fun endFrame() { sections.endFrame() }

    /** Compatibility hooks retained for the original TextureCameraBackend. */
    fun texture(terrain: PreparedTerrain, target: RenderTarget, atlas: GpuTextureView, lightmap: GpuTextureView) {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            target.colorTexture!!, Vector4f(0.12f, 0.18f, 0.25f, 1f), target.depthTexture!!, 1.0,
        )
    }

    fun surface(screen: ScreenDefinition, origin: Position, mainView: Matrix4f, target: RenderTarget, color: GpuTextureView?) {
        // Surface composition is supplied by CameraCompositor in the staged renderer.
    }

    fun embedded(terrain: PreparedTerrain, screen: ScreenDefinition, origin: Position, mainView: Matrix4f,
                 target: RenderTarget, atlas: GpuTextureView, lightmap: GpuTextureView) {
        // Embedded composition is supplied by CameraCompositor in the staged renderer.
    }

    /** Caller holds SectionRenderDispatcher.lock across preparation AND execution for this frame. */
    fun prepare(camera: CameraDefinition, renderer: LevelRenderer): PreparedTerrain {
        val area = renderer.viewArea() ?: return PreparedTerrain(camera, emptyList(), 0)
        val dispatcher = renderer.sectionRenderDispatcher() ?: return PreparedTerrain(camera, emptyList(), 0)
        val frustum = Frustum(camera.viewMatrix(), camera.projectionMatrix())
        frustum.prepare(camera.position.x, camera.position.y, camera.position.z)
        val center = area.cameraSectionPos
        val radius = area.viewDistance
        val draws = mutableListOf<TerrainDraw>()
        var sectionCount = 0
        // Scan the existing ViewArea, including compiled sections outside the player's frustum.
        for (x in center.x() - radius..center.x() + radius) {
            for (z in center.z() - radius..center.z() + radius) {
                for (y in area.minSectionY()..area.maxSectionY()) {
                    val section = area.getRenderSectionAt(BlockPos(x * 16, y * 16, z * 16)) ?: continue
                    if (!frustum.isVisible(section.boundingBox)) continue
                    val mesh = section.getSectionMesh()
                    val origin = section.renderOrigin
                    var offset: GpuBufferSlice? = null
                    for (layer in arrayOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)) {
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
                        val indices = if (custom) slice.indexBuffer()!! else null
                        val type = if (custom) draw.indexType() else null
                        draws += TerrainDraw(slice.vertexBuffer(), indices, type,
                            if (custom) (slice.indexBufferOffset() / type!!.bytes).toInt() else 0,
                            draw.indexCount(), (slice.vertexBufferOffset() / layer.vertexFormat().vertexSize).toInt(),
                            offset, layer == ChunkSectionLayer.CUTOUT)
                    }
                }
            }
        }
        return PreparedTerrain(camera, draws, sectionCount)
    }

    override fun draw(context: CameraDrawContext) {
        val terrain = context.view.terrain
        val output = context.output
        if (terrain.draws.isEmpty()) return
        val embedded = output.embedded
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        val sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        val maxIndices = terrain.draws.filter { it.indices == null }.maxOfOrNull { it.indexCount } ?: 0
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
                pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer()!!)
                pass.bindTexture("SceneDepth", output.mainDepth, sampler)
            }
            for (draw in terrain.draws) {
                pass.setPipeline(when {
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

    override fun close() { sections.close() }
}
