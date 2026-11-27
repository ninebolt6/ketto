package net.ninebolt.ketto.e2e.fixtures;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class E2eAbilitiesPlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2) return false;
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("Unknown player: " + args[0]);
            return true;
        }
        boolean allowed;
        if (args[1].equals("true")) {
            allowed = true;
        } else if (args[1].equals("false")) {
            allowed = false;
        } else {
            return false;
        }
        target.setAllowFlight(allowed);
        sender.sendMessage("Set flight permission for " + target.getName() + " to " + allowed);
        return true;
    }
}
