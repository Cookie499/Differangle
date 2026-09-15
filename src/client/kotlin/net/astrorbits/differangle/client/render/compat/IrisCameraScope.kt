package net.astrorbits.differangle.client.render.compat

import net.irisshaders.iris.api.v0.IrisApi
import net.irisshaders.iris.vertices.ImmediateState

/** Used after Iris finishes the main world's composite, before Minecraft clears its world depth. */
class IrisCameraScope : AutoCloseable {
    private val skip = ImmediateState.skipExtension.get()
    private val level = ImmediateState.isRenderingLevel
    private val extended = ImmediateState.renderWithExtendedVertexFormat
    private val bypass = ImmediateState.bypass
    private val multiply = ImmediateState.safeToMultiply
    init {
        ImmediateState.skipExtension.set(true)
        ImmediateState.isRenderingLevel = false
        ImmediateState.renderWithExtendedVertexFormat = false
        ImmediateState.bypass = true
        ImmediateState.safeToMultiply = false
    }
    override fun close() {
        ImmediateState.skipExtension.set(skip)
        ImmediateState.isRenderingLevel = level
        ImmediateState.renderWithExtendedVertexFormat = extended
        ImmediateState.bypass = bypass
        ImmediateState.safeToMultiply = multiply
    }
    companion object {
        fun active() = IrisApi.getInstance().isShaderPackInUse
        fun shadowPass() = IrisApi.getInstance().isRenderingShadowPass
    }
}
