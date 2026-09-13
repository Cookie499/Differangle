package net.astrorbits.differangle.client

import net.fabricmc.api.ClientModInitializer
import net.astrorbits.differangle.client.render.CameraPipelines
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener

class DifferangleClient : ClientModInitializer {

    override fun onInitializeClient() {
        CameraPipelines.initialize()
        net.astrorbits.differangle.client.render.EmbeddedNativePipelines.initialize()
        CameraCommands.register(runtime)
        ClientTickEvents.END_CLIENT_TICK.register { runtime.tick(it) }
        LevelRenderEvents.AFTER_SOLID_FEATURES.register { runtime.render(it) }
        ClientLifecycleEvents.CLIENT_STOPPING.register { runtime.close() }
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath("differangle", "camera_resources"),
            ResourceManagerReloadListener { runtime.reloadResources() },
        )
    }

    companion object {
        val runtime = CameraRuntime()
        val cameraSystem get() = runtime.system
    }
}
