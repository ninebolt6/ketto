package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.mockk
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

internal fun TestEnv.run(sender: CommandSender, vararg args: String) = command.onCommand(sender, mockk<Command>(relaxed = true), "1vs1", arrayOf(*args))

internal fun TestEnv.tab(sender: CommandSender, vararg args: String) = command.onTabComplete(sender, mockk<Command>(relaxed = true), "1vs1", arrayOf(*args))

internal fun TestEnv.writeStats(uuid: Uuid, win: Int, lose: Int) {
    repeat(win) { statsRepo.recordWin(uuid) }
    repeat(lose) { statsRepo.recordLoss(uuid) }
}

internal fun TestEnv.opPlayer(name: String): ArenaPlayerMock = player(name).also { it.isOp = true }

// ServerMock carries the upstream @Deprecated on getOfflinePlayer(name) that Paper removed, so call it through the Server type
internal fun TestEnv.offlineId(name: String): Uuid = (server as Server).getOfflinePlayer(name).uniqueId.toKotlinUuid()
