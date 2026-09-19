package net.ninebolt.onevsone

import org.bukkit.plugin.java.JavaPlugin

class OneVsOnePlugin : JavaPlugin() {

    var service: ArenaService? = null
        private set

    override fun onEnable() {
        saveDefaultConfig()
        val store = YamlStore(dataFolder, logger)
        val messages = Messages(config.getString("prefix") ?: "&8[&61vs1&8] ")
        val service = ArenaService(this, store, messages)
        this.service = service
        service.load()
        val executor = OneVsOneCommand(this, service, messages)
        val command = getCommand("1vs1")
        command?.setExecutor(executor)
        command?.tabCompleter = executor
        server.pluginManager.registerEvents(ArenaListener(service, messages), this)
    }

    override fun onDisable() {
        service?.shutdown()
    }
}
