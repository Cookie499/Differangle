package net.astrorbits.differangle.client.media

import net.astrorbits.differangle.media.MediaBackend
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.media.MediaSession
import net.astrorbits.differangle.media.MediaSourceType

class ClientMediaBackend(
    private val images: ImageMediaBackend = ImageMediaBackend(),
    private val videos: WaterMediaBackend = WaterMediaBackend(),
) : MediaBackend {
    override fun open(request: MediaRequest): MediaSession = when (request.config.sourceType) {
        MediaSourceType.IMAGE -> images.open(request)
        MediaSourceType.VIDEO -> videos.open(request)
        MediaSourceType.BILIBILI -> videos.open(request)
        else -> throw IllegalArgumentException("Unsupported client media source: ${request.config.sourceType.serializedName}")
    }
}
