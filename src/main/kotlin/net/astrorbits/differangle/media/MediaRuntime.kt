package net.astrorbits.differangle.media

/** Decoder/render implementations live in the client source set; this core owns their lifecycle. */
interface MediaSession : AutoCloseable {
    val config: MediaConfig
    fun tick()
}

fun interface MediaBackend {
    fun open(config: MediaConfig): MediaSession
}

class MediaRuntime(private val backend: MediaBackend) : AutoCloseable {
    private val sessions = linkedMapOf<String, MediaSession>()

    fun sync(requested: Map<String, MediaConfig>) {
        val media = requested.filterValues { it.sourceType.isMedia }
        sessions.entries.removeIf { (id, session) ->
            val next = media[id]
            if (next == null || next != session.config) {
                session.close()
                true
            } else false
        }
        media.forEach { (id, config) -> sessions.getOrPut(id) { backend.open(config) } }
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
