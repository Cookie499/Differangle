package net.astrorbits.differangle.media

import java.net.URI
import java.net.InetAddress
import java.util.Locale

enum class MediaSourceType(val serializedName: String) {
    CAMERA("camera"),
    BILIBILI("bilibili"),
    VIDEO("video"),
    IMAGE("image");

    val isMedia: Boolean get() = this != CAMERA

    companion object {
        fun parse(value: String): MediaSourceType = entries.firstOrNull {
            it.serializedName == value.lowercase(Locale.ROOT)
        } ?: throw IllegalArgumentException("Unknown screen source type: $value")
    }
}

enum class AudioAttenuation(val serializedName: String) {
    NONE("none"),
    LINEAR("linear");

    companion object {
        fun parse(value: String): AudioAttenuation = entries.firstOrNull {
            it.serializedName == value.lowercase(Locale.ROOT)
        } ?: throw IllegalArgumentException("Unknown audio attenuation: $value")
    }
}

/** Network-safe, persistent settings. Login cookies and resolved temporary URLs never belong here. */
data class MediaConfig(
    val sourceType: MediaSourceType = MediaSourceType.CAMERA,
    val sourceUrl: String = "",
    val playing: Boolean = true,
    val loop: Boolean = false,
    val positionSeconds: Double = 0.0,
    val audioEnabled: Boolean = true,
    val volume: Float = 1.0f,
    val attenuation: AudioAttenuation = AudioAttenuation.LINEAR,
    val audibleDistance: Float = 32.0f,
    val maxVideoHeight: Int = 720,
    /** Server game time at which [positionSeconds] was established; -1 marks legacy unsynchronized data. */
    val positionGameTime: Long = -1L,
) {
    init {
        require(sourceUrl.length <= MAX_URL_LENGTH) { "Media URL is too long" }
        require(positionSeconds.isFinite() && positionSeconds >= 0.0) { "Invalid media position" }
        require(volume.isFinite() && volume in 0f..1f) { "Invalid media volume" }
        require(audibleDistance.isFinite() && audibleDistance in 1f..256f) { "Invalid audible distance" }
        require(maxVideoHeight in 144..4320) { "Invalid maximum video height" }
        require(positionGameTime >= -1L) { "Invalid media synchronization time" }
        if (sourceType.isMedia) {
            require(sourceUrl.isNotBlank()) { "Media URL is required" }
            MediaUrls.requireHttp(sourceUrl)
        }
        if (sourceType == MediaSourceType.BILIBILI) {
            require(MediaUrls.isBilibili(sourceUrl)) { "Not a Bilibili video URL" }
        }
    }

    /** The server-authoritative target position. Server game time does not advance while the world is offline. */
    fun positionAt(gameTime: Long): Double = positionSeconds +
        if (playing && positionGameTime >= 0L) (gameTime - positionGameTime).coerceAtLeast(0L) / 20.0 else 0.0

    fun anchored(gameTime: Long, position: Double = positionSeconds): MediaConfig =
        copy(positionSeconds = position, positionGameTime = gameTime)

    /** A different source always starts on a fresh timeline. */
    fun withSource(type: MediaSourceType, url: String, gameTime: Long): MediaConfig =
        copy(sourceType = type, sourceUrl = url).anchored(gameTime, 0.0)

    companion object {
        const val MAX_URL_LENGTH = 2048
    }
}

object MediaUrls {
    private val imageExtensions = setOf("png", "jpg", "jpeg")

    fun requireHttp(value: String): URI {
        val uri = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("Invalid media URL", it) }
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")) { "Media URL must use HTTP or HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid media URL host" }
        return uri
    }

    fun isBilibili(value: String): Boolean {
        val host = runCatching { requireHttp(value).host.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" || host.endsWith(".b23.tv")
    }

    /** Best-effort editor convenience. Explicit source selection remains available for extensionless URLs. */
    fun detect(value: String): MediaSourceType? {
        val uri = runCatching { requireHttp(value) }.getOrNull() ?: return null
        if (isBilibili(value)) return MediaSourceType.BILIBILI
        val extension = uri.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension == "mp4" -> MediaSourceType.VIDEO
            extension in imageExtensions -> MediaSourceType.IMAGE
            else -> null
        }
    }
}

object MediaNetworkPolicy {
    fun requirePublic(addresses: Iterable<InetAddress>) {
        require(addresses.any()) { "Media host did not resolve" }
        require(addresses.all(::isPublic)) { "Media URL resolves to a local or private address" }
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false // IPv6 unique-local fc00::/7
        if (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64) return false // 100.64/10
        return true
    }
}
