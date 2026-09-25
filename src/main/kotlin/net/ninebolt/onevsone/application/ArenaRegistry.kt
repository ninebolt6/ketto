package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Transition
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

// Ordering contract: each mutator's persist hook runs before the in-memory write and touches persistence only; arena persist failures propagate, match persist failures are only reported
class ArenaRegistry(
    private val requiredWins: Int,
    private val logger: Logger,
) {

    private data class Slot(val arena: Arena, val match: ArenaMatch)

    private val slots = linkedMapOf<Arena.Id, Slot>()
    private val playerArena = mutableMapOf<Uuid, Arena.Id>()

    fun arena(id: Arena.Id): Arena? = slots[id]?.arena

    // Case-insensitive fallback is unambiguous because create rejects case-insensitive duplicate names
    fun resolveArenaId(name: String): Arena.Id? = Arena.Id.of(name)?.takeIf { slots.containsKey(it) }
        ?: slots.keys.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun resolveArena(name: String): Arena? = resolveArenaId(name)?.let { slots[it]?.arena }

    fun resolveEntry(name: String): Pair<Arena, ArenaMatch>? = resolveArenaId(name)?.let(::entry)

    fun arenaIds(): List<Arena.Id> = slots.keys.toList()

    fun installArena(arena: Arena, persist: (Arena) -> Unit) {
        persist(arena)
        val match = ArenaMatch.new(arena.id, requiredWins)
        val previous = slots.put(arena.id, Slot(arena, match))
        reconcileIndex(arena.id, previous?.match, match)
    }

    fun removeArena(id: Arena.Id, persist: (Arena) -> Unit) {
        val previous = slots[id] ?: return
        persist(previous.arena)
        slots.remove(id)
        reconcileIndex(id, previous.match, null)
    }

    fun updateArena(id: Arena.Id, persist: (Arena) -> Unit, transform: (Arena) -> Arena): Arena? {
        val slot = slots[id] ?: return null
        val next = transform(slot.arena)
        persist(next)
        slots[id] = slot.copy(arena = next)
        return next
    }

    fun match(id: Arena.Id): ArenaMatch? = slots[id]?.match

    fun entry(id: Arena.Id): Pair<Arena, ArenaMatch>? = slots[id]?.let { it.arena to it.match }

    fun matches(): List<ArenaMatch> = slots.values.map { it.match }

    fun putMatch(match: ArenaMatch, persist: (ArenaMatch) -> Unit) {
        val slot = slots[match.arenaId] ?: return
        if (match !== slot.match) persistProjection(match.arenaId, match, persist)
        slots[match.arenaId] = slot.copy(match = match)
        reconcileIndex(match.arenaId, slot.match, match)
    }

    fun updateMatch(
        id: Arena.Id,
        persist: (ArenaMatch) -> Unit,
        transform: (ArenaMatch) -> ArenaMatch,
    ): ArenaMatch? {
        val slot = slots[id] ?: return null
        val next = transform(slot.match)
        if (next !== slot.match) persistProjection(id, next, persist)
        slots[id] = slot.copy(match = next)
        reconcileIndex(id, slot.match, next)
        return next
    }

    // Rejected transitions return the same match instance, so the identity check skips persistence
    fun <O> transact(
        id: Arena.Id,
        persist: (ArenaMatch) -> Unit,
        operation: (ArenaMatch) -> Transition<O>,
    ): Transition<O>? {
        val slot = slots[id] ?: return null
        val transition = operation(slot.match)
        if (transition.match !== slot.match) persistProjection(id, transition.match, persist)
        slots[id] = slot.copy(match = transition.match)
        reconcileIndex(id, slot.match, transition.match)
        return transition
    }

    // The match projection is write-only forensic data: a persist failure is reported and the commit proceeds; the next write converges the ledger
    private fun persistProjection(id: Arena.Id, match: ArenaMatch, persist: (ArenaMatch) -> Unit) {
        try {
            persist(match)
        } catch (e: PersistenceFailure) {
            logger.log(
                Level.SEVERE,
                "Could not persist match projection for arena ${id.name}; in-memory state committed",
                e,
            )
        }
    }

    fun arenaOf(playerId: Uuid): Arena.Id? = playerArena[playerId]

    fun isJoined(playerId: Uuid): Boolean = playerId in playerArena

    // Leave-side removals only apply to entries still pointing at this arena, so a stale diff never unregisters participation in another arena
    private fun reconcileIndex(id: Arena.Id, before: ArenaMatch?, after: ArenaMatch?) {
        val beforeIds = before?.participants?.map { it.id }?.toSet() ?: emptySet()
        val afterIds = after?.participants?.map { it.id }?.toSet() ?: emptySet()
        for (playerId in beforeIds - afterIds) {
            if (playerArena[playerId] == id) playerArena.remove(playerId)
        }
        for (playerId in afterIds - beforeIds) {
            playerArena[playerId] = id
        }
    }
}
