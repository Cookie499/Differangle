package net.astrorbits.differangle.client.render.compat

import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.camera.Position
import net.astrorbits.differangle.camera.Rotation
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents

/**
 * Cross-refresh visibility skeleton cache for a stationary camera (doc 5.1).
 *
 * The skeleton records which section coordinates passed the last full radius scan and the
 * distance-sorted region order. A hit re-reads section content through SectionStorage.getCurrent,
 * so meshes, block entities and animated sprites are always fresh. Chunk load and unload events
 * clear the whole cache; chunk events are rare, so this is lossless.
 */
class CameraVisibilityCache {
    data class Key(
        val position: Position,
        val rotation: Rotation,
        val fov: Float,
        val near: Float,
        val far: Float,
        val aspect: Float,
        val offAxis: net.astrorbits.differangle.camera.OffAxisProjection?,
        val radius: Int,
        val minSectionY: Int,
        val maxSectionY: Int,
    )

    data class SectionCoord(val x: Int, val y: Int, val z: Int)
    data class RegionCoord(val x: Int, val y: Int, val z: Int)

    class Skeleton(val sections: List<SectionCoord>, val regionOrder: List<RegionCoord>)

    private class Entry(val key: Key, val skeleton: Skeleton)

    private val entries = mutableMapOf<String, Entry>()

    companion object {
        @Volatile
        var active: CameraVisibilityCache? = null

        init {
            ClientChunkEvents.CHUNK_LOAD.register { _, _ -> active?.clear() }
            ClientChunkEvents.CHUNK_UNLOAD.register { _, _ -> active?.clear() }
        }
    }

    init {
        active = this
    }

    fun keyFor(definition: CameraDefinition, radius: Int, minSectionY: Int, maxSectionY: Int) = Key(
        definition.position,
        definition.rotation,
        definition.fov,
        definition.nearPlane,
        definition.farPlane,
        definition.resolution.aspect,
        definition.offAxis,
        radius,
        minSectionY,
        maxSectionY,
    )

    fun obtain(id: String, key: Key): Skeleton? = entries[id]?.takeIf { it.key == key }?.skeleton

    fun store(id: String, key: Key, skeleton: Skeleton) {
        entries[id] = Entry(key, skeleton)
    }

    fun clear() = entries.clear()
}
