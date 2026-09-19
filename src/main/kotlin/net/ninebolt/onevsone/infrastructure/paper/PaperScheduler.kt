package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.SchedulerPort
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

/** BukkitTask をこのクラスに限定する。 */
class PaperScheduler(private val plugin: JavaPlugin) : SchedulerPort {

    override fun schedule(delayTicks: Long, action: () -> Unit): Cancellation {
        val task = object : BukkitRunnable() {
            override fun run() = action()
        }
        task.runTaskLater(plugin, delayTicks)
        return Cancellation { task.cancel() }
    }

    override fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation {
        var task: BukkitRunnable? = null
        val cancellation = Cancellation { task?.cancel() }
        task = object : BukkitRunnable() {
            override fun run() = action(cancellation)
        }
        task.runTaskTimer(plugin, initialDelayTicks, periodTicks)
        return cancellation
    }
}
