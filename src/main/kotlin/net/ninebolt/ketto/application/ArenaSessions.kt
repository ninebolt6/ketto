package net.ninebolt.ketto.application

import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.ArenaMatch
import net.ninebolt.ketto.domain.Transition
import kotlin.uuid.Uuid

class ArenaSessions(private val requiredWins: Int) {

    private data class Slot(val arena: Arena, val match: ArenaMatch)

    private val slots = linkedMapOf<Arena.Id, Slot>()

    fun findArena(id: Arena.Id): Arena? = slots[id]?.arena

    fun findEnabledArena(id: Arena.Id): Arena.Enabled? = findArena(id) as? Arena.Enabled

    // Case-insensitive fallback is unambiguous because create rejects case-insensitive duplicate names
    fun findArenaId(name: String): Arena.Id? = Arena.Id.of(name)?.takeIf { slots.containsKey(it) }
        ?: slots.keys.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun findArena(name: String): Arena? = findArenaId(name)?.let { slots.getValue(it).arena }

    fun arenaIds(): List<Arena.Id> = slots.keys.toList()

    fun installArena(arena: Arena): ArenaMatch {
        val match = ArenaMatch.new(arena.id, requiredWins)
        slots[arena.id] = Slot(arena, match)
        return match
    }

    fun removeArena(id: Arena.Id) {
        slots.remove(id)
    }

    fun replaceArena(arena: Arena) {
        val slot = slots[arena.id] ?: return
        slots[arena.id] = slot.copy(arena = arena)
    }

    fun findMatch(id: Arena.Id): ArenaMatch? = slots[id]?.match

    fun findEntry(id: Arena.Id): Pair<Arena, ArenaMatch>? = slots[id]?.let { it.arena to it.match }

    fun matches(): List<ArenaMatch> = slots.values.map { it.match }

    fun putMatch(match: ArenaMatch) {
        val slot = slots[match.arenaId] ?: return
        slots[match.arenaId] = slot.copy(match = match)
    }

    fun <O> transact(
        id: Arena.Id,
        operation: (ArenaMatch) -> Transition<O>,
    ): Transition<O>? {
        val slot = slots[id] ?: return null
        val transition = operation(slot.match)
        slots[id] = slot.copy(match = transition.match)
        return transition
    }

    fun findMatchOf(playerId: Uuid): ArenaMatch? = findArenaIdOf(playerId)?.let(::findMatch)

    fun <O> transactFor(
        playerId: Uuid,
        operation: (ArenaMatch) -> Transition<O>,
    ): Transition<O>? = findArenaIdOf(playerId)?.let { transact(it, operation) }

    // the last slot in insertion order wins if a player somehow appears in two matches
    fun findArenaIdOf(playerId: Uuid): Arena.Id? = slots.values.lastOrNull { slot -> slot.match.participants.any { it.id == playerId } }?.arena?.id

    fun isJoined(playerId: Uuid): Boolean = findArenaIdOf(playerId) != null
}
