package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.Arena

interface ArenaRepository {
    // entries with invalid names, duplicates, or unreadable data are skipped
    fun loadAll(): List<Arena>

    // unregistered arenas are appended to the end of the registration list
    fun save(arena: Arena)

    // also removes the arena's related persistent data
    fun delete(id: Arena.Id)
}
