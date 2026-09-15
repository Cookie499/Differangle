package net.astrorbits.differangle.client.render.compat

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.vertex.VertexFormat
import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.client.render.SharedTerrainRenderer.PreparedTerrain
import net.astrorbits.differangle.client.render.SharedTerrainRenderer.TerrainDraw
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB

/** Sodium 0.9: borrow uploaded region buffers on the render thread, without changing its main-view lists. */
object SodiumTerrain {
    // These three private API accesses are isolated here so upstream changes fail with a useful field name.
    private val managerField = SodiumWorldRenderer::class.java.getDeclaredField("renderSectionManager").apply { isAccessible = true }
    private val storageField = RenderSectionManager::class.java.getDeclaredField("renderSections").apply { isAccessible = true }
    private val formatField = ShaderChunkRenderer::class.java.getDeclaredField("vertexFormat").apply { isAccessible = true }

    fun prepare(camera: CameraDefinition, translucent: Boolean, offset: (Float, Float, Float) -> GpuBufferSlice): PreparedTerrain {
        val client = Minecraft.getInstance()
        val level = client.level ?: return PreparedTerrain(camera, emptyList(), 0)
        val sodium = SodiumWorldRenderer.instanceNullable() ?: return PreparedTerrain(camera, emptyList(), 0)
        val manager = managerField.get(sodium) as? RenderSectionManager ?: return PreparedTerrain(camera, emptyList(), 0)
        val storage = storageField.get(manager) as SectionStorage
        // Iris can extend the format; use the live renderer's stride, not a hard-coded 20 bytes.
        val format = formatField.get(manager.chunkRenderer) as VertexFormat
        val frustum = Frustum(camera.viewMatrix(), camera.projectionMatrix()).apply {
            prepare(camera.position.x, camera.position.y, camera.position.z)
        }
        val center = client.gameRenderer.mainCamera().position()
        val cx = Math.floorDiv(kotlin.math.floor(center.x).toInt(), 16)
        val cz = Math.floorDiv(kotlin.math.floor(center.z).toInt(), 16)
        val radius = client.options.effectiveRenderDistance
        val draws = mutableListOf<TerrainDraw>()
        val entities = linkedSetOf<BlockEntity>()
        var sectionCount = 0
        // getCurrent is the render-thread view; safe-read phases belong to Sodium's async culler.
        for (x in cx - radius..cx + radius) for (z in cz - radius..cz + radius) {
            for (y in level.minSectionY..level.maxSectionY) {
                val section = storage.getCurrent(x, y, z) ?: continue
                if (section.isDisposed || !section.isBuilt) continue
                val ox = section.originX; val oy = section.originY; val oz = section.originZ
                if (!frustum.isVisible(AABB(ox.toDouble(), oy.toDouble(), oz.toDouble(), ox + 16.0, oy + 16.0, oz + 16.0))) continue
                val region = section.region
                val index = section.sectionIndex
                region.getAnimatedSprites(index)?.forEach { net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil.markSpriteActive(it) }
                region.getCulledBlockEntities(index)?.let { entities.addAll(it) }
                region.getGlobalBlockEntities(index)?.let { entities.addAll(it) }
                val resources = region.resources ?: continue
                var uniform: GpuBufferSlice? = null
                for (layer in DefaultTerrainRenderPasses.ALL) {
                    val transparent = layer === DefaultTerrainRenderPasses.TRANSLUCENT
                    if (transparent && !translucent) continue
                    val data = region.getStorage(layer) ?: continue
                    val pointer = data.getDataPointer(index)
                    if (SectionRenderDataUnsafe.getSliceMask(pointer) == 0) continue
                    if (uniform == null) {
                        uniform = offset((ox - camera.position.x).toFloat(), (oy - camera.position.y).toFloat(), (oz - camera.position.z).toFloat())
                        sectionCount++
                    }
                    var baseVertex = SectionRenderDataUnsafe.getBaseVertex(pointer).toInt()
                    var firstIndex = SectionRenderDataUnsafe.getBaseElement(pointer).toInt()
                    val localIndices = SectionRenderDataUnsafe.isLocalIndex(pointer)
                    for (face in 0 until 7) {
                        val count = SectionRenderDataUnsafe.getVertexCount(pointer, face).toInt()
                        val indexCount = count / 4 * 6
                        if (count > 0) draws += TerrainDraw(resources.geometryBuffer,
                            if (localIndices) resources.indexBuffer else null,
                            if (localIndices) IndexType.INT else null,
                            if (localIndices) firstIndex else 0, indexCount, baseVertex, uniform,
                            layer === DefaultTerrainRenderPasses.CUTOUT, transparent,
                            camera.position.let { (ox + 8 - it.x) * (ox + 8 - it.x) + (oy + 8 - it.y) * (oy + 8 - it.y) + (oz + 8 - it.z) * (oz + 8 - it.z) }, format)
                        baseVertex += count
                        firstIndex += indexCount
                    }
                }
            }
        }
        return PreparedTerrain(camera, draws, sectionCount, entities.toList())
    }
}
