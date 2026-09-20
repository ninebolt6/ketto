package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.infrastructure.persistence.PersistedBackup
import net.ninebolt.onevsone.infrastructure.persistence.YamlBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.YamlKitStore
import org.bukkit.entity.Player
import kotlin.uuid.Uuid

/**
 * インベントリ実データのアダプター。バックアップ/キットの ItemStack は
 * PaperInventorySnapshot としてこの層に閉じ込める。
 */
class PaperEquipmentAdapter(
    private val backups: YamlBackupStore,
    private val kitStore: YamlKitStore,
    private val lookup: PaperPlayerLookup
) : KitPort, InventoryBackupPort {

    /** アリーナ装備のメモリキャッシュ(arena/<name>.yml の inventory)。 */
    private val kits = mutableMapOf<Arena.Id, PaperInventorySnapshot>()

    /** 稼働中に取得/読み込みしたバックアップ実データ(backupId → snapshot)。 */
    private val pendingSnapshots = mutableMapOf<Uuid, PaperInventorySnapshot>()

    /** テスト・起動時プリロード用。 */
    internal fun putKit(arena: Arena.Id, kit: PaperInventorySnapshot) {
        kits[arena] = kit
    }

    /** キャッシュ済みのアリーナ装備(テスト検証用。未設定時は null)。 */
    internal fun kitOf(arena: Arena.Id): PaperInventorySnapshot? = kits[arena]

    override fun forgetKit(arena: Arena.Id) {
        kits.remove(arena)
    }

    /**
     * 両者の持ち物を複製して一括永続化。複製は持ち物を変更しない。
     * 保存に失敗したら PersistenceFailure を投げ、誰の持ち物も変更しない。
     */
    override fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef> {
        val captured = participants.map { participant ->
            val player = lookup.resolve(participant.id)
                ?: throw PersistenceFailure("Player ${participant.name} (${participant.id}) is not available for inventory backup")
            PersistedBackup(
                BackupRef.new(
                    matchId = match,
                    playerId = participant.id,
                    playerName = participant.name
                ),
                PaperInventorySnapshot.capture(player.inventory)
            )
        }
        backups.saveBackups(captured)
        captured.forEach { (ref, snapshot) -> pendingSnapshots[ref.backupId] = snapshot }
        return captured.map { it.ref }
    }

    /** バックアップへ復元。空スナップショットは空インベントリへ戻すだけ。 */
    override fun restore(backup: BackupRef) {
        val snapshot = pendingSnapshots[backup.backupId]
            ?: backups.backupFor(backup)?.snapshot
            ?: throw PersistenceFailure("No stored backup ${backup.backupId} for ${backup.playerName}")
        val player = resolve(backup)
            ?: throw PersistenceFailure("Player ${backup.playerName} is not available for restore")
        snapshot.apply(player.inventory)
    }

    private fun resolve(backup: BackupRef): Player? =
        backup.playerId?.let { lookup.resolve(it) } ?: lookup.resolveByName(backup.playerName)

    /** backupId が一致する記録だけを消す。 */
    override fun acknowledge(backup: BackupRef) {
        backups.deleteBackup(backup)
        pendingSnapshots.remove(backup.backupId)
    }

    override fun pendingBackups(): List<BackupRef> =
        backups.persistedBackups().onEach { pendingSnapshots[it.ref.backupId] = it.snapshot }.map { it.ref }

    override fun applyKit(arena: Arena.Id, playerId: Uuid) {
        val player = lookup.resolve(playerId)
            ?: throw PersistenceFailure("Player $playerId is not available for kit apply")
        kit(arena).apply(player.inventory)
    }

    override fun saveKit(arena: Arena.Id, playerId: Uuid) {
        val player = lookup.resolve(playerId)
            ?: throw PersistenceFailure("Player $playerId is not available for kit capture")
        val kit = PaperInventorySnapshot.capture(player.inventory)
        kitStore.saveArenaKit(arena.name, kit)
        kits[arena] = kit
    }

    private fun kit(arena: Arena.Id): PaperInventorySnapshot =
        kits.getOrPut(arena) { kitStore.loadArenaKit(arena.name) }
}
