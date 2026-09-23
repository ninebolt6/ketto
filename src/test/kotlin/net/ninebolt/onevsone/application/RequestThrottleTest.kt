package net.ninebolt.onevsone.application

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test

/** Unit tests for the per-requester acquisition window. */
class RequestThrottleTest {

    private val throttle = RequestThrottle(windowNanos = 100L)
    private val key = Uuid.random()

    @Test
    fun `first acquire succeeds then repeats within the window are denied`() {
        assertTrue(throttle.tryAcquire(key, 0L))
        assertFalse(throttle.tryAcquire(key, 50L))
        assertFalse(throttle.tryAcquire(key, 99L))
    }

    @Test
    fun `acquire succeeds again after the window elapses`() {
        assertTrue(throttle.tryAcquire(key, 0L))
        assertTrue(throttle.tryAcquire(key, 100L))
    }

    @Test
    fun `different keys do not share the window`() {
        assertTrue(throttle.tryAcquire(key, 0L))
        assertTrue(throttle.tryAcquire(Uuid.random(), 0L))
    }
}
