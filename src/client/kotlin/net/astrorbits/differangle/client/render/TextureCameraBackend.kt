package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.astrorbits.differangle.camera.CameraBackend
import net.astrorbits.differangle.camera.CameraDefinition
import net.astrorbits.differangle.camera.CameraFrame
import net.astrorbits.differangle.camera.CameraTarget
import net.astrorbits.differangle.camera.Position
import net.astrorbits.differangle.camera.ScreenDefinition

class TextureCameraTarget(camera: CameraDefinition) : CameraTarget {
    val renderTarget: TextureTarget
    private var closed = false

    init {
        RenderSystem.assertOnRenderThread()
        renderTarget = TextureTarget(
            "Differangle/${camera.id}", camera.resolution.width, camera.resolution.height,
            true, GpuFormat.RGBA8_UNORM,
        )
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (!closed) {
            renderTarget.destroyBuffers()
            closed = true
        }
    }
}

/**
 * GPU resource adapter for 26.2. Both draw callbacks are required: allocating a target alone
 * is not a successful world render. Each world draw must clear/write its own color and depth,
 * use independent camera matrices and restore all temporary renderer state in finally.
 * Screen drawing samples color while using the MAIN view's surface depth, never camera depth.
 */
class TextureCameraBackend(
    private val drawWorld: (CameraDefinition, TextureTarget) -> Unit,
    private val drawSurface: (ScreenDefinition, TextureTarget, Position) -> Unit,
) : CameraBackend<TextureCameraTarget> {
    override fun createTarget(camera: CameraDefinition): TextureCameraTarget = TextureCameraTarget(camera)

    override fun renderCamera(camera: CameraDefinition, target: TextureCameraTarget) {
        RenderSystem.assertOnRenderThread()
        drawWorld(camera, target.renderTarget)
    }

    override fun renderScreen(screen: ScreenDefinition, frame: CameraFrame<TextureCameraTarget>, mainOrigin: Position) {
        RenderSystem.assertOnRenderThread()
        drawSurface(screen, frame.target.renderTarget, mainOrigin)
    }
}
