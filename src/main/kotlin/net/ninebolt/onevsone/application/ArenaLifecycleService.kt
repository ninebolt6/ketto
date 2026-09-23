package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.warnOnFailure
import net.ninebolt.onevsone.domain.ArenaState

/**
 * Startup and shutdown processing: installs persisted arenas into the
 * registry with their status and sign refreshed, and on shutdown aborts every
 * running match and restores online pending restores.
 *
 * Startup persistence work is isolated per item under warnOnFailure so one
 * bad record cannot break the rest of the load. Shutdown aborts commit
 * through the registry persist hook, so the ledger and status projection are
 * rewritten in the same step that clears the match.
 */
class ArenaLifecycleService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val sync: MatchStateSync,
    private val recovery: PlayerRecoveryService,
    private val progression: MatchProgressionService,
    private val failures: FailureReporter
) {

    fun load() {
        val loaded = try {
            arenas.loadAll()
        } catch (e: PersistenceFailure) {
            failures.warn("Arena definitions are unreadable; no arenas loaded this session")
            emptyList()
        }
        loaded.forEach { arena ->
            // The definition is already persisted; only the projection and sign are refreshed
            registry.installArena(arena, persist = {})
            failures.warnOnFailure("Could not persist status for arena ${arena.id.name}; continuing startup") {
                registry.match(arena.id)?.let { sync.saveStatus(it) }
            }
            failures.warnOnFailure("Could not update sign for arena ${arena.id.name}; continuing startup") {
                sync.refreshSign(arena.id, ArenaState.WAITING)
            }
        }
        failures.warnOnFailure("Persisted backups are unreadable; pending restores unavailable this session") {
            recovery.loadPersisted()
        }
        failures.warnOnFailure("Could not clear stale registrations") {
            sync.clearRegistrations()
        }
    }

    fun shutdown() {
        registry.matches().forEach { match ->
            val arenaId = match.arenaId
            progression.cancelCountdown(arenaId)
            registry.transact(arenaId, persist = sync::persistMatch) { it.abort() }
        }
        recovery.restoreAllOnline()
    }
}
