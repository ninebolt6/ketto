package net.ninebolt.onevsone.application.port

// the same handle is passed to repeat's action callback
fun interface Cancellation {
    fun cancel()
}

interface SchedulerPort {
    // delayTicks=0 schedules on the next tick
    fun schedule(delayTicks: Long, action: () -> Unit): Cancellation

    fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation
}
