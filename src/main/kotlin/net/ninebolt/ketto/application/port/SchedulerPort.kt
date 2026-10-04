package net.ninebolt.ketto.application.port

// the same handle is passed to repeat's action callback
fun interface Cancellation {
    fun cancel()
}

interface SchedulerPort {
    // delayTicks=0 schedules on the next tick
    fun schedule(delayTicks: Long, action: () -> Unit): Cancellation

    // iteration is 0 on the first run and increments each execution
    fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation, iteration: Int) -> Unit): Cancellation
}
