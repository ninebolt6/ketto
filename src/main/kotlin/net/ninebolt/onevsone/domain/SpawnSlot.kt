package net.ninebolt.onevsone.domain

enum class SpawnSlot(val index: Int) {
    FIRST(0),
    SECOND(1);

    val number: Int get() = index + 1

    companion object {
        fun ofIndex(index: Int): SpawnSlot? = entries.getOrNull(index)

        fun ofNumber(number: Int): SpawnSlot? = entries.getOrNull(number - 1)
    }
}
