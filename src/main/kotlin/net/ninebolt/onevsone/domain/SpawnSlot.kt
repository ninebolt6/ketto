package net.ninebolt.onevsone.domain

enum class SpawnSlot(val index: Int) {
    FIRST(0),
    SECOND(1),
    ;

    val number: Int get() = index + 1
}
