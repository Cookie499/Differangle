package net.astrorbits.differangle.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MediaRuntimeTest {
    private class Session(override val request: MediaRequest) : MediaSession {
        var ticks = 0
        var closes = 0
        override fun tick() { ticks++ }
        override fun close() { closes++ }
    }

    @Test fun `opens reuses replaces and closes media sessions`() {
        val opened = mutableListOf<Session>()
        val runtime = MediaRuntime { request -> Session(request).also(opened::add) }
        val video = MediaConfig(MediaSourceType.VIDEO, "https://example.com/a.mp4")
        fun request(config: MediaConfig) = MediaRequest(config, 256, 144, 15)
        runtime.sync(mapOf("camera" to request(MediaConfig()), "screen" to request(video)))
        runtime.tick()
        assertEquals(1, opened.size)
        assertEquals(1, opened.single().ticks)
        assertNotNull(runtime.session("screen"))

        runtime.sync(mapOf("screen" to request(video)))
        assertEquals(1, opened.size)
        runtime.sync(mapOf("screen" to request(video.copy(volume = 0.5f))))
        assertEquals(1, opened.first().closes)
        assertEquals(2, opened.size)

        runtime.sync(emptyMap())
        assertEquals(1, opened.last().closes)
        assertNull(runtime.session("screen"))
        runtime.close()
    }
}
