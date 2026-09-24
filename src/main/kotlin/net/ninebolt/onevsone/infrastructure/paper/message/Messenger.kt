package net.ninebolt.onevsone.infrastructure.paper.message

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File
import java.util.Locale
import java.util.logging.Logger

class Messenger private constructor(
    private val bundles: Map<String, Map<String, String>>,
    private val fallbackLang: String,
    private val chatLang: String?,
    private val logger: Logger,
) {
    private val mini = MiniMessage.miniMessage()
    private val warnedMissing = mutableSetOf<String>()

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

    private fun template(lang: String, key: String): String = bundles[lang]?.get(key) ?: bundles[fallbackLang]?.get(key) ?: run {
        if (warnedMissing.add("$lang:$key")) {
            logger.warning("Missing message key '$key' for lang '$lang'")
        }
        key
    }

    fun render(message: Message, lang: String = serverLang): Component = mini.deserialize(
        template(lang, message.key.name),
        *message.args.map { arg ->
            when (arg) {
                is Message.Str -> Placeholder.unparsed(arg.name, arg.value)
                is Message.Nested -> Placeholder.component(arg.name, render(arg.message, lang))
            }
        }.toTypedArray<TagResolver>(),
    )

    fun send(sender: CommandSender, message: Message) {
        val lang = localeOf(sender)
        sender.sendMessage(render(Message.Prefix, lang).append(render(message, lang)))
    }

    fun broadcast(server: Server, message: Message) {
        server.onlinePlayers.forEach { send(it, message) }
        server.consoleSender.sendMessage(render(Message.Prefix).append(render(message)))
    }

    companion object {
        fun load(messagesDir: File, fallbackLang: String, language: String, logger: Logger): Messenger = Messenger(
            LanguageFiles.loadBundles(messagesDir, fallbackLang, logger),
            fallbackLang,
            if (language.equals("auto", ignoreCase = true)) null else language,
            logger,
        )
    }
}
