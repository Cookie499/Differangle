package net.astrorbits.differangle.camera

/**
 * Texture-first orchestration. All access (including edits/clear) must use the render thread.
 * Visibility is supplied by the main-view integration, never inferred from player chunks.
 */
class CameraSystem<T : CameraTarget>(
    private val backend: CameraBackend<T>,
    val maxUpdatesPerFrame: Int = 1,
) : AutoCloseable {
    private val cameras = linkedMapOf<String, CameraDefinition>()
    private val screens = linkedMapOf<String, ScreenDefinition>()
    private val frames = mutableMapOf<String, CameraFrame<T>>()
    private var rendering = false
    private var lastFrameTime: Long? = null

    init { require(maxUpdatesPerFrame > 0) }

    fun cameras(): List<CameraDefinition> = cameras.values.toList()
    fun screens(): List<ScreenDefinition> = screens.values.toList()

    fun putCamera(camera: CameraDefinition) {
        checkIdle()
        if (cameras[camera.id] == camera) return
        release(camera.id)
        cameras[camera.id] = camera
    }

    fun putScreen(screen: ScreenDefinition) {
        checkIdle()
        val previous = screens.put(screen.id, screen)
        if (previous != null && previous.cameraId != screen.cameraId) releaseIfUnused(previous.cameraId)
        releaseIfUnused(screen.cameraId)
    }

    fun removeCamera(id: String) {
        checkIdle()
        cameras.remove(id)
        release(id)
    }

    fun removeScreen(id: String) {
        checkIdle()
        screens.remove(id)?.let { releaseIfUnused(it.cameraId) }
    }

    fun frame(cameraId: String): CameraFrame<T>? = frames[cameraId]

    /** Release only GPU frames, retaining definitions when switching rendering modes/reloading assets. */
    fun invalidateFrames() {
        checkIdle()
        val targets = frames.values.map { it.target }
        frames.clear()
        lastFrameTime = null
        closeTargets(targets)
    }

    /**
     * Pass monotonic nanoseconds (System.nanoTime), once per main frame. Hidden cameras keep
     * their cache but receive no updates. One camera shared by N screens renders at most once.
     * Oldest successful update wins; priority breaks ties. This prevents budget starvation.
     */
    fun renderFrame(nowNanos: Long, visibleScreenIds: Collection<String>, mainOrigin: Position): FrameStatistics {
        checkIdle()
        lastFrameTime?.let { require(nowNanos - it >= 0) { "Frame clock moved backwards" } }
        lastFrameTime = nowNanos
        rendering = true
        try {
            val visible = visibleScreenIds.distinct().mapNotNull(screens::get)
                .filter { it.enabled && cameras[it.cameraId]?.enabled == true }
            val due = visible.map { cameras.getValue(it.cameraId) }.distinctBy { it.id }
                .filter { camera -> frames[camera.id]?.let { nowNanos - it.renderedAtNanos >= camera.intervalNanos } ?: true }
                .sortedWith(compareByDescending<CameraDefinition> {
                    frames[it.id]?.let { frame -> nowNanos - frame.renderedAtNanos } ?: Long.MAX_VALUE
                }.thenByDescending { it.priority }.thenBy { it.id })
                .take(maxUpdatesPerFrame)

            for (camera in due) {
                val target = frames[camera.id]?.target ?: backend.createTarget(camera)
                try {
                    backend.renderCamera(camera, target)
                    frames[camera.id] = CameraFrame(target, nowNanos)
                } catch (failure: Throwable) {
                    // A failed draw may have partially overwritten the texture. Never display it.
                    frames.remove(camera.id)
                    try { target.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }

            var draws = 0
            for (screen in visible) {
                frames[screen.cameraId]?.let {
                    backend.renderScreen(screen, it, mainOrigin)
                    draws++
                }
            }
            return FrameStatistics(due.size, draws, frames.size)
        } finally {
            rendering = false
        }
    }

    /** Disconnect, world replacement, resource reload, or renderer replacement. Reusable afterwards. */
    fun clear() {
        checkIdle()
        val targets = frames.values.map { it.target }
        frames.clear()
        cameras.clear()
        screens.clear()
        lastFrameTime = null
        closeTargets(targets)
    }

    private fun closeTargets(targets: List<T>) {
        var failure: Throwable? = null
        for (target in targets) {
            try { target.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    override fun close() = clear()

    private fun releaseIfUnused(cameraId: String) {
        if (screens.values.none { it.cameraId == cameraId && it.enabled }) release(cameraId)
    }

    private fun release(id: String) { frames.remove(id)?.target?.close() }
    private fun checkIdle() { check(!rendering) { "Recursive rendering or mutation during rendering" } }
}
