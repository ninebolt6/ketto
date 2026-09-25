package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.warnOnFailure
import net.ninebolt.onevsone.domain.ArenaState
import java.util.logging.Logger

class ArenaLifecycleService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val sync: MatchStateSync,
    private val recovery: PlayerRecoveryService,
    private val progression: MatchProgressionService,
    private val logger: Logger,
) {

    fun load() {
        val loaded = try {
            arenas.loadAll()
        } catch (e: PersistenceFailure) {
            logger.warning("Arena definitions are unreadable; no arenas loaded this session")
            emptyList()
        }
        loaded.forEach { arena ->
            // Loaded definitions are already persisted, so install saves nothing
            val match = registry.installArena(arena, persist = {})
            logger.warnOnFailure("Could not persist status for arena ${arena.id.name}; continuing startup") {
                sync.saveStatus(match)
            }
            logger.warnOnFailure("Could not update sign for arena ${arena.id.name}; continuing startup") {
                sync.refreshSign(arena.id, ArenaState.WAITING)
            }
        }
        logger.warnOnFailure("Persisted backups are unreadable; pending restores unavailable this session") {
            recovery.loadPersisted()
        }
        logger.warnOnFailure("Could not clear stale registrations") {
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
