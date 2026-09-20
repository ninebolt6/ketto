package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.mockk
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import kotlin.uuid.Uuid

/** OneVsOneCommand テスト用のコマンド実行・stats 投入・OP プレイヤーフィクスチャ。 */

internal fun TestEnv.run(sender: CommandSender, vararg args: String) =
    command.onCommand(sender, mockk<Command>(relaxed = true), "1vs1", arrayOf(*args))

internal fun TestEnv.writeStats(uuid: Uuid, win: Int, lose: Int) {
    repeat(win) { statsRepo.recordWin(uuid) }
    repeat(lose) { statsRepo.recordLoss(uuid) }
}

internal fun TestEnv.opPlayer(name: String): ArenaPlayerMock = player(name).also { it.isOp = true }
