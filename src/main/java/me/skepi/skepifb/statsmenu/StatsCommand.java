package me.skepi.skepifb.statsmenu;

import me.skepi.skepifb.SkepiFBPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Standalone /stats [player] command - thin wrapper around StatsMenuManager's shared handler, the
 * same one /fb stats [player] calls, so both entry points behave identically.
 */
public class StatsCommand implements CommandExecutor {

    private final SkepiFBPlugin plugin;

    public StatsCommand(SkepiFBPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return plugin.getStatsMenuManager().handleStatsCommand(sender, args, 0);
    }
}
