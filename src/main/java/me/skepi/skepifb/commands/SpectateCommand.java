package me.skepi.skepifb.commands;

import me.skepi.skepifb.SkepiFBPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Backs /spectate [player|exit], /spec [player|exit], and (via FBCommand) /fb spectate
 * [player|exit] - all three are thin wrappers around the same handle() below, the same pattern
 * StatsCommand uses for /stats and /fb stats, so every entry point behaves identically.
 */
public class SpectateCommand implements CommandExecutor {

    private final SkepiFBPlugin plugin;

    public SpectateCommand(SkepiFBPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return handle(sender, args, 0);
    }

    /**
     * @param nameArgIndex where the target-player/"exit" argument sits in args - 0 for the
     *                     standalone /spectate and /spec commands, 1 for "/fb spectate ...".
     */
    public boolean handle(CommandSender sender, String[] args, int nameArgIndex) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players may use this command.");
            return true;
        }
        if (!plugin.getPermissionsManager().hasCommandPermission(sender, "spectate")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length <= nameArgIndex) {
            sender.sendMessage(ChatColor.RED + "Usage: /spectate <player|exit>");
            return true;
        }

        String argument = args[nameArgIndex];
        if (argument.equalsIgnoreCase("exit") || argument.equalsIgnoreCase("stop") || argument.equalsIgnoreCase("off")) {
            boolean wasSpectating = plugin.getSpectateManager().stopSpectating(player);
            if (wasSpectating) {
                player.sendMessage(ChatColor.GREEN + "You are no longer spectating.");
            } else {
                player.sendMessage(ChatColor.RED + "You are not currently spectating anyone.");
            }
            return true;
        }

        Player target = Bukkit.getPlayerExact(argument);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player is not online.");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't spectate yourself.");
            return true;
        }

        plugin.getSpectateManager().startSpectating(player, target);
        player.sendMessage(ChatColor.GREEN + "Now spectating " + target.getName()
                + ChatColor.GREEN + ". Use /spectate exit to return.");
        return true;
    }
}
