package net.ninebolt.onevsone.infrastructure.paper.fixtures

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.mockbukkit.mockbukkit.command.MessageTarget

private val plain = PlainTextComponentSerializer.plainText()

internal fun MessageTarget.drainMessages(): List<String> = generateSequence { nextComponentMessage() }.map(plain::serialize).toList()

internal fun TestEnv.lastBroadcast(): String = server.consoleSender.drainMessages().lastOrNull() ?: error("no broadcast captured")

internal fun TestEnv.view(name: String = "arena1") = service.matchOf(name)!!

internal data class RegistrationRow(val playerUuid: String, val playerName: String, val arenaName: String)

internal data class BackupRow(val backupId: String, val matchId: String, val playerUuid: String?, val playerName: String)

internal data class StatusRow(val state: String, val players: List<String>)

internal fun TestEnv.registrations(): List<RegistrationRow> = store.query("SELECT player_uuid, player_name, arena_name FROM registrations ORDER BY rowid") { row ->
    RegistrationRow(row.getString("player_uuid"), row.getString("player_name"), row.getString("arena_name"))
}

internal fun TestEnv.backupByName(playerName: String): BackupRow? = store.queryOne("SELECT backup_id, match_id, player_uuid, player_name FROM backups WHERE player_name = ?", playerName) { row ->
    BackupRow(
        row.getString("backup_id"),
        row.getString("match_id"),
        row.getString("player_uuid"),
        row.getString("player_name"),
    )
}

internal fun TestEnv.statusOf(arena: String): StatusRow? = store.queryOne("SELECT state, players FROM match_status WHERE arena_name = ?", arena) { row ->
    val players = row.getString("players")
    StatusRow(row.getString("state"), if (players.isEmpty()) emptyList() else players.split(","))
}
