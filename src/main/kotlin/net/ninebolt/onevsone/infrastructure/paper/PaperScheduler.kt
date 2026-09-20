package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.SchedulerPort
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/** BukkitTask をこのクラスに限定する。 */
class PaperScheduler(private val plugin: JavaPlugin) : SchedulerPort {

    override fun schedule(delayTicks: Long, action: () -> Unit): Cancellation {
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable { action() }, delayTicks)
        return Cancellation { task.cancel() }
    }

    override fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation {
        var task: BukkitTask? = null
        val cancellation = Cancellation { task?.cancel() }
        task = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable { action(cancellation) },
            initialDelayTicks,
            periodTicks
        )
        return cancellation
    }
}
