package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.warnOnFailure
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.Participant

/**
 * Startup and shutdown processing: installs persisted arenas into the
 * registry with their status and sign refreshed, and on shutdown aborts every
 * running match and restores online pending restores.
 * All operations are assumed to be serialized on the main thread.
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
            failures.warn("arenalist.yml is unreadable; no arenas loaded this session")
            emptyList()
        }
        loaded.forEach { arena ->
            registry.installArena(arena)
            failures.warnOnFailure("Could not persist status for arena ${arena.id.name}; continuing startup") {
                registry.match(arena.id)?.let { sync.saveStatus(it) }
            }
            failures.warnOnFailure("Could not update sign for arena ${arena.id.name}; continuing startup") {
                sync.refreshSign(arena.id, ArenaState.WAITING)
            }
        }
        failures.warnOnFailure("players.yml is unreadable; pending restores unavailable this session") {
            recovery.loadPersisted()
        }
        failures.warnOnFailure("Could not clear stale players.yml registrations") {
            sync.clearRegistrations()
        }
    }

    fun shutdown() {
        registry.matches().forEach { match ->
            val arenaId = match.arenaId
            progression.cancelCountdown(arenaId)
            val left = registry.transact(arenaId) { it.abort() }?.outcome ?: emptyList()
            left.forEach { unregister(it) }
            failures.warnOnFailure("Could not persist shutdown state for arena $arenaId; continuing shutdown") {
                registry.match(arenaId)?.let { sync.saveStatus(it) }
            }
        }
        recovery.restoreAllOnline()
    }

    /** Ledger-unregister failures are swallowed into a warn so later processing continues. */
    private fun unregister(participant: Participant) =
        failures.warnOnFailure("Could not unregister ${participant.name} from players.yml; membership record may be stale") {
            sync.unregister(participant)
        }
}
