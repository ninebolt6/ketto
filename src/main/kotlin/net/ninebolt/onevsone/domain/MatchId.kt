package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/** Token-like ID identifying one match (from backup through end-of-match restore). */
@JvmInline
value class MatchId private constructor(val value: Uuid) {
    companion object {
        fun new(): MatchId = MatchId(Uuid.random())
        fun new(value: Uuid): MatchId = MatchId(value)
    }
    override fun toString(): String = value.toString()
}
