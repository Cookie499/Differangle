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
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage
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
 * Every read past [sectionManager] uses sodium's public API: the section storage, region buffer arenas,
 * per-section mesh headers, block entities and animated sprites are all reachable from public members.
 *
 * The candidate set is the camera's own chunk radius, culled by the camera's own [Frustum]. Sodium's per-frame
 * render lists must NOT be used here: those lists are what the *main* camera can see after its own occlusion
 * pass, so anything the main camera cannot see (behind it, underground, occluded) would silently vanish from the
 * screen even though this camera is looking straight at it. Occlusion culling against this camera's own depth is
 * not reachable through sodium's public API, so only frustum culling is applied.
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

    private val storageField = runCatching {
        RenderSectionManager::class.java.getDeclaredField("renderSections").apply { isAccessible = true }
    }.onFailure { log.warn("Sodium manager no longer exposes 'renderSections'; sodium terrain is disabled", it) }.getOrNull()

    fun prepare(camera: CameraDefinition, translucent: Boolean, offset: (Float, Float, Float) -> GpuBufferSlice): PreparedTerrain {
        val client = Minecraft.getInstance()
        val level = client.level ?: return PreparedTerrain(camera, emptyList(), 0)
        val sodium = SodiumWorldRenderer.instanceNullable() ?: return PreparedTerrain(camera, emptyList(), 0)
        val manager = sectionManager(sodium) ?: return PreparedTerrain(camera, emptyList(), 0)
        val storage = sectionStorage(manager) ?: return PreparedTerrain(camera, emptyList(), 0)
        val frustum = Frustum(camera.viewMatrix(), camera.projectionMatrix()).apply {
            prepare(camera.position.x, camera.position.y, camera.position.z)
        }
        val format = vertexFormat
        val radius = client.options.effectiveRenderDistance
        val cx = Math.floorDiv(camera.position.x.toInt(), 16)
        val cz = Math.floorDiv(camera.position.z.toInt(), 16)
        val draws = mutableListOf<TerrainDraw>()
        val entities = linkedSetOf<BlockEntity>()
        var sectionCount = 0
        // getCurrent is the render-thread view; safe-read phases belong to sodium's own async culler.
        for (x in cx - radius..cx + radius) for (z in cz - radius..cz + radius) {
            for (y in level.minSectionY..level.maxSectionY) {
                val section = storage.getCurrent(x, y, z) ?: continue
                if (section.isDisposed || !section.isBuilt) continue
                val ox = section.originX
                val oy = section.originY
                val oz = section.originZ
                if (!frustum.isVisible(bounds(ox, oy, oz))) continue
                val region = section.region
                val index = section.sectionIndex
                // Textures animate because sodium only ticks the sprites that were reported visible this frame.
                // This mesh is not drawn through sodium's own pipeline, so its sprites must be reported through
                // the public API on every frame they are drawn.
                region.getAnimatedSprites(index)?.forEach { SpriteUtil.INSTANCE.markSpriteActive(it) }
                region.getCulledBlockEntities(index)?.let { entities.addAll(it) }
                region.getGlobalBlockEntities(index)?.let { entities.addAll(it) }
                val resources = region.resources ?: continue
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
                                resources.geometryBuffer,
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
        return PreparedTerrain(camera, draws, sectionCount, entities.toList())
    }

    private fun bounds(ox: Int, oy: Int, oz: Int) =
        AABB(ox.toDouble(), oy.toDouble(), oz.toDouble(), ox + 16.0, oy + 16.0, oz + 16.0)

    private fun squaredDistance(ox: Int, oy: Int, oz: Int, camera: CameraDefinition): Double {
        val dx = ox + 8 - camera.position.x
        val dy = oy + 8 - camera.position.y
        val dz = oz + 8 - camera.position.z
        return dx * dx + dy * dy + dz * dz
    }

    /** The only non-public sodium reads: the renderer's manager and that manager's section storage have no getters. */
    private fun sectionManager(sodium: SodiumWorldRenderer): RenderSectionManager? {
        val field = managerField ?: return null
        return runCatching { field.get(sodium) as? RenderSectionManager }
            .onFailure { log.warn("Could not read the sodium section manager; sodium terrain is disabled", it) }
            .getOrNull()
    }

    private fun sectionStorage(manager: RenderSectionManager): SectionStorage? {
        val field = storageField ?: return null
        return runCatching { field.get(manager) as? SectionStorage }
            .onFailure { log.warn("Could not read the sodium section storage; sodium terrain is disabled", it) }
            .getOrNull()
    }
}
