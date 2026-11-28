package net.ninebolt.ketto.infrastructure.paper

import net.ninebolt.ketto.application.port.WorldPort
import org.bukkit.Server

class PaperWorlds(private val server: Server) : WorldPort {
    override fun isLoaded(worldName: String): Boolean = server.getWorld(worldName) != null
}
