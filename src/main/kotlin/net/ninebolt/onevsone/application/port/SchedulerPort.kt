package net.ninebolt.onevsone.application.port

/** Cancel handle for a running task. The same one is passed to repeat's callback. */
fun interface Cancellation {
    fun cancel()
}

/**
 * Scheduler for game timing. Unit is ticks.
 * domain does not know about ticks; application makes the timing explicit.
 * BukkitTask stays inside the adapter.
 */
interface SchedulerPort {
    /** Runs once. delayTicks=0 means the next tick. */
    fun schedule(delayTicks: Long, action: () -> Unit): Cancellation

    fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation
}
