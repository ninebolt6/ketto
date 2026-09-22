package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena

/**
 * Persistence of arena definitions.
 * Does not handle kit contents — KitPort touches them via the arena ID.
 * The lobby is LobbyRepository's job; sign coordinates are ArenaSignRepository's.
 * The persistence format (index file vs per-file layout) is an internal detail
 * of the implementation.
 */
interface ArenaRepository {
    /** All arenas in registration order. Entries with invalid names, duplicates, or unreadable data are skipped. */
    fun loadAll(): List<Arena>

    /** Invalid names yield null. Corrupt files throw PersistenceFailure. A file not yet created returns a default arena. */
    fun find(name: String): Arena?

    /** Saves an arena, appending it to the end of the registration list if unregistered. */
    fun save(arena: Arena)

    /** Deletes the definition and its related persistent data, and removes it from the registration list. */
    fun delete(name: String)
}
