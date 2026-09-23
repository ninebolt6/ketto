package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.Locale
import java.util.logging.Logger

/**
 * Message infrastructure rendering MiniMessage templates from
 * messages/<lang>.yaml. Messages are Message subtypes. send/broadcast render
 * in the recipient's locale (the client setting when language=auto); shared
 * surfaces such as signs, item names, and the scoreboard use render (the
 * server language).
 */
class Messenger private constructor(
    private val bundles: Map<String, Map<String, String>>,
    private val fallbackLang: String,
    private val chatLang: String?,
    private val logger: Logger
) {
    private val mini = MiniMessage.miniMessage()
    private val warnedMissing = mutableSetOf<String>()

    /** Language for shared surfaces and console. The fixed language in fixed mode, the fallback language in auto. */
    private val serverLang = chatLang ?: fallbackLang

    private fun localeOf(sender: CommandSender): String {
        if (chatLang != null) return chatLang
        val locale = (sender as? Player)?.let { runCatching { it.locale() }.getOrNull() } ?: return fallbackLang
        val langTag = locale.toString().lowercase(Locale.ROOT)
        return when {
            langTag in bundles -> langTag
            locale.language in bundles -> locale.language
            else -> fallbackLang
        }
    }

    private fun template(lang: String, key: String): String =
        bundles[lang]?.get(key) ?: bundles[fallbackLang]?.get(key) ?: run {
            if (warnedMissing.add("$lang:$key")) {
                logger.warning("Missing message key '$key' for lang '$lang'")
            }
            key
        }

    /** Renders message. When lang is omitted, uses the server language (for shared surfaces like signs and items). */
    fun render(message: Message, lang: String = serverLang): Component =
        mini.deserialize(template(lang, message.key.name), *message.args.map { arg ->
            when (arg) {
                is Message.Str -> Placeholder.unparsed(arg.name, arg.value)
                is Message.Nested -> Placeholder.component(arg.name, render(arg.message, lang))
            }
        }.toTypedArray<TagResolver>())

    /** Renders a bare key with no placeholders. For tests that enumerate keys. */
    internal fun render(key: MessageKey, lang: String = serverLang): Component =
        mini.deserialize(template(lang, key.name))

    fun send(sender: CommandSender, message: Message) {
        val lang = localeOf(sender)
        sender.sendMessage(render(Message.Prefix, lang).append(render(message, lang)))
    }

    /** Players get their own locale; the console gets the server language. */
    fun broadcast(server: Server, message: Message) {
        server.onlinePlayers.forEach { send(it, message) }
        server.consoleSender.sendMessage(render(Message.Prefix).append(render(message)))
    }

    companion object {
        /** Default languages bundled in the jar. User-added languages load from dataFolder/messages/<lang>.yaml. */
        private val BUNDLED_LANGS = listOf("ja", "en")

        /**
         * Loads messagesDir/<lang>.{yaml,yml} merged per language over the
         * bundled languages; when both extensions exist for a language, .yaml
         * wins. "auto" renders per recipient locale; anything else pins all
         * recipients to that language.
         */
        fun load(messagesDir: File, fallbackLang: String, language: String, logger: Logger): Messenger {
            val bundled = BUNDLED_LANGS.mapNotNull { lang ->
                val yaml = Messenger::class.java.getResourceAsStream("/messages/$lang.yaml")
                    ?.bufferedReader()?.use { YamlConfiguration.loadConfiguration(it) }
                    ?: return@mapNotNull null
                lang to flatten(yaml)
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
            return Messenger(bundles, fallbackLang, if (language.equals("auto", ignoreCase = true)) null else language, logger)
        }

        /** Flattens nested YAML into a map of a.b.c keys to templates. */
        private fun flatten(config: YamlConfiguration): Map<String, String> =
            config.getKeys(true).mapNotNull { key -> config.getString(key)?.let { key to it } }.toMap()
    }
}
