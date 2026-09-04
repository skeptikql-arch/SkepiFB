package me.skepi.skepifb.leaderboard;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standalone /lb and /leaderboard command - opens the graphical top 10 leaderboard menu (see
 * LeaderboardMenuManager). Entirely separate from "/fb lb ...", which remains the chat-based
 * admin add/remove/list/user leaderboard management command and is untouched by this.
 * <p>
 * With no argument, opens whichever mode the player is currently in (see
 * LeaderboardMenuManager#openLeaderboardMenu(Player)). With one argument, "/lb <mode>" opens that
 * mode's leaderboard directly, case-insensitively (so "/lb snow" and "/lb Snow" are the same).
 */
public class LeaderboardMenuCommand implements CommandExecutor, TabCompleter {

    private final SkepiFBPlugin plugin;

    public LeaderboardMenuCommand(SkepiFBPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players may use this command.");
            return true;
        }
        if (!plugin.getPermissionsManager().hasCommandPermission(sender, "leaderboard")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length >= 1 && !args[0].isBlank()) {
            String mode = plugin.getLeaderboardMenuManager().resolveModeArgument(args[0]);
            plugin.getLeaderboardMenuManager().openLeaderboardMenu(player, mode);
        } else {
            plugin.getLeaderboardMenuManager().openLeaderboardMenu(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (Arena arena : plugin.getArenaManager().getArenas()) {
            String name = arena.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(partial)) {
                suggestions.add(name);
            }
        }
        return suggestions;
    }
}
