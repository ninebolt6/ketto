package net.ninebolt.onevsone.domain

sealed interface EnableOutcome {
    data class Ready(val arena: Arena.Enabled) : EnableOutcome

    data class MissingSpawns(val slots: List<SpawnSlot>) : EnableOutcome
}
