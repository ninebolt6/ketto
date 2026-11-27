package net.ninebolt.ketto.application.fixtures

import net.ninebolt.ketto.application.port.WorldPort

class FakeWorlds : WorldPort {
    val unloaded = mutableSetOf<String>()

    override fun isLoaded(worldName: String): Boolean = worldName !in unloaded
}
