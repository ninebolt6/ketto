package net.ninebolt.onevsone.application

import kotlin.uuid.Uuid

/**
 * Rate limiter keyed by requester: one acquisition per window. The clock is
 * passed in so callers can use System.nanoTime and tests can stay pure.
 */
class RequestThrottle(private val windowNanos: Long) {
    private val lastAccepted = mutableMapOf<Uuid, Long>()

    /** true when the key may proceed (first call or window elapsed). Expired entries are pruned on each call. */
    fun tryAcquire(key: Uuid, nowNanos: Long): Boolean {
        lastAccepted.entries.removeAll { nowNanos - it.value >= windowNanos }
        if (key in lastAccepted) return false
        lastAccepted[key] = nowNanos
        return true
    }
}
