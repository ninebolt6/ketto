package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.MockKMatcherScope
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.mockbukkit.mockbukkit.command.MessageTarget
import java.io.File

/** Observation helpers for TestEnv. Only collects reads of already-sent messages. */

private val plain = PlainTextComponentSerializer.plainText()

/** Matcher for verifying String arguments (Logger etc., for fault-injection mocks). */
internal fun MockKMatcherScope.containsText(part: String): String = match { it.contains(part) }

/** Reads out all sent messages. The queue is consumed, so later calls see only new ones. */
internal fun MessageTarget.drainMessages(): List<String> =
    generateSequence { nextComponentMessage() }.map(plain::serialize).toList()

internal fun TestEnv.lastBroadcast(): String =
    server.consoleSender.drainMessages().lastOrNull() ?: error("no broadcast captured")

internal fun TestEnv.view(name: String = "arena1") = service.matchOf(name)!!

internal fun TestEnv.playersYaml() =
    YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
