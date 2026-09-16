package net.astrorbits.differangle.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MediaSourceTest {
    @Test fun `detects supported URL families without inspecting query strings`() {
        assertEquals(MediaSourceType.BILIBILI, MediaUrls.detect("https://www.bilibili.com/video/BV1234?p=2"))
        assertEquals(MediaSourceType.BILIBILI, MediaUrls.detect("https://b23.tv/abc123"))
        assertEquals(MediaSourceType.VIDEO, MediaUrls.detect("https://cdn.example/video.MP4?token=abc"))
        assertEquals(MediaSourceType.IMAGE, MediaUrls.detect("https://cdn.example/poster.webp?v=2"))
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
}
