package me.skepi.skepifb.stats;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaIsland;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Locale;

public class StatboardManager {

    private final JavaPlugin plugin;
    private final SkepiFBPlugin main;
    private final Map<UUID, List<ArmorStand>> statboards = new ConcurrentHashMap<>();
    private int taskId = -1;

    public StatboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.main = (SkepiFBPlugin) plugin;
        scheduleUpdater();
    }

    private void scheduleUpdater() {
        cancelUpdater();
        if (!main.getConfigManager().getConfiguration().getBoolean("statboard.enabled", true)) {
            return;
        }
        int interval = main.getConfigManager().getConfiguration().getInt("statboard.update-interval-ticks", 20);
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, interval, interval).getTaskId();
    }

    private void cancelUpdater() {
        if (taskId > -1) {
            try { Bukkit.getScheduler().cancelTask(taskId); } catch (Throwable ignored) {}
            taskId = -1;
        }
    }

    public void createStatboard(UUID playerUuid, Arena arena, ArenaIsland island) {
        if (playerUuid == null || arena == null || island == null) return;
        if (!main.getConfigManager().getConfiguration().getBoolean("statboard.enabled", true)) return;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return;

        // remove any existing
        removeStatboard(playerUuid);

        Location base = new Location(player.getWorld(), island.getSpawnLocation().getX(), island.getSpawnLocation().getY(), island.getSpawnLocation().getZ());
        base.setYaw(player.getLocation().getYaw());

        double rightDistance = main.getConfigManager().getConfiguration().getDouble("statboard.offset.right", 2.0);
        double forwardDistance = main.getConfigManager().getConfiguration().getDouble("statboard.offset.forward", 1.0);
        double upDistance = main.getConfigManager().getConfiguration().getDouble("statboard.offset.up", 1.0);
        double yawRad = Math.toRadians(base.getYaw());
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        Location hologramBase = base.clone()
                .add(rightX * rightDistance, upDistance, rightZ * rightDistance)
                .add(forwardX * forwardDistance, 0.0, forwardZ * forwardDistance);

        List<String> lines = main.getConfigManager().getConfiguration().getStringList("statboard.lines");
        if (lines == null || lines.isEmpty()) lines = List.of(
                "%player%&f's Statboard",
                "&e&lBridging Statistics &7- &e%mode%",
                "&bPersonal Best &7- &e%pb% &7[&bTop &e%top%&7]",
                "&bAverage Time &7- &e%averagetime%",
                "&bCompletions &7- &e%completions%",
                "&bAttempts &7- &e%attempts%"
        );

        List<ArmorStand> stands = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int lineIndex = i;
            String lineText = lines.get(i);
            Location loc = hologramBase.clone().add(0, (lines.size() - lineIndex - 1) * 0.25, 0);
            try {
                ArmorStand as = hologramBase.getWorld().spawn(loc, ArmorStand.class, asn -> {
                    asn.setVisible(false);
                    asn.setGravity(false);
                    try { asn.setMarker(true); } catch (Throwable ignored) {}
                    asn.setCustomNameVisible(true);
                    asn.setCustomName(replacePlaceholders(playerUuid, lineText));
                });
                if (as != null) {
                    stands.add(as);
                }
            } catch (Throwable ex) {
                plugin.getLogger().warning("Failed to spawn statboard armorstand: " + ex.getMessage());
            }
        }

        if (!stands.isEmpty()) {
            statboards.put(playerUuid, stands);
        }
    }

    public void removeStatboard(UUID playerUuid) {
        if (playerUuid == null) return;
        Player player = Bukkit.getPlayer(playerUuid);
        List<ArmorStand> stands = statboards.remove(playerUuid);
        if (stands == null) return;
        for (ArmorStand as : stands) {
            try { as.remove(); } catch (Throwable ignored) {}
        }
    }

    public void updateAll() {
        if (statboards.isEmpty()) return;
        List<UUID> keys = new ArrayList<>(statboards.keySet());
        for (UUID uuid : keys) {
            updateStatboard(uuid);
        }
    }

    public void updateStatboard(UUID playerUuid) {
        List<ArmorStand> stands = statboards.get(playerUuid);
        if (stands == null || stands.isEmpty()) return;
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            removeStatboard(playerUuid);
            return;
        }
        List<String> lines = main.getConfigManager().getConfiguration().getStringList("statboard.lines");
        if (lines == null) lines = List.of();
        for (int i = 0; i < stands.size(); i++) {
            ArmorStand as = stands.get(i);
            String text = i < lines.size() ? replacePlaceholders(playerUuid, lines.get(i)) : "";
            try { as.setCustomName(text); } catch (Throwable ignored) {}
        }
    }

    public boolean hasStatboard(UUID playerUuid) {
        return playerUuid != null && statboards.containsKey(playerUuid);
    }

    private String getLuckPermsDisplayName(UUID playerUuid, String fallbackName) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return fallbackName;
        }
        try {
            Class<?> luckClass = Class.forName("net.luckperms.api.LuckPerms");
            Object luckApi = luckClass.getMethod("getApi").invoke(null);
            if (luckApi == null) {
                return fallbackName;
            }
            Object userManager = luckApi.getClass().getMethod("getUserManager").invoke(luckApi);
            Object user = null;
            try {
                user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, playerUuid);
            } catch (NoSuchMethodException ignored) {
            }
            if (user == null) {
                try {
                    Object future = userManager.getClass().getMethod("loadUser", UUID.class).invoke(userManager, playerUuid);
                    if (future != null) {
                        Object result = future.getClass().getMethod("join").invoke(future);
                        user = result;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (user == null) {
                return fallbackName;
            }
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
            String prefix = (String) metaData.getClass().getMethod("getPrefix").invoke(metaData);
            String suffix = (String) metaData.getClass().getMethod("getSuffix").invoke(metaData);
            if (prefix == null) prefix = "";
            if (suffix == null) suffix = "";
            return prefix + fallbackName + suffix;
        } catch (Throwable ignored) {
            return fallbackName;
        }
    }

    private double getTopPercentile(UUID playerUuid, String arenaName) {
        double playerPb = main.getStatsManager().getPersonalBest(playerUuid, arenaName);
        if (playerPb <= 0.0) {
            return -1.0;
        }
        java.util.List<Double> allPbs = main.getStatsManager().getPersonalBestsForArena(arenaName);
        if (allPbs.isEmpty()) {
            return 100.0;
        }
        long allPlayers = allPbs.size();
        long fasterCount = allPbs.stream().filter(pb -> pb > 0.0 && pb < playerPb).count();
        long otherPlayers = Math.max(0, allPlayers - 1);
        if (otherPlayers <= 0) {
            return 100.0;
        }
        return (fasterCount * 100.0) / otherPlayers;
    }

    private String replacePlaceholders(UUID playerUuid, String line) {
        if (line == null) return "";
        String result = line;
        PlayerStatsManager stats = main.getStatsManager();
        Player player = Bukkit.getPlayer(playerUuid);
        String playerName = player == null ? "" : player.getName();
        String arenaName = main.getPlayerManager().getPlayerArena(playerUuid);
        String mode = main.getTimerManager().isPracticeMode(playerUuid) ? "Practice" : (arenaName != null ? arenaName : "None");
        double pb = arenaName == null ? 0.0 : stats.getPersonalBest(playerUuid, arenaName);
        double topPercent = arenaName == null ? -1.0 : this.getTopPercentile(playerUuid, arenaName);
        double averageTime = arenaName == null ? 0.0 : stats.getAverageCompletionTime(playerUuid, arenaName);
        int completions = arenaName == null ? 0 : stats.getCompletions(playerUuid, arenaName);
        int attempts = arenaName != null ? main.getTimerManager().getAttemptsPlaceholder(playerUuid, arenaName) : 0;

        result = result.replace("%player%", this.getLuckPermsDisplayName(playerUuid, playerName));
        result = result.replace("%mode%", mode);
        result = result.replace("%top%", topPercent < 0.0 ? "N/A" : String.format(Locale.ROOT, "%.2f%%", topPercent));
        result = result.replace("%pb%", pb == 0.0 ? "N/A" : String.format(Locale.ROOT, "%.3f", pb));
        result = result.replace("%speed%", main.getTimerManager().getSpeedPlaceholder(playerUuid));
        result = result.replace("%averagespeed%", main.getTimerManager().getAverageSpeedPlaceholder(playerUuid));
        result = result.replace("%averagetime%", averageTime <= 0.0 ? "N/A" : String.format(Locale.ROOT, "%.3f", averageTime));
        result = result.replace("%completions%", completions <= 0 ? "N/A" : String.valueOf(completions));
        result = result.replace("%attempts%", String.valueOf(attempts));

        // The rest of this method used to stop here, which meant every placeholder that the
        // scoreboard and finish-message text support (%timer%, %time%, %blocks%, %coins%, %xp%,
        // %level%, %next_level_xp%, %bossbar_percent%, %pbdiff%, %sessiontop1%-%sessiontop5%)
        // silently did nothing on a statboard - it would print the literal "%timer%" text instead
        // of a value, since nothing here ever looked for or replaced it. A placeholder documented
        // as usable anywhere should actually work anywhere; adding the same set here (using the
        // exact same source methods ScoreboardManager and TimerManager use) is what makes that true.
        if (result.contains("%timer%")) {
            result = result.replace("%timer%", main.getTimerManager().getTimerPlaceholder(playerUuid));
        }
        if (result.contains("%time%")) {
            String t = main.getTimerManager().getTimerPlaceholder(playerUuid);
            result = result.replace("%time%", t == null ? "0.000" : t);
        }
        if (result.contains("%blocks%")) {
            result = result.replace("%blocks%", String.valueOf(main.getTimerManager().getBlockCountPlaceholder(playerUuid)));
        }
        if (result.contains("%coins%")) {
            result = result.replace("%coins%", String.valueOf(main.getTimerManager().getCoinsPlaceholder(playerUuid)));
        }
        if (result.contains("%receivedcoins%")) {
            // Only meaningful at the moment of finishing a run; not meaningful on a continuously
            // updating statboard, so it always resolves to 0 here (same convention the scoreboard
            // placeholders use outside of the finish message itself).
            result = result.replace("%receivedcoins%", "0");
        }
        if (result.contains("%xp%")) {
            result = result.replace("%xp%", String.valueOf(stats.getXp(playerUuid)));
        }
        if (result.contains("%level%")) {
            result = result.replace("%level%", String.valueOf(main.getTimerManager().getLevelForXp(stats.getXp(playerUuid))));
        }
        if (result.contains("%next_level_xp%")) {
            result = result.replace("%next_level_xp%", String.valueOf(main.getTimerManager().getNextLevelXpForXp(stats.getXp(playerUuid))));
        }
        if (result.contains("%bossbar_percent%")) {
            result = result.replace("%bossbar_percent%", String.valueOf(main.getTimerManager().getBossbarPercentForXp(stats.getXp(playerUuid))));
        }
        if (result.contains("%pbdiff%")) {
            double current = 0.0;
            try {
                current = Double.parseDouble(main.getTimerManager().getTimerPlaceholder(playerUuid));
            } catch (Throwable ignored) {
            }
            String formatted;
            if (pb <= 0) {
                formatted = "&e-0.000";
            } else {
                double diff = current - pb;
                String f = String.format(Locale.ROOT, "%.3f", Math.abs(diff));
                if (diff > 0) formatted = "&c+" + f;
                else if (diff < 0) formatted = "&a-" + f;
                else formatted = "&e-0.000";
            }
            result = result.replace("%pbdiff%", formatted);
        }
        if (result.contains("%sessiontop1%") || result.contains("%sessiontop2%") || result.contains("%sessiontop3%") || result.contains("%sessiontop4%") || result.contains("%sessiontop5%")) {
            for (int i = 1; i <= 5; i++) {
                String key = "%sessiontop" + i + "%";
                if (result.contains(key) && player != null) {
                    String entry = main.getSessionTopManager().getFormattedEntry(arenaName, i, player);
                    result = result.replace(key, entry);
                }
            }
        }

        return ChatColor.translateAlternateColorCodes('&', result);
    }

    public void cleanup() {
        for (UUID u : statboards.keySet().stream().collect(Collectors.toList())) {
            removeStatboard(u);
        }
        cancelUpdater();
    }
}
