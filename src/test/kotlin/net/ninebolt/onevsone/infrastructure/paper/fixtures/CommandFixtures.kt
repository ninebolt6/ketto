package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.mockk
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

/** OneVsOneCommand テスト用のコマンド実行・stats 投入・OP プレイヤーフィクスチャ。 */

internal fun TestEnv.run(sender: CommandSender, vararg args: String) =
    command.onCommand(sender, mockk<Command>(relaxed = true), "1vs1", arrayOf(*args))

internal fun TestEnv.writeStats(uuid: Uuid, win: Int, lose: Int) {
    repeat(win) { statsRepo.recordWin(uuid) }
    repeat(lose) { statsRepo.recordLoss(uuid) }
}

internal fun TestEnv.opPlayer(name: String): ArenaPlayerMock = player(name).also { it.isOp = true }

// getOfflinePlayer(name) の @Deprecated は Bukkit 上流由来で Paper では除去済み。
// ServerMock は上流の注釈を残しているため Server 型経由で呼ぶ
internal fun TestEnv.offlineId(name: String): Uuid =
    (server as Server).getOfflinePlayer(name).uniqueId.toKotlinUuid()
