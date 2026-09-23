package net.ninebolt.onevsone.infrastructure.paper

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale
import java.util.logging.Logger

/** Language files under dataFolder/messages/: bundled-file provisioning and bundle loading. */
internal object LanguageFiles {

    /** Languages shipped in the jar; additional languages come from dataFolder/messages/<lang>.yaml. */
    val BUNDLED_LANGS = listOf("ja", "en")

    fun dir(dataFolder: File): File = File(dataFolder, "messages")

    private fun bundledYaml(lang: String): YamlConfiguration? =
        LanguageFiles::class.java.getResourceAsStream("/messages/$lang.yaml")
            ?.bufferedReader()?.use { YamlConfiguration.loadConfiguration(it) }

    /**
     * Writes bundled files when absent (via saveResource) and backfills
     * keys added by plugin updates into existing files.
     */
    fun syncBundled(dataFolder: File, logger: Logger, saveResource: (String) -> Unit) {
        BUNDLED_LANGS.forEach { lang ->
            val path = "messages/$lang.yaml"
            val bundled = bundledYaml(lang) ?: return@forEach
            saveResource(path)
            val added = backfill(File(dataFolder, path), bundled)
            if (added > 0) logger.info("$path: appended $added new message key(s)")
        }
    }

    /**
     * Reads messagesDir/<lang>.{yaml,yml} merged over the bundled languages
     * (.yaml wins when both exist). Warns once per key a non-fallback
     * language lacks.
     */
    fun loadBundles(messagesDir: File, fallbackLang: String, logger: Logger): Map<String, Map<String, String>> {
        val bundled = BUNDLED_LANGS.mapNotNull { lang ->
            bundledYaml(lang)?.let { lang to flatten(it) }
        }.toMap()
        val overrides = messagesDir.listFiles { f -> f.name.matches(Regex(".+\\.ya?ml")) }
            ?.groupBy { it.nameWithoutExtension.lowercase(Locale.ROOT) }
            ?.mapValues { (_, files) ->
                flatten(YamlConfiguration.loadConfiguration(
                    files.firstOrNull { it.extension == "yaml" } ?: files.first()))
            } ?: emptyMap()
        val bundles = (bundled.keys + overrides.keys)
            .associateWith { (bundled[it] ?: emptyMap()) + (overrides[it] ?: emptyMap()) }
        (bundles[fallbackLang]?.keys ?: emptySet()).forEach { key ->
            bundles.filterKeys { it != fallbackLang }
                .filterValues { key !in it }
                .keys.forEach { lang -> logger.warning("language '$lang' is missing key '$key' (falls back to '$fallbackLang')") }
        }
        return bundles
    }

    /** Appends bundled keys missing from an existing file without rewriting its content. */
    fun backfill(file: File, bundled: YamlConfiguration): Int {
        val existing = YamlConfiguration.loadConfiguration(file)
        val patch = YamlConfiguration()
        bundled.getKeys(true)
            .filter { bundled.isString(it) && !existing.contains(it) }
            .forEach { patch.set(it, bundled.getString(it)) }
        val added = patch.getKeys(true).count { patch.isString(it) }
        if (added > 0) file.appendText("\n" + patch.saveToString())
        return added
    }

    /** Nested YAML keys become dot-separated (a.b.c) template keys. */
    private fun flatten(config: YamlConfiguration): Map<String, String> =
        config.getKeys(true).mapNotNull { key -> config.getString(key)?.let { key to it } }.toMap()
}
