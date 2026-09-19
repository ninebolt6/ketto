package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.MockKMatcherScope
import io.mockk.slot
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** TestEnv の観測系ヘルパー。マッチャと読み取り系のみを集める。 */

private val legacy = LegacyComponentSerializer.legacySection()

internal fun MockKMatcherScope.contains(part: String): String = match { it.contains(part) }

internal fun TestEnv.lastBroadcast(): String {
    val slot = slot<Component>()
    verify(exactly = 1) { server.broadcast(capture(slot)) }
    return legacy.serialize(slot.captured)
}

internal fun TestEnv.view(name: String = "arena1") = service.matchOf(name)!!

internal fun TestEnv.playersYaml() =
    YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
