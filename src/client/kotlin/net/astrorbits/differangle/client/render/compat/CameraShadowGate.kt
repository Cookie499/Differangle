package net.astrorbits.differangle.client.render.compat

import kotlin.math.abs

/**
 * Decides whether a camera refresh must rerender shadows (doc 6.1).
 *
 * State is kept per camera id. The caller owns the per-camera refresh counter and increments it
 * at the start of every refresh, so the first refresh has index 0. sunAngle is in radians.
 */
class CameraShadowGate(private val updateEvery: Int, private val adaptive: Boolean) {
    private class State {
        var lastShadowRenderIndex = -1L
        var lastSunAngle = Float.NaN
        var lastShadowDistance = -1
        var lastShadowResolution = -1
        var lastFov = Float.NaN
        var lastNear = Float.NaN
        var lastFar = Float.NaN
        var lastOffAxis: net.astrorbits.differangle.camera.OffAxisProjection? = null
    }

    private val states = mutableMapOf<String, State>()
    private val sunThresholdRadians = Math.toRadians(0.5).toFloat()

    fun shouldRender(id: String, refreshIndex: Long, sunAngleRadians: Float,
                     shadowDistance: Int, shadowResolution: Int,
                     fov: Float, near: Float, far: Float,
                     offAxis: net.astrorbits.differangle.camera.OffAxisProjection?): Boolean {
        if (updateEvery <= 1) return true
        val state = states.getOrPut(id) { State() }
        val render = refreshIndex == 0L ||
            shadowDistance != state.lastShadowDistance ||
            shadowResolution != state.lastShadowResolution ||
            fov != state.lastFov ||
            near != state.lastNear ||
            far != state.lastFar ||
            offAxis != state.lastOffAxis ||
            (adaptive && abs(sunAngleRadians - state.lastSunAngle) > sunThresholdRadians) ||
            refreshIndex - state.lastShadowRenderIndex >= updateEvery.toLong()
        if (render) {
            state.lastShadowRenderIndex = refreshIndex
            state.lastSunAngle = sunAngleRadians
            state.lastShadowDistance = shadowDistance
            state.lastShadowResolution = shadowResolution
            state.lastFov = fov
            state.lastNear = near
            state.lastFar = far
            state.lastOffAxis = offAxis
        }
        return render
    }

    fun reset(id: String) { states.remove(id) }

    fun clear() = states.clear()
}
