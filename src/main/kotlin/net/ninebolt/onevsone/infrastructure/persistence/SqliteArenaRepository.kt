package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.EnableOutcome
import net.ninebolt.onevsone.domain.WorldPosition
import java.sql.ResultSet
import java.util.logging.Logger

// delete cascades to the arena's kit, sign, and match-status rows
class SqliteArenaRepository(
    private val store: SqliteStore,
    private val logger: Logger = Logger.getLogger(SqliteArenaRepository::class.java.name),
) : ArenaRepository {

    override fun loadAll(): List<Arena> = store.query("SELECT * FROM arenas ORDER BY seq") { row ->
        val name = row.getString("name")
        if (Arena.Id.of(name) == null) {
            logger.warning("Ignoring invalid arena name '$name' in arenas table")
            null
        } else {
            decode(row) ?: run {
                logger.warning("Arena '$name' could not be loaded; skipping")
                null
            }
        }
    }.filterNotNull()

    override fun find(name: String): Arena? {
        val id = Arena.Id.of(name) ?: return null
        return store.queryOne("SELECT * FROM arenas WHERE name = ?", name) { toArena(it) } ?: Arena.Disabled.new(id)
    }

    // seq is assigned only on first insert so updates keep the registration slot and stored casing
    override fun save(arena: Arena) {
        store.exec(
            """
            INSERT INTO arenas(name, enabled,
              spawn1_world, spawn1_x, spawn1_y, spawn1_z, spawn1_yaw, spawn1_pitch,
              spawn2_world, spawn2_x, spawn2_y, spawn2_z, spawn2_yaw, spawn2_pitch, seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
              (SELECT COALESCE(MAX(seq), 0) + 1 FROM arenas))
            ON CONFLICT(name) DO UPDATE SET
              enabled = excluded.enabled,
              spawn1_world = excluded.spawn1_world, spawn1_x = excluded.spawn1_x,
              spawn1_y = excluded.spawn1_y, spawn1_z = excluded.spawn1_z,
              spawn1_yaw = excluded.spawn1_yaw, spawn1_pitch = excluded.spawn1_pitch,
              spawn2_world = excluded.spawn2_world, spawn2_x = excluded.spawn2_x,
              spawn2_y = excluded.spawn2_y, spawn2_z = excluded.spawn2_z,
              spawn2_yaw = excluded.spawn2_yaw, spawn2_pitch = excluded.spawn2_pitch
            """.trimIndent(),
            arena.name,
            arena.enabled,
            *locationParams(arena.spawn1),
            *locationParams(arena.spawn2),
        )
    }

    override fun delete(name: String) {
        store.exec("DELETE FROM arenas WHERE name = ?", name)
    }

    // corrupt rows are skipped on load; direct reads let the store wrap the failure
    private fun decode(row: ResultSet): Arena? = try {
        toArena(row)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun toArena(row: ResultSet): Arena {
        val name = row.getString("name")
        val id = Arena.Id.of(name) ?: throw IllegalArgumentException("invalid arena name: '$name'")
        val disabled = Arena.Disabled.restored(
            id,
            readLocation(row, "spawn1"),
            readLocation(row, "spawn2"),
        )
        if (row.getInt("enabled") == 0) return disabled
        return when (val outcome = disabled.enable()) {
            is EnableOutcome.Ready -> outcome.arena

            is EnableOutcome.MissingSpawns -> {
                logger.warning(
                    "Arena '${id.name}' is marked enabled but spawn " +
                        outcome.slots.joinToString(", ") { it.number.toString() } +
                        " is missing; loading as disabled",
                )
                disabled
            }
        }
    }

    private fun readLocation(row: ResultSet, prefix: String): WorldPosition? {
        val world = row.getString("${prefix}_world") ?: return null
        return WorldPosition.new(
            world = world,
            x = row.getDouble("${prefix}_x"),
            y = row.getDouble("${prefix}_y"),
            z = row.getDouble("${prefix}_z"),
            yaw = row.getDouble("${prefix}_yaw").toFloat(),
            pitch = row.getDouble("${prefix}_pitch").toFloat(),
        )
    }

    private fun locationParams(loc: WorldPosition?): Array<Any?> = if (loc == null) {
        arrayOf(null, null, null, null, null, null)
    } else {
        arrayOf(loc.world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
    }
}
