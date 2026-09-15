package net.astrorbits.differangle.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.astrorbits.differangle.camera.CameraMode
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Client-wide preferences, independent of worlds and screen NBT. */
internal class ClientConfig {
    private val path = FabricLoader.getInstance().configDir.resolve("differangle.json")
    private val logger = LoggerFactory.getLogger("Differangle")
    private val data: JsonObject = try {
        if (Files.exists(path)) Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
        else JsonObject()
    } catch (failure: Exception) {
        logger.warn("Could not read Differangle client config; using defaults", failure)
        JsonObject()
    }

    fun loadMode(): CameraMode {
        val value = data.get("renderMode")
        return CameraMode.entries.firstOrNull {
            value?.isJsonPrimitive == true && value.asJsonPrimitive.isString &&
                it.commandName.equals(value.asString, ignoreCase = true)
        } ?: CameraMode.TEXTURE
    }

    fun saveMode(mode: CameraMode) {
        save(data.deepCopy().apply { addProperty("renderMode", mode.commandName) })
    }

    fun loadCameraShaders(): Boolean = data.get("cameraShaders")?.let {
        if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else true
    } ?: true

    fun saveCameraShaders(enabled: Boolean) {
        save(data.deepCopy().apply { addProperty("cameraShaders", enabled) })
    }

    private fun save(updated: JsonObject) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "differangle-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { GsonBuilder().setPrettyPrinting().create().toJson(updated, it) }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            updated.entrySet().forEach { (key, value) -> data.add(key, value) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
