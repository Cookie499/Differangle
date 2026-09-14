package net.astrorbits.differangle

import net.fabricmc.api.ModInitializer

class Differangle : ModInitializer {

    override fun onInitialize() {
        net.astrorbits.differangle.world.WorldResources.initialize()
    }
}
