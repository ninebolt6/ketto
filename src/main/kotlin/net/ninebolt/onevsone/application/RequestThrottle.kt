package net.ninebolt.onevsone.application

import kotlin.uuid.Uuid

class RequestThrottle(private val windowNanos: Long) {
    private val lastAccepted = mutableMapOf<Uuid, Long>()

    fun tryAcquire(key: Uuid, nowNanos: Long): Boolean {
        lastAccepted.entries.removeAll { nowNanos - it.value >= windowNanos }
        if (key in lastAccepted) return false
        lastAccepted[key] = nowNanos
        return true
    }
}
