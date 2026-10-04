package net.ninebolt.ketto.infrastructure.persistence.fixtures

import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.WorldPosition
import net.ninebolt.ketto.domain.fixtures.arenaId
import net.ninebolt.ketto.infrastructure.persistence.SqliteStore
import java.io.File
import java.util.logging.Logger

internal fun store(folder: File) = SqliteStore(folder, Logger.getLogger("test"))

internal fun <T> withStore(folder: File, block: (SqliteStore) -> T): T = store(folder).use(block)

internal fun enabledArena(name: String): Arena = Arena.Enabled.restored(
    arenaId(name),
    WorldPosition.new("world", 1.0, 64.0, 1.0),
    WorldPosition.new("world", 2.0, 64.0, 2.0),
)

internal fun countRows(store: SqliteStore, table: String, where: String = "", vararg params: Any?): Int = store.queryOne("SELECT COUNT(*) AS c FROM $table $where", *params) { it.getInt("c") }!!
