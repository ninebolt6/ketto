package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.MatchId
import kotlin.uuid.Uuid

/**
 * Identifier of an inventory backup taken just before a match starts.
 * The payload (ItemStack-derived) is confined outside the port (in
 * infrastructure); inner layers only pass this metadata around.
 *
 * playerId can be null for backups without a recorded uuid.
 */
data class BackupRef private constructor(
    val backupId: Uuid,
    val matchId: MatchId,
    val playerId: Uuid?,
    val playerName: String
) {
    companion object {
        /** A new backup. backupId is generated internally. */
        fun new(matchId: MatchId, playerId: Uuid?, playerName: String): BackupRef =
            BackupRef(Uuid.random(), matchId, playerId, playerName)

        fun restored(backupId: Uuid, matchId: MatchId, playerId: Uuid?, playerName: String): BackupRef =
            BackupRef(backupId, matchId, playerId, playerName)
    }
}
