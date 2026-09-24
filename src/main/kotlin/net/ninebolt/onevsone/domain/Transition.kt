package net.ninebolt.onevsone.domain

data class Transition<out O>(val match: ArenaMatch, val outcome: O)
