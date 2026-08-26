package me.skepi.skepifb.session;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SessionTopManager {

    private final SkepiFBPlugin plugin;
    private final ConfigManager configManager;

    private static final int MAX_ENTRIES = 5;

    // arenaName -> list of entries (sorted by time asc)
    private final Map<String, List<Entry>> arenaTopMap = new ConcurrentHashMap<>();

    public SessionTopManager(SkepiFBPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void recordFinish(String arenaName, UUID playerUuid, double timeSeconds) {
        if (arenaName == null) return;
        arenaTopMap.compute(arenaName, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            // If player already has an entry, only update if the new time is strictly better
            for (Entry e : list) {
                if (e.playerUuid.equals(playerUuid)) {
                    if (timeSeconds >= e.time) {
                        // New time is worse or equal: do nothing
                        list.sort(Comparator.comparingDouble(x -> x.time));
                        if (list.size() > MAX_ENTRIES) {
                            return new ArrayList<>(list.subList(0, MAX_ENTRIES));
                        }
                        return list;
                    } else {
                        // New time is better: replace existing entry
                        list.removeIf(x -> x.playerUuid.equals(playerUuid));
                        list.add(new Entry(playerUuid, timeSeconds));
                        list.sort(Comparator.comparingDouble(x -> x.time));
                        if (list.size() > MAX_ENTRIES) {
                            return new ArrayList<>(list.subList(0, MAX_ENTRIES));
                        }
                        return list;
                    }
                }
            }
            // No existing entry: add new
            list.add(new Entry(playerUuid, timeSeconds));
            list.sort(Comparator.comparingDouble(e -> e.time));
            if (list.size() > MAX_ENTRIES) {
                return new ArrayList<>(list.subList(0, MAX_ENTRIES));
            }
            return list;
        });
    }

    public void removePlayerFromArena(UUID playerUuid, String arenaName) {
        if (arenaName == null) return;
        List<Entry> list = arenaTopMap.get(arenaName);
        if (list == null) return;
        list.removeIf(e -> e.playerUuid.equals(playerUuid));
    }

    public void removePlayerFromAllArenas(UUID playerUuid) {
        for (String arena : arenaTopMap.keySet()) {
            removePlayerFromArena(playerUuid, arena);
        }
    }

    public void clearAll() {
        arenaTopMap.clear();
    }

    public String getFormattedEntry(String arenaName, int rankIndex, Player viewer) {
        // rankIndex is 1-based
        String empty = configManager == null ? "&7-.--" : configManager.getConfiguration().getString("session-top-empty", "&7-.--");
        if (arenaName == null) {
            return empty;
        }
        List<Entry> list = arenaTopMap.get(arenaName);
        String format = configManager == null ? "%player% : &e%time%" : configManager.getConfiguration().getString("session-top-format", "%player% : &e%time%");

        if (list == null || list.size() < rankIndex) {
            return empty;
        }

        Entry entry = list.get(rankIndex - 1);
        String playerName = lookupColoredName(entry.playerUuid, viewer);
        String timeText = String.format(Locale.ROOT, "%.3f", entry.time);

        return format.replace("%player%", playerName).replace("%time%", timeText);
    }

    private String lookupColoredName(UUID playerUuid, Player viewer) {
        Player player = Bukkit.getPlayer(playerUuid);
        String name = player != null ? player.getName() : playerUuid.toString();

        // Try to use LuckPerms if available to extract a color from the prefix
        try {
            Class<?> luckClass = Class.forName("net.luckperms.api.LuckPerms");
            Object reg = Bukkit.getServicesManager().getRegistration(luckClass);
            if (reg != null) {
                Object luckApi = reg.getClass().getMethod("getProvider").invoke(reg);
                if (luckApi != null) {
                    // Get user manager and then user
                    Object userManager = luckApi.getClass().getMethod("getUserManager").invoke(luckApi);
                    Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, playerUuid);
                    if (user != null) {
                        Object cached = user.getClass().getMethod("getCachedData").invoke(user);
                        if (cached != null) {
                            Object meta = cached.getClass().getMethod("getMetaData").invoke(cached);
                            if (meta != null) {
                                Object prefix = meta.getClass().getMethod("getPrefix").invoke(meta);
                                if (prefix instanceof String) {
                                    String p = (String) prefix;
                                    String color = extractLastColorCode(p);
                                    if (color != null) {
                                        return color + name;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback: return plain name
        return name;
    }

    private String extractLastColorCode(String s) {
        if (s == null) return null;
        // Find last occurrence of section sign + code
        int idx = s.lastIndexOf('§');
        if (idx >= 0 && idx + 1 < s.length()) {
            char code = s.charAt(idx + 1);
            return "§" + code;
        }
        // Also handle ampersand-style
        idx = s.lastIndexOf('&');
        if (idx >= 0 && idx + 1 < s.length()) {
            char code = s.charAt(idx + 1);
            return "&" + code;
        }
        return null;
    }

    private static final class Entry {
        final UUID playerUuid;
        final double time;

        Entry(UUID playerUuid, double time) {
            this.playerUuid = playerUuid;
            this.time = time;
        }
    }
}
