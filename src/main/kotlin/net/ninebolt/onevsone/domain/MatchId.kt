package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/**
 * Identifier grouping the backups taken for one match. ArenaMatch itself has
 * no durable identity — it is addressed by its arena — so this value is
 * forensic metadata, not an aggregate reference.
 */
@JvmInline
value class MatchId private constructor(val value: Uuid) {
    companion object {
        fun new(): MatchId = MatchId(Uuid.random())
        fun new(value: Uuid): MatchId = MatchId(value)
    }
    override fun toString(): String = value.toString()
}
