package me.skepi.skepifb.chat;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.config.ConfigManager;
import me.skepi.skepifb.scoreboard.ScoreboardManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public class ChatPlaceholderManager implements Listener {

    private final SkepiFBPlugin plugin;
    private final ConfigManager configManager;
    private final ScoreboardManager scoreboardManager;

    public ChatPlaceholderManager(SkepiFBPlugin plugin, ConfigManager configManager, ScoreboardManager scoreboardManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scoreboardManager = scoreboardManager;
    }

    public void registerListeners() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }

        message = applyConfiguredChatPlaceholders(message);
        message = scoreboardManager.replacePlaceholders(player, message);
        message = ChatColor.translateAlternateColorCodes('&', message);
        event.setMessage(message);
    }

    private String applyConfiguredChatPlaceholders(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        Map<String, String> placeholders = configManager.getChatPlaceholders();
        if (placeholders.isEmpty()) {
            return input;
        }

        String result = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            String value = entry.getValue();
            if (value == null) {
                value = "";
            }
            if (result.contains(key)) {
                result = result.replace(key, value);
            }
        }
        return result;
    }
}
