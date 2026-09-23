package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.Transition
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.uuid.Uuid

/**
 * Shared registry of arenas, match aggregates, and the UUID->arena index.
 * Shared by ArenaApplicationService, ArenaAdministrationService,
 * ArenaLifecycleService, ArenaSignService, and MatchProgressionService; the
 * playerArena index enforces the ban on joining two arenas at once.
 *
 * Arena and ArenaMatch always exist 1:1, and match.arenaId == arena.id is
 * guaranteed by construction in installArena (Slot). The map is not exposed.
 * The playerArena index is kept in sync from participant diffs on every match
 * write-back (putMatch/transact/updateMatch), structurally maintaining
 * playerArena[uuid]=id ⟺ uuid ∈ matches[id].participants.
 * ArenaMatch is immutable, so every change must go through these methods.
 *
 * Write-back ordering contract: every mutator takes a `persist` hook that runs
 * before the in-memory state is replaced. The failure policy is structural:
 *
 * - Arena mutations are authoritative data: a persist failure propagates and
 *   the registry stays unchanged.
 * - Match mutations are projections: a persist failure is reported and the
 *   in-memory commit still proceeds (the game must go on; the projection
 *   converges on the next write).
 *
 * Callers that deliberately persist nothing pass `persist = {}`. World side
 * effects (teleports, inventory restores, sign repaints) never run inside the
 * hook — the contract covers memory and persistence only.
 */
class ArenaRegistry(
    private val requiredWins: Int,
    private val logger: Logger
) {

    private data class Slot(val arena: Arena, val match: ArenaMatch)

    private val slots = linkedMapOf<Arena.Id, Slot>()
    private val playerArena = mutableMapOf<Uuid, Arena.Id>()

    // ---- Arenas (authoritative: persist failures propagate) -----------------

    fun arena(id: Arena.Id): Arena? = slots[id]?.arena

    /**
     * Resolves a registered arena by name. Prefers an exact match, falling back
     * to case-insensitive lookup (the read side mirrors create's
     * case-insensitive duplicate rejection).
     */
    fun resolveArenaId(name: String): Arena.Id? =
        Arena.Id.of(name)?.takeIf { slots.containsKey(it) }
            ?: slots.keys.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Arena IDs in registration order. */
    fun arenaIds(): List<Arena.Id> = slots.keys.toList()

    /** Persists, then registers an arena and attaches a fresh match aggregate. */
    fun installArena(arena: Arena, persist: (Arena) -> Unit) {
        persist(arena)
        val match = ArenaMatch.new(arena.id, requiredWins)
        val previous = slots.put(arena.id, Slot(arena, match))
        reconcileIndex(arena.id, previous?.match, match)
    }

    /** Persists, then removes the arena. On failure the arena stays registered. */
    fun removeArena(id: Arena.Id, persist: (Arena) -> Unit) {
        val previous = slots[id] ?: return
        persist(previous.arena)
        slots.remove(id)
        reconcileIndex(id, previous.match, null)
    }

    /**
     * Persists the transformed arena, then writes it back. Does nothing and
     * returns null when unregistered; a persist failure leaves the current
     * arena in place.
     */
    fun updateArena(id: Arena.Id, persist: (Arena) -> Unit, transform: (Arena) -> Arena): Arena? {
        val slot = slots[id] ?: return null
        val next = transform(slot.arena)
        persist(next)
        slots[id] = slot.copy(arena = next)
        return next
    }

    // ---- Match aggregates (projections: persist failures degrade to a report) ----

    fun match(id: Arena.Id): ArenaMatch? = slots[id]?.match

    /** All matches in registration order (for shutdown processing). */
    fun matches(): List<ArenaMatch> = slots.values.map { it.match }

    /**
     * Writes back a transition result computed beforehand. For paths like join
     * that need "compute -> side effects -> commit" ordering. Does nothing
     * when unregistered.
     */
    fun putMatch(match: ArenaMatch, persist: (ArenaMatch) -> Unit) {
        val slot = slots[match.arenaId] ?: return
        if (match !== slot.match) persistProjection(match.arenaId, match, persist)
        slots[match.arenaId] = slot.copy(match = match)
        reconcileIndex(match.arenaId, slot.match, match)
    }

    /**
     * Transforms and writes back a match. Does nothing and returns null when
     * the arena is absent.
     */
    fun updateMatch(
        id: Arena.Id,
        persist: (ArenaMatch) -> Unit,
        transform: (ArenaMatch) -> ArenaMatch
    ): ArenaMatch? {
        val slot = slots[id] ?: return null
        val next = transform(slot.match)
        if (next !== slot.match) persistProjection(id, next, persist)
        slots[id] = slot.copy(match = next)
        reconcileIndex(id, slot.match, next)
        return next
    }

    /**
     * Applies an operation to the match, writes it back, and returns the
     * outcome. null when the arena is absent. Centralizes the commit step of
     * "compute -> commit -> orchestrate based on the outcome". Rejected
     * transitions (same match instance) skip persistence entirely.
     */
    fun <O> transact(
        id: Arena.Id,
        persist: (ArenaMatch) -> Unit,
        operation: (ArenaMatch) -> Transition<O>
    ): Transition<O>? {
        val slot = slots[id] ?: return null
        val transition = operation(slot.match)
        if (transition.match !== slot.match) persistProjection(id, transition.match, persist)
        slots[id] = slot.copy(match = transition.match)
        reconcileIndex(id, slot.match, transition.match)
        return transition
    }

    /**
     * The match projection is write-only forensic data, so a persistence
     * failure never blocks game flow: it is reported and the in-memory commit
     * proceeds. A successful next projection converges the ledger regardless
     * of what this attempt left behind.
     */
    private fun persistProjection(id: Arena.Id, match: ArenaMatch, persist: (ArenaMatch) -> Unit) {
        try {
            persist(match)
        } catch (e: PersistenceFailure) {
            logger.log(
                Level.SEVERE,
                "Could not persist match projection for arena ${id.name}; in-memory state committed",
                e
            )
        }
    }

    // ---- Participation index ----------------------------------------------------------

    fun arenaOf(playerId: Uuid): Arena.Id? = playerArena[playerId]

    fun isJoined(playerId: Uuid): Boolean = playerId in playerArena

    /**
     * Reflects the participant diff between before and after a write-back into
     * the index. Entries added on the join side simply mirror match membership;
     * on the leave side only entries still pointing at this arena are removed
     * (so they never mix with participation in another arena).
     */
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
