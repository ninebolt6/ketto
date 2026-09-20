package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.MockKMatcherScope
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.mockbukkit.mockbukkit.command.MessageTarget
import java.io.File

/** TestEnv の観測系ヘルパー。送信済みメッセージの読み出しのみを集める。 */

private val plain = PlainTextComponentSerializer.plainText()

/** 文字列引数(Logger 等、障害注入モック向け)を検証するマッチャ。 */
internal fun MockKMatcherScope.containsText(part: String): String = match { it.contains(part) }

/** 送信済みメッセージを全て読み出す。キューは消費されるので以後の呼出は新規分のみ見える。 */
internal fun MessageTarget.drainMessages(): List<String> =
    generateSequence { nextComponentMessage() }.map(plain::serialize).toList()

internal fun TestEnv.lastBroadcast(): String =
    server.consoleSender.drainMessages().lastOrNull() ?: error("no broadcast captured")

internal fun TestEnv.view(name: String = "arena1") = service.matchOf(name)!!

internal fun TestEnv.playersYaml() =
    YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
