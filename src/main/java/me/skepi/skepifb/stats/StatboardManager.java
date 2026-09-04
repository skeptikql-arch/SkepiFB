package me.skepi.skepifb.stats;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaIsland;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Locale;

public class StatboardManager {

    // Marks every armor stand this class spawns as ours, independent of this session's in-memory
    // tracking maps below - see SkepiFBPlugin#removeLeftoverManagedEntities() for why that
    // distinction matters (it's what lets a leftover hologram from an unclean previous shutdown
    // get found and removed on the next startup, instead of persisting forever).
    private static final String HOLOGRAM_TAG_VALUE = "statboard";

    private final JavaPlugin plugin;
    private final SkepiFBPlugin main;
    private final NamespacedKey hologramKey;
    private final Map<UUID, List<ArmorStand>> statboards = new ConcurrentHashMap<>();
    private final Map<UUID, Location> statboardBases = new ConcurrentHashMap<>();
    private int taskId = -1;

    public StatboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.main = (SkepiFBPlugin) plugin;
        this.hologramKey = new NamespacedKey(plugin, "skepifb_hologram");
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

        List<String> lines = buildStatboardLines(playerUuid);

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
                    asn.setCustomName(safeArmorStandName(replacePlaceholders(playerUuid, lineText)));
                    asn.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, HOLOGRAM_TAG_VALUE);
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
            statboardBases.put(playerUuid, hologramBase);
        }
    }

    /**
     * Builds this player's full set of statboard lines by rendering each named section from
     * "statboard.order" (top to bottom) and stitching them together, skipping any section that
     * renders no lines at all (e.g. "leaderboard" for a player with no placements) - a skipped
     * section contributes no gap either, so two sections are never separated by more blank space
     * than "statboard.section-spacing" configures. New sections can be added to buildSection()
     * later and immediately become orderable via "statboard.order" without anything else changing.
     */
    private List<String> buildStatboardLines(UUID playerUuid) {
        List<String> order = readSectionOrder();
        int spacing = Math.max(0, main.getConfigManager().getConfiguration().getInt("statboard.section-spacing", 2));

        List<String> combined = new ArrayList<>();
        for (String sectionName : order) {
            List<String> sectionLines = buildSection(sectionName, playerUuid);
            if (sectionLines.isEmpty()) {
                continue;
            }
            if (!combined.isEmpty()) {
                for (int i = 0; i < spacing; i++) {
                    combined.add("");
                }
            }
            combined.addAll(sectionLines);
        }

        if (combined.isEmpty()) {
            // Every configured section rendered empty (e.g. a typo'd order list) - always fall
            // back to the stats section so the hologram never ends up completely blank.
            combined.addAll(buildStatsSectionLines());
        }
        return combined;
    }

    /**
     * "statboard.order" - which named sections appear in the statboard hologram and in what order,
     * top to bottom. Accepts either a normal YAML list:
     *   order:
     *     - "leaderboard"
     *     - "stats"
     * or, since a plain comma-separated line is sometimes easier to hand-edit, a single string:
     *   order: "leaderboard, stats"
     * Falls back to the default order (leaderboard, then stats) if the key is missing, empty, or
     * unreadable.
     */
    private List<String> readSectionOrder() {
        Object raw = main.getConfigManager().getConfiguration().get("statboard.order");
        List<String> order = new ArrayList<>();
        if (raw instanceof List<?> rawList) {
            for (Object entry : rawList) {
                if (entry == null) continue;
                String value = entry.toString().trim();
                if (!value.isEmpty()) order.add(value);
            }
        } else if (raw instanceof String rawString) {
            for (String part : rawString.split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) order.add(value);
            }
        }
        if (order.isEmpty()) {
            order.add("leaderboard");
            order.add("stats");
        }
        return order;
    }

    private List<String> buildSection(String sectionName, UUID playerUuid) {
        if (sectionName == null) return List.of();
        switch (sectionName.trim().toLowerCase(Locale.ROOT)) {
            case "leaderboard":
                return buildLeaderboardSectionLines(playerUuid);
            case "stats":
                return buildStatsSectionLines();
            default:
                return List.of();
        }
    }

    private List<String> buildStatsSectionLines() {
        List<String> statLines = main.getConfigManager().getConfiguration().getStringList("statboard.lines");
        if (statLines == null || statLines.isEmpty()) statLines = List.of(
                "%player%&f's Statboard",
                "&e&lBridging Statistics &7- &e%mode%",
                "&bPersonal Best &7- &e%pb% &7[&bTop &e%top%&7]",
                "&bAverage Time &7- &e%averagetime%",
                "&bCompletions &7- &e%completions%",
                "&bAttempts &7- &e%attempts%"
        );
        return statLines;
    }

    /**
     * Renders the leaderboard section for a player: an empty list (meaning "don't show this
     * section at all", and contribute no separator gap either - see buildStatboardLines) if the
     * feature is disabled or the player holds no leaderboard placements at all, otherwise the
     * configured title line followed by one line per placement, each rendered from
     * "statboard.leaderboard.line-format" with {PLACE}/{MODE}/{TIME} substituted.
     */
    private List<String> buildLeaderboardSectionLines(UUID playerUuid) {
        List<String> lines = new ArrayList<>();
        if (playerUuid == null) return lines;
        if (!main.getConfigManager().getConfiguration().getBoolean("statboard.leaderboard.enabled", true)) return lines;

        List<me.skepi.skepifb.leaderboard.LeaderboardManager.PlayerLeaderboardEntry> entries =
                main.getLeaderboardManager().getPlayerLeaderboardEntries(playerUuid);
        if (entries.isEmpty()) return lines;

        String title = main.getConfigManager().getConfiguration().getString("statboard.leaderboard.title", "&d&lGLOBAL LEADERBOARD PLAYER");
        String lineFormat = main.getConfigManager().getConfiguration().getString("statboard.leaderboard.line-format", "&b#{PLACE} &eon {MODE} Mode &7- &b{TIME}");

        if (title != null && !title.isBlank()) {
            lines.add(title);
        }
        for (me.skepi.skepifb.leaderboard.LeaderboardManager.PlayerLeaderboardEntry entry : entries) {
            String rendered = lineFormat
                    .replace("{PLACE}", String.valueOf(entry.getPosition()))
                    .replace("{MODE}", entry.getModeDisplayName())
                    .replace("{TIME}", String.format(Locale.ROOT, "%.3f", entry.getScore()));
            lines.add(rendered);
        }
        return lines;
    }

    /**
     * A blank line (used as a spacer between statboard sections, or a genuinely blank configured
     * line) becomes an empty string after color-code translation. Passing an empty string to
     * ArmorStand#setCustomName does NOT hide the nametag - the client falls back to showing the
     * entity's own default name ("Armor Stand") instead, since Minecraft treats an empty custom
     * name as "no custom name set" rather than "custom name is blank". A single space renders as a
     * true blank line while still counting as "a custom name is set".
     */
    private String safeArmorStandName(String text) {
        return (text == null || text.isEmpty()) ? " " : text;
    }

    public void removeStatboard(UUID playerUuid) {
        if (playerUuid == null) return;
        List<ArmorStand> stands = statboards.remove(playerUuid);
        statboardBases.remove(playerUuid);
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
        List<String> lines = buildStatboardLines(playerUuid);

        if (lines.size() != stands.size()) {
            // The leaderboard section can appear/disappear or grow/shrink live (a player gains or
            // loses a placement while standing on their island), which changes the total line
            // count - respawn every armorstand at the same hologram base rather than trying to
            // patch a mismatched line count onto the old stand count.
            rebuildStatboardArmorstands(playerUuid, lines);
            return;
        }

        for (int i = 0; i < stands.size(); i++) {
            ArmorStand as = stands.get(i);
            String text = i < lines.size() ? replacePlaceholders(playerUuid, lines.get(i)) : "";
            try { as.setCustomName(safeArmorStandName(text)); } catch (Throwable ignored) {}
        }
    }

    private void rebuildStatboardArmorstands(UUID playerUuid, List<String> lines) {
        Location hologramBase = statboardBases.get(playerUuid);
        List<ArmorStand> oldStands = statboards.get(playerUuid);
        if (hologramBase == null || hologramBase.getWorld() == null) {
            removeStatboard(playerUuid);
            return;
        }
        if (oldStands != null) {
            for (ArmorStand as : oldStands) {
                try { as.remove(); } catch (Throwable ignored) {}
            }
        }

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
                    asn.setCustomName(safeArmorStandName(replacePlaceholders(playerUuid, lineText)));
                    asn.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, HOLOGRAM_TAG_VALUE);
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
        } else {
            statboards.remove(playerUuid);
            statboardBases.remove(playerUuid);
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
