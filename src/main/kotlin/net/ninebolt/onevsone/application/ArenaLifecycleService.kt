package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.ArenaState
import java.util.logging.Level
import java.util.logging.Logger

class ArenaLifecycleService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val signs: ArenaSignService,
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
            registry.installArena(arena)
            try {
                signs.refreshSign(arena.id, ArenaState.Waiting)
            } catch (e: PersistenceFailure) {
                logger.log(Level.WARNING, "Could not update sign for arena ${arena.id.name}; continuing startup", e)
            }
        }
    }

    fun shutdown() {
        registry.matches().forEach { match ->
            val arenaId = match.arenaId
            progression.cancelCountdown(arenaId)
            registry.transact(arenaId) { it.abort() }
        }
        recovery.restoreAllOnline()
    }
}
