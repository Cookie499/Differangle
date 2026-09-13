package net.astrorbits.differangle.camera

enum class CameraLayer(val commandName: String) {
    TRANSLUCENT("translucent"), ENTITIES("entities"), BLOCK_ENTITIES("block_entities"),
    PARTICLES("particles"), WEATHER("weather"), CLOUDS("clouds");
}

class CameraLayers {
    private val enabled = CameraLayer.entries.toMutableSet()
    operator fun contains(layer: CameraLayer) = layer in enabled
    fun set(layer: CameraLayer, value: Boolean) { if (value) enabled.add(layer) else enabled.remove(layer) }
    val hasNativeContent get() = enabled.any { it != CameraLayer.TRANSLUCENT }
    fun summary() = CameraLayer.entries.joinToString { "${it.commandName}=${it in enabled}" }
}
