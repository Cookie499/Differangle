package net.astrorbits.differangle.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Camera-space distances, never main-view or screen-surface distances. */
data class CameraFog(
    val environmentalStart: Float,
    val environmentalEnd: Float,
    val renderDistanceStart: Float,
    val renderDistanceEnd: Float,
) {
    init {
        require(listOf(environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd).all { it.isFinite() })
        require(environmentalEnd > environmentalStart && renderDistanceEnd > renderDistanceStart)
    }

    fun amount(x: Float, y: Float, z: Float): Float = max(
        linear(sqrt(x * x + y * y + z * z), environmentalStart, environmentalEnd),
        linear(max(sqrt(x * x + z * z), abs(y)), renderDistanceStart, renderDistanceEnd),
    )

    companion object {
        fun forView(start: Float, end: Float, farPlane: Float, loadedDistance: Float): CameraFog {
            require(farPlane.isFinite() && farPlane > 0 && loadedDistance.isFinite() && loadedDistance > 0)
            val rangeEnd = minOf(farPlane, loadedDistance)
            val span = (rangeEnd / 10f).coerceIn(minOf(4f, rangeEnd), minOf(64f, rangeEnd))
            val safeEnd = if (end.isFinite()) max(end, 0.01f) else 1_000_000f
            val safeStart = if (start.isFinite()) minOf(start, safeEnd - 0.001f) else safeEnd - 1f
            return CameraFog(safeStart, safeEnd, max(0f, rangeEnd - span), rangeEnd)
        }
        private fun linear(distance: Float, start: Float, end: Float) = ((distance - start) / (end - start)).coerceIn(0f, 1f)
    }
}
