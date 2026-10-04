package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.ArenaState
import java.util.logging.Level
import java.util.logging.Logger

class ArenaLifecycleService(
    private val sessions: ArenaSessions,
    private val arenaRepository: ArenaRepository,
    private val signs: ArenaSignService,
    private val recovery: InventoryRecoveryService,
    private val progression: MatchProgressionService,
    private val logger: Logger,
) {

    fun load() {
        val loaded = try {
            arenaRepository.loadAll()
        } catch (e: PersistenceException) {
            logger.log(Level.WARNING, "Arena definitions are unreadable; no arenaRepository loaded this session", e)
            emptyList()
        }
        loaded.forEach { arena ->
            sessions.installArena(arena)
            try {
                signs.refreshSign(arena.id, ArenaState.Waiting)
            } catch (e: PersistenceException) {
                logger.log(Level.WARNING, "Could not update sign for arena ${arena.id.name}; continuing startup", e)
            }
        }
    }

    fun shutdown() {
        sessions.matches().forEach { match ->
            val arenaId = match.arenaId
            progression.cancelCountdown(arenaId)
            sessions.transact(arenaId) { it.abort() }
        }
        recovery.restoreAllOnline()
    }
}
