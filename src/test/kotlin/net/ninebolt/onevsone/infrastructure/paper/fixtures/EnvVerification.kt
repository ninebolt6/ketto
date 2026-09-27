package net.ninebolt.onevsone.infrastructure.paper.fixtures

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.mockbukkit.mockbukkit.command.MessageTarget

private val plain = PlainTextComponentSerializer.plainText()

internal fun MessageTarget.drainMessages(): List<String> = generateSequence { nextComponentMessage() }.map(plain::serialize).toList()

internal fun TestEnv.lastBroadcast(): String = server.consoleSender.drainMessages().lastOrNull() ?: error("no broadcast captured")

internal fun TestEnv.view(name: String = "arena1") = service.matchOf(name)!!

internal data class BackupRow(val backupId: String, val matchId: String, val playerUuid: String?, val playerName: String)

internal fun TestEnv.backupByName(playerName: String): BackupRow? = store.queryOne("SELECT backup_id, match_id, player_uuid, player_name FROM backups WHERE player_name = ?", playerName) { row ->
    BackupRow(
        row.getString("backup_id"),
        row.getString("match_id"),
        row.getString("player_uuid"),
        row.getString("player_name"),
    )
}
