package net.astrorbits.differangle.client.render.compat

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.vertex.VertexFormat
import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.client.render.SharedTerrainRenderer.PreparedTerrain
import net.astrorbits.differangle.client.render.SharedTerrainRenderer.TerrainDraw
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB
import org.slf4j.LoggerFactory

/**
 * Sodium: borrow the region buffers that the main camera already uploaded, without touching its view lists.
 *
 * Sodium's public API is used throughout. The main camera's per-frame render lists name every region and section
 * index that is currently loaded, built and visible; the region objects then hand out the buffer arenas, the
 * per-section mesh headers and the block entity / animated sprite payloads. Nothing here mutates sodium state.
 *
 * The one field sodium does not expose is the [RenderSectionManager] itself; [sectionManager] is the single
 * accessor for it. Everything downstream of it is public API, so an upstream rename degrades to "no sodium
 * terrain" instead of failing on three separate private reads.
 */
object SodiumTerrain {
    private val log = LoggerFactory.getLogger("Differangle/SodiumTerrain")
    private val solid = DefaultTerrainRenderPasses.SOLID
    private val cutout = DefaultTerrainRenderPasses.CUTOUT
    private val translucent = DefaultTerrainRenderPasses.TRANSLUCENT
    private val layers: Array<TerrainRenderPass> = arrayOf(solid, cutout, translucent)

    /**
     * Sodium picks its chunk vertex format at runtime and Iris overwrites [ChunkMeshFormats.getCurrent] whenever a
     * shader pack is in use. This is the public source of truth for the layout of the buffers borrowed below;
     * reading the renderer's own field instead reports the compact format even while Iris uploads an extended one.
     */
    private val vertexFormat: VertexFormat get() = ChunkMeshFormats.getCurrent().vertexFormat

    private val managerField = runCatching {
        SodiumWorldRenderer::class.java.getDeclaredField("renderSectionManager").apply { isAccessible = true }
    }.onFailure { log.warn("Sodium renderer no longer exposes 'renderSectionManager'; sodium terrain is disabled", it) }.getOrNull()

    fun prepare(camera: CameraDefinition, translucent: Boolean, offset: (Float, Float, Float) -> GpuBufferSlice): PreparedTerrain {
        val sodium = SodiumWorldRenderer.instanceNullable() ?: return PreparedTerrain(camera, emptyList(), 0)
        val manager = sectionManager(sodium) ?: return PreparedTerrain(camera, emptyList(), 0)
        val frustum = Frustum(camera.viewMatrix(), camera.projectionMatrix()).apply {
            prepare(camera.position.x, camera.position.y, camera.position.z)
        }
        val format = vertexFormat
        val draws = mutableListOf<TerrainDraw>()
        val entities = linkedSetOf<BlockEntity>()
        var sectionCount = 0
        // getRenderLists() is the render-thread view; sodium publishes it at the end of its own culling pass.
        for (renderList in manager.renderLists.iterator(false)) {
            val region = renderList.region
            val resources = region.resources ?: continue
            // Textures animate because sodium only ticks the sprites that were reported visible this frame.
            // The borrowed chunk mesh is not drawn through sodium's own pipeline, so its sprites must be reported
            // through the public API on every frame they are drawn.
            renderList.sectionsWithSpritesIterator()?.let { sprites ->
                while (sprites.hasNext()) {
                    region.getAnimatedSprites(sprites.nextByteAsInt())?.forEach { SpriteUtil.INSTANCE.markSpriteActive(it) }
                }
            }
            val geometry = resources.geometryBuffer
            renderList.sectionsWithGeometryIterator(false)?.let { sections ->
                while (sections.hasNext()) {
                    val index = sections.nextByteAsInt()
                    if (region.sectionIsInvisible(index)) continue
                    val ox = sectionOrigin(region.originX, LocalSectionIndex.unpackX(index))
                    val oy = sectionOrigin(region.originY, LocalSectionIndex.unpackY(index))
                    val oz = sectionOrigin(region.originZ, LocalSectionIndex.unpackZ(index))
                    if (!frustum.isVisible(bounds(ox, oy, oz))) continue
                    // Block entities come from the same section set that is drawn, so they stay inside the camera.
                    region.getCulledBlockEntities(index)?.let { entities.addAll(it) }
                    region.getGlobalBlockEntities(index)?.let { entities.addAll(it) }
                    var uniform: GpuBufferSlice? = null
                    for (layer in layers) {
                        if (layer.isTranslucent && !translucent) continue
                        val pointer = region.getStorage(layer)?.getDataPointer(index) ?: continue
                        if (SectionRenderDataUnsafe.getSliceMask(pointer) == 0) continue
                        if (uniform == null) {
                            uniform = offset(
                                (ox - camera.position.x).toFloat(),
                                (oy - camera.position.y).toFloat(),
                                (oz - camera.position.z).toFloat(),
                            )
                            sectionCount++
                        }
                        val localIndices = SectionRenderDataUnsafe.isLocalIndex(pointer)
                        var baseVertex = SectionRenderDataUnsafe.getBaseVertex(pointer).toInt()
                        var firstIndex = SectionRenderDataUnsafe.getBaseElement(pointer).toInt()
                        for (face in 0 until ModelQuadFacing.COUNT) {
                            val count = SectionRenderDataUnsafe.getVertexCount(pointer, face).toInt()
                            val indexCount = count / 4 * 6
                            if (count > 0) {
                                draws += TerrainDraw(
                                    geometry,
                                    if (localIndices) resources.indexBuffer else null,
                                    if (localIndices) IndexType.INT else null,
                                    if (localIndices) firstIndex else 0,
                                    indexCount,
                                    baseVertex,
                                    uniform,
                                    layer === cutout,
                                    layer.isTranslucent,
                                    squaredDistance(ox, oy, oz, camera),
                                    format,
                                )
                            }
                            baseVertex += count
                            firstIndex += indexCount
                        }
                    }
                }
            }
        }
        return PreparedTerrain(camera, draws, sectionCount, entities.toList())
    }

    private fun bounds(ox: Int, oy: Int, oz: Int) =
        AABB(ox.toDouble(), oy.toDouble(), oz.toDouble(), ox + 16.0, oy + 16.0, oz + 16.0)

    private fun sectionOrigin(regionOrigin: Int, localSection: Int) = regionOrigin + (localSection shl 4)

    private fun squaredDistance(ox: Int, oy: Int, oz: Int, camera: CameraDefinition): Double {
        val dx = ox + 8 - camera.position.x
        val dy = oy + 8 - camera.position.y
        val dz = oz + 8 - camera.position.z
        return dx * dx + dy * dy + dz * dz
    }

    /** The only non-public sodium read left: [SodiumWorldRenderer] has no getter for its section manager. */
    private fun sectionManager(sodium: SodiumWorldRenderer): RenderSectionManager? {
        val field = managerField ?: return null
        return runCatching { field.get(sodium) as? RenderSectionManager }
            .onFailure { log.warn("Could not read the sodium section manager; sodium terrain is disabled", it) }
            .getOrNull()
    }
}
