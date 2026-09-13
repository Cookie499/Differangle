package net.astrorbits.differangle.camera

/** A camera owns its color/depth resources. Screen draws only borrow them. */
interface CameraTarget : AutoCloseable

/**
 * Called synchronously on the render thread. Implementations must restore renderer state
 * even on failure, and must never recursively render camera screens into a camera view.
 * World geometry/extraction belongs to the backend, not to individual screens.
 */
interface CameraBackend<T : CameraTarget> {
    fun createTarget(camera: CameraDefinition): T
    fun renderCamera(camera: CameraDefinition, target: T)
    fun renderScreen(screen: ScreenDefinition, frame: CameraFrame<T>, mainOrigin: Position)
}

/** Valid until the owning system updates/removes the camera or clears its world. */
data class CameraFrame<T : CameraTarget>(val target: T, val renderedAtNanos: Long)

data class FrameStatistics(val cameraUpdates: Int, val screenDraws: Int, val cachedCameraCount: Int)
