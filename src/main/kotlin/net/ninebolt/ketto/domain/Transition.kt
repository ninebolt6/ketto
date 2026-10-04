package net.ninebolt.ketto.domain

data class Transition<out O>(val match: ArenaMatch, val outcome: O)
