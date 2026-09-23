package net.ninebolt.onevsone.infrastructure.paper

import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

/** Typed view over config.yml; owns key names, defaults, and validation. */
internal class PluginSettings private constructor(
    val requiredWins: Int,
    val defaultLanguage: String,
    val language: String
) {
    companion object {
        fun load(config: FileConfiguration, logger: Logger): PluginSettings {
            val wins = config.getInt("required-wins", 3)
            if (wins < 1) logger.warning("required-wins must be >= 1 (was $wins); using 1")
            return PluginSettings(
                requiredWins = wins.coerceAtLeast(1),
                defaultLanguage = config.getString("default-language") ?: "en",
                language = config.getString("language") ?: "auto"
            )
        }
    }
}
