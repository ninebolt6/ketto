package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.BackupRef
import net.ninebolt.ketto.application.port.PersistenceException
import org.bukkit.GameMode
import org.bukkit.configuration.file.YamlConfiguration

internal object BackupPayloadCodec {

    fun encode(backup: PersistedBackup): String {
        val yaml = YamlConfiguration()
        InventoryPayloadCodec.writeSnapshot(yaml, backup.snapshot)
        backup.gameMode?.let { yaml.set("game-mode", it.name) }
        backup.allowFlight?.let { yaml.set("allow-flight", it) }
        return yaml.saveToString()
    }

    fun decode(payload: String, ref: BackupRef): PersistedBackup {
        try {
            val yaml = InventoryPayloadCodec.load(payload)
            return PersistedBackup(
                ref = ref,
                snapshot = InventoryPayloadCodec.readSnapshot(yaml),
                gameMode = decodeGameMode(yaml.get("game-mode")),
                allowFlight = decodeAllowFlight(yaml.get("allow-flight")),
            )
        } catch (e: Exception) {
            throw PersistenceException("Could not decode backup payload", e)
        }
    }

    private fun decodeGameMode(value: Any?): GameMode? = when (value) {
        null -> null
        is String -> GameMode.valueOf(value)
        else -> throw IllegalArgumentException("Unexpected backup game mode: $value")
    }

    private fun decodeAllowFlight(value: Any?): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        else -> throw IllegalArgumentException("Unexpected backup flight permission: $value")
    }
}
