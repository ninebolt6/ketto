package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.MatchId
import kotlin.uuid.Uuid

data class BackupRef private constructor(
    val backupId: Uuid,
    val matchId: MatchId,
    val playerId: Uuid,
    val playerName: String,
) {
    companion object {
        fun new(matchId: MatchId, playerId: Uuid, playerName: String): BackupRef = BackupRef(Uuid.random(), matchId, playerId, playerName)

        fun restored(backupId: Uuid, matchId: MatchId, playerId: Uuid, playerName: String): BackupRef = BackupRef(backupId, matchId, playerId, playerName)
    }
}
