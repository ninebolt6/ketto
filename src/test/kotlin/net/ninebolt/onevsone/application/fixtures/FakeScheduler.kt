package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.SchedulerPort

class FakeScheduler : SchedulerPort {
    class Timer(
        val id: Int,
        val delay: Long,
        val period: Long,
        private val owner: FakeScheduler,
        private val action: (Cancellation) -> Unit
    ) : Cancellation {
        var cancelled = false
            private set

        override fun cancel() {
            if (!cancelled) {
                cancelled = true
                owner.cancelledIds += id
            }
        }

        fun run() = action(this)
    }

    class OneShot(val action: () -> Unit) : Cancellation {
        var cancelled = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun run() {
            if (!cancelled) action()
        }
    }

    var nextId = 1
    val timers = mutableListOf<Timer>()
    val oneShots = mutableListOf<OneShot>()
    val cancelledIds = mutableListOf<Int>()

    override fun schedule(delayTicks: Long, action: () -> Unit): Cancellation =
        OneShot(action).also { oneShots += it }

    override fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation =
        Timer(nextId++, initialDelayTicks, periodTicks, this, action).also { timers += it }

    fun tick(times: Int = 1) {
        repeat(times) { timers.lastOrNull()?.run() }
    }

    fun runOneShots() {
        val pending = oneShots.toList()
        oneShots.clear()
        pending.forEach { it.run() }
    }
}
