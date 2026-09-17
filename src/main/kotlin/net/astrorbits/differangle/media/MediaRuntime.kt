package net.astrorbits.differangle.media

/** Decoder/render implementations live in the client source set; this core owns their lifecycle. */
data class MediaRequest(
    val config: MediaConfig,
    val width: Int,
    val height: Int,
    val fps: Int,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
) {
    init {
        require(width in 16..2048 && height in 16..2048 && fps in 1..240)
        require(x.isFinite() && y.isFinite() && z.isFinite())
    }
}

interface MediaSession : AutoCloseable {
    val request: MediaRequest
    val config: MediaConfig get() = request.config
    fun tick()
}

fun interface MediaBackend {
    fun open(request: MediaRequest): MediaSession
}

class MediaRuntime(private val backend: MediaBackend) : AutoCloseable {
    private val sessions = linkedMapOf<String, MediaSession>()

    fun sync(requested: Map<String, MediaRequest>) {
        val media = requested.filterValues { it.config.sourceType.isMedia }
        sessions.entries.removeIf { (id, session) ->
            val next = media[id]
            if (next == null || next != session.request) {
                session.close()
                true
            } else false
        }
        media.forEach { (id, request) -> sessions.getOrPut(id) { backend.open(request) } }
    }

    fun tick() = sessions.values.forEach(MediaSession::tick)

    fun session(screenId: String): MediaSession? = sessions[screenId]

    override fun close() {
        var failure: Throwable? = null
        sessions.values.forEach { session ->
            try { session.close() } catch (cause: Throwable) {
                val first = failure
                if (first == null) failure = cause else first.addSuppressed(cause)
            }
        }
        sessions.clear()
        failure?.let { throw it }
    }
}
