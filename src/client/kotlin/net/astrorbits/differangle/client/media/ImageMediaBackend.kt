package net.astrorbits.differangle.client.media

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import net.astrorbits.differangle.media.MediaBackend
import net.astrorbits.differangle.media.MediaConfig
import net.astrorbits.differangle.media.MediaNetworkPolicy
import net.astrorbits.differangle.media.MediaRequest
import net.astrorbits.differangle.media.MediaSession
import net.astrorbits.differangle.media.MediaSourceType
import net.astrorbits.differangle.media.MediaUrls
import net.minecraft.client.renderer.texture.DynamicTexture
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

interface TextureMediaSession : MediaSession {
    fun textureView(): GpuTextureView?
    val failure: String?
}

class ImageMediaBackend(
    private val downloader: ImageDownloader = ImageDownloader(),
) : MediaBackend {
    override fun open(request: MediaRequest): MediaSession {
        val config = request.config
        require(config.sourceType == MediaSourceType.IMAGE) { "Image backend cannot open ${config.sourceType.serializedName}" }
        return ImageMediaSession(request, downloader.download(config.sourceUrl))
    }
}

class ImageDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    fun download(url: String): CompletableFuture<NativeImage> = CompletableFuture.supplyAsync {
        val bytes = fetch(MediaUrls.requireHttp(url))
        validateDimensions(bytes)
        NativeImage.read(bytes)
    }

    private fun fetch(initial: URI): ByteArray {
        var uri = initial
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            MediaNetworkPolicy.requirePublic(InetAddress.getAllByName(uri.host).asIterable())
            val request = HttpRequest.newBuilder(uri).timeout(READ_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/png,image/jpeg,image/*;q=0.8")
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                if (response.statusCode() in 300..399) {
                    require(redirectCount < MAX_REDIRECTS) { "Too many image redirects" }
                    val location = response.headers().firstValue("Location").orElseThrow {
                        IllegalArgumentException("Image redirect has no location")
                    }
                    uri = MediaUrls.requireHttp(uri.resolve(location).toString())
                    return@repeat
                }
                require(response.statusCode() in 200..299) { "Image request failed with HTTP ${response.statusCode()}" }
                val declared = response.headers().firstValueAsLong("Content-Length").orElse(-1)
                require(declared <= MAX_BYTES) { "Image exceeds $MAX_BYTES bytes" }
                val output = ByteArrayOutputStream(if (declared in 1..MAX_BYTES) declared.toInt() else 8192)
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = body.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Image exceeds $MAX_BYTES bytes" }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        }
        error("Image redirect loop")
    }

    private fun validateDimensions(bytes: ByteArray) {
        val encoded = MemoryUtil.memAlloc(bytes.size)
        try {
            encoded.put(bytes).flip()
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val components = stack.mallocInt(1)
                require(STBImage.stbi_info_from_memory(encoded, width, height, components)) {
                    "Unsupported or invalid image: ${STBImage.stbi_failure_reason()}"
                }
                val w = width[0]
                val h = height[0]
                require(w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION && w.toLong() * h <= MAX_PIXELS) {
                    "Image dimensions ${w}x$h exceed the limit"
                }
            }
        } finally {
            MemoryUtil.memFree(encoded)
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val MAX_DIMENSION = 8192
        const val MAX_PIXELS = 32L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val READ_TIMEOUT = Duration.ofSeconds(20)
        private const val USER_AGENT = "Differangle/1.0 (Minecraft media screen)"
    }
}

private class ImageMediaSession(
    override val request: MediaRequest,
    private val image: CompletableFuture<NativeImage>,
) : TextureMediaSession {
    private var texture: DynamicTexture? = null
    private var decodedReleased = false
    @Volatile private var closed = false
    override var failure: String? = null
        private set

    init {
        image.whenComplete { decoded, _ ->
            synchronized(this) {
                if (closed && decoded != null && !decodedReleased) {
                    decodedReleased = true
                    decoded.close()
                }
            }
        }
    }

    override fun tick() {}

    @Synchronized
    override fun textureView(): GpuTextureView? {
        RenderSystem.assertOnRenderThread()
        texture?.let { return it.textureView }
        if (closed || !image.isDone || image.isCompletedExceptionally || image.isCancelled) {
            if (image.isCompletedExceptionally && failure == null) recordFailure()
            return null
        }
        val decoded = image.getNow(null) ?: return null
        return try {
            DynamicTexture({ "Differangle image ${config.sourceUrl}" }, decoded).also { texture = it }.textureView
        } catch (cause: Throwable) {
            decodedReleased = true
            decoded.close()
            failure = cause.message ?: cause.javaClass.simpleName
            null
        }
    }

    private fun recordFailure() {
        try { image.join() } catch (cause: CompletionException) {
            val detail = cause.cause ?: cause
            failure = detail.message ?: detail.javaClass.simpleName
            LOGGER.warn("Could not load screen image {}: {}", config.sourceUrl, failure)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val currentTexture = texture
        currentTexture?.close()
        texture = null
        if (currentTexture == null && !decodedReleased && image.isDone && !image.isCompletedExceptionally && !image.isCancelled) {
            decodedReleased = true
            image.getNow(null)?.close()
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("Differangle")
    }
}
