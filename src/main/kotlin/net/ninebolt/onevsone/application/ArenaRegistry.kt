package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Transition
import kotlin.uuid.Uuid

class ArenaRegistry(private val requiredWins: Int) {

    private data class Slot(val arena: Arena, val match: ArenaMatch)

    private val slots = linkedMapOf<Arena.Id, Slot>()
    private val playerArena = mutableMapOf<Uuid, Arena.Id>()

    fun arena(id: Arena.Id): Arena? = slots[id]?.arena

    fun enabledArena(id: Arena.Id): Arena.Enabled? = arena(id) as? Arena.Enabled

    // Case-insensitive fallback is unambiguous because create rejects case-insensitive duplicate names
    fun resolveArenaId(name: String): Arena.Id? = Arena.Id.of(name)?.takeIf { slots.containsKey(it) }
        ?: slots.keys.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun resolveArena(name: String): Arena? = resolveArenaId(name)?.let { slots.getValue(it).arena }

    fun resolveEntry(name: String): Pair<Arena, ArenaMatch>? = resolveArenaId(name)?.let(::entry)

    fun arenaIds(): List<Arena.Id> = slots.keys.toList()

    fun installArena(arena: Arena): ArenaMatch {
        val match = ArenaMatch.new(arena.id, requiredWins)
        val previous = slots.put(arena.id, Slot(arena, match))
        reconcileIndex(arena.id, previous?.match, match)
        return match
    }

    fun removeArena(id: Arena.Id) {
        val previous = slots.remove(id) ?: return
        reconcileIndex(id, previous.match, null)
    }

    fun replaceArena(arena: Arena) {
        val slot = slots[arena.id] ?: return
        slots[arena.id] = slot.copy(arena = arena)
    }

    fun match(id: Arena.Id): ArenaMatch? = slots[id]?.match

    fun entry(id: Arena.Id): Pair<Arena, ArenaMatch>? = slots[id]?.let { it.arena to it.match }

    fun matches(): List<ArenaMatch> = slots.values.map { it.match }

    fun putMatch(match: ArenaMatch) {
        val slot = slots[match.arenaId] ?: return
        slots[match.arenaId] = slot.copy(match = match)
        reconcileIndex(match.arenaId, slot.match, match)
    }

    fun updateMatch(id: Arena.Id, transform: (ArenaMatch) -> ArenaMatch): ArenaMatch? {
        val slot = slots[id] ?: return null
        val next = transform(slot.match)
        slots[id] = slot.copy(match = next)
        reconcileIndex(id, slot.match, next)
        return next
    }

    fun <O> transact(
        id: Arena.Id,
        operation: (ArenaMatch) -> Transition<O>,
    ): Transition<O>? {
        val slot = slots[id] ?: return null
        val transition = operation(slot.match)
        slots[id] = slot.copy(match = transition.match)
        reconcileIndex(id, slot.match, transition.match)
        return transition
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
