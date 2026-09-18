package net.astrorbits.differangle.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetAddress

class MediaSourceTest {
    @Test fun `playing position follows the server game clock while paused position stays fixed`() {
        val playing = MediaConfig(MediaSourceType.VIDEO, "https://example.com/a.mp4", positionSeconds = 12.5)
            .anchored(100L)
        assertEquals(14.5, playing.positionAt(140L))
        assertEquals(12.5, playing.copy(playing = false).positionAt(140L))
        assertEquals(12.5, playing.positionAt(80L))
    }

    @Test fun `detects supported URL families without inspecting query strings`() {
        assertEquals(MediaSourceType.BILIBILI, MediaUrls.detect("https://www.bilibili.com/video/BV1234?p=2"))
        assertEquals(MediaSourceType.BILIBILI, MediaUrls.detect("https://b23.tv/abc123"))
        assertEquals(MediaSourceType.VIDEO, MediaUrls.detect("https://cdn.example/video.MP4?token=abc"))
        assertEquals(MediaSourceType.IMAGE, MediaUrls.detect("https://cdn.example/poster.jpeg?v=2"))
        assertNull(MediaUrls.detect("https://example.com/media?id=42"))
    }

    @Test fun `rejects local files credentials and mismatched Bilibili sources`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaConfig(MediaSourceType.VIDEO, "file:///tmp/video.mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaConfig(MediaSourceType.IMAGE, "https://user:password@example.com/a.png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaConfig(MediaSourceType.BILIBILI, "https://example.com/video/BV1234")
        }
    }

    @Test fun `network policy blocks addresses that could reach the client local network`() {
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "::1", "fd00::1").forEach {
            assertEquals(false, MediaNetworkPolicy.isPublic(InetAddress.getByName(it)), it)
        }
        assertEquals(true, MediaNetworkPolicy.isPublic(InetAddress.getByName("1.1.1.1")))
    }
}
