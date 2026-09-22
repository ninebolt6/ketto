package net.ninebolt.onevsone.domain

/**
 * Result of an ArenaMatch operation. match is the new post-transition state
 * (the same unchanged instance on rejection). The caller inspects outcome
 * first, then writes match back to the registry.
 */
data class Transition<out O>(val match: ArenaMatch, val outcome: O)
