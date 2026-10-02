package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.SchedulerPort
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.logging.Level

// Bukkit scheduler types are confined to this class
class PaperScheduler(private val plugin: JavaPlugin) : SchedulerPort {

    override fun schedule(delayTicks: Long, action: () -> Unit): Cancellation {
        val task = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                try {
                    action()
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Scheduled task failed", e)
                }
            },
            delayTicks,
        )
        return Cancellation { task.cancel() }
    }

    override fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation, Int) -> Unit): Cancellation {
        val runnable = object : BukkitRunnable() {
            private val self = Cancellation { this.cancel() }
            private var runs = 0

            override fun run() {
                try {
                    action(self, runs++)
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Repeating task failed; cancelling", e)
                    cancel()
                }
            }
        }
        runnable.runTaskTimer(plugin, initialDelayTicks, periodTicks)
        return Cancellation { runnable.cancel() }
    }
}
