package me.skepi.skepifb.scoreboard;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.timer.TimerManager;
import org.bukkit.Bukkit;
import me.skepi.skepifb.stats.PlayerStatsManager;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ScoreboardManager {

    private static final int MAX_LINES = 15;

    private final JavaPlugin plugin;
    private final TimerManager timerManager;
    private final PlayerManager playerManager;
    private final File scoreboardFile;
    private YamlConfiguration configuration;
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();
    private BukkitTask updateTask;

    public ScoreboardManager(JavaPlugin plugin, TimerManager timerManager, PlayerManager playerManager) {
        this.plugin = plugin;
        this.timerManager = timerManager;
        this.playerManager = playerManager;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.scoreboardFile = new File(dataFolder, "scoreboard.yml");
        loadScoreboard();
        scheduleUpdates();
    }

    private void loadScoreboard() {
        createDefaultScoreboardIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(scoreboardFile);
    }

    private void createDefaultScoreboardIfMissing() {
        if (scoreboardFile.exists()) {
            return;
        }

        String defaultContent = "# SkepiFB scoreboard configuration.\n"
            + "#\n"
            + "# title: The title shown at the top of the scoreboard.\n"
            + "# lines: Up to 15 lines are displayed below the title.\n"
            + "#\n"
            + "# Available placeholders:\n"
            + "#   %timer%    - current attempt timer (seconds with millisecond precision)\n"
            + "#   %blocks%   - blocks placed in current attempt\n"
            + "#   %coins%    - total coins accumulated\n"
            + "#   %attempts% - total attempts in the current arena\n"
            + "#   %pb%       - personal best for current arena (N/A if none)\n"
            + "#   %sessiontop1%..%sessiontop5% - runtime-only Session Top 5 for the player's current arena\n"
            + "#\n"
            + "# Session Top 5 is per-arena and exists only while the server is running.\n"
            + "# Entries are removed immediately when players leave the arena or disconnect.\n"
            + "#\n"
                + "title: \"&6&lSkepiFB\"\n"
                + "lines:\n"
                + "  - \"&7Time: %timer%\"\n"
                + "  - \"&aPB: %pb%\"\n"
                + "  - \"\"\n"
                + "  - \"&dAttempts: %attempts%\"\n"
                + "  - \"&bCoins: %coins%\"\n"
                + "  - \"&eBlocks: %blocks%\"\n"
                + "  - \"\"\n"
            + "  - \"&6Session Top\"\n"
            + "  - \"%sessiontop1%\"\n"
            + "  - \"%sessiontop2%\"\n"
            + "  - \"%sessiontop3%\"\n"
            + "  - \"%sessiontop4%\"\n"
            + "  - \"%sessiontop5%\"\n"
            + "  - \"\"\n"
            + "  - \"&aexample.net\"\n";

        try {
            Files.write(scoreboardFile.toPath(), defaultContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default scoreboard.yml: " + ex.getMessage());
        }
    }

    private void scheduleUpdates() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateScoreboards, 1L, 1L);
    }

    public void showScoreboard(Player player) {
        if (playerScoreboards.containsKey(player.getUniqueId())) {
            return;
        }

        Scoreboard scoreboard = buildScoreboard(player);
        player.setScoreboard(scoreboard);
        playerScoreboards.put(player.getUniqueId(), scoreboard);
    }

    public void hideScoreboard(Player player) {
        playerScoreboards.remove(player.getUniqueId());
        org.bukkit.scoreboard.ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager == null) {
            return;
        }
        player.setScoreboard(scoreboardManager.getNewScoreboard());
    }

    public void reload() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        loadScoreboard();
        playerScoreboards.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (playerManager.isInArena(player.getUniqueId()) || playerManager.isInTestMode(player.getUniqueId())) {
                showScoreboard(player);
            } else {
                hideScoreboard(player);
            }
        }
        scheduleUpdates();
    }

    private void updateScoreboards() {
        for (UUID playerUuid : playerScoreboards.keySet().stream().collect(Collectors.toList())) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid))) {
                playerScoreboards.remove(playerUuid);
                continue;
            }
            Scoreboard scoreboard = playerScoreboards.get(playerUuid);
            if (scoreboard == null) {
                continue;
            }
            refreshScoreboard(player, scoreboard);
        }
    }

    private Scoreboard buildScoreboard(Player player) {
        org.bukkit.scoreboard.ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            throw new IllegalStateException("Scoreboard manager is not available.");
        }

        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("skepifb", "dummy", translateColorCodes(configuration.getString("title", "&6&lSkepiFB")));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        refreshScoreboard(player, scoreboard);
        return scoreboard;
    }

    private void refreshScoreboard(Player player, Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective("skepifb");
        if (objective == null) {
            return;
        }

        List<String> lines = configuration.getStringList("lines");
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }

        // Build the desired entries
        Map<Integer, String> desiredEntries = new HashMap<>();
        int score = lines.size();
        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index);
            String renderedLine = translateColorCodes(replacePlaceholders(player, rawLine));
            String uniqueEntry = makeUniqueEntry(renderedLine, index);
            desiredEntries.put(score, uniqueEntry);
            score--;
        }

        // Remove entries that are no longer needed
        for (String existingEntry : new java.util.ArrayList<>(scoreboard.getEntries())) {
            org.bukkit.scoreboard.Score existingScore = objective.getScore(existingEntry);
            if (!desiredEntries.values().contains(existingEntry)) {
                scoreboard.resetScores(existingEntry);
            } else if (!desiredEntries.containsValue(existingEntry) || existingScore.getScore() != getScoreForEntry(existingEntry, desiredEntries)) {
                scoreboard.resetScores(existingEntry);
            }
        }

        // Update or add entries
        for (Map.Entry<Integer, String> entry : desiredEntries.entrySet()) {
            objective.getScore(entry.getValue()).setScore(entry.getKey());
        }
    }

    private int getScoreForEntry(String entry, Map<Integer, String> desiredEntries) {
        for (Map.Entry<Integer, String> e : desiredEntries.entrySet()) {
            if (e.getValue().equals(entry)) {
                return e.getKey();
            }
        }
        return 0;
    }

    public double getTopPercentile(UUID playerUuid, String arenaName) {
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        PlayerStatsManager stats = main.getStatsManager();
        double playerPb = stats.getPersonalBest(playerUuid, arenaName);
        if (playerPb <= 0.0) {
            return -1.0;
        }

        List<Double> allPbs = stats.getPersonalBestsForArena(arenaName);
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

    public String replacePlaceholders(Player player, String line) {
        if (line == null) {
            return "";
        }
        String result = line;
        UUID playerUuid = player.getUniqueId();
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;

        if (result.contains("%timer%")) {
            result = result.replace("%timer%", timerManager.getTimerPlaceholder(playerUuid));
        }
        if (result.contains("%time%")) {
            // show current timer as time (fallback to 0.000)
            String t = timerManager.getTimerPlaceholder(playerUuid);
            result = result.replace("%time%", t == null ? "0.000" : t);
        }
        if (result.contains("%blocks%")) {
            result = result.replace("%blocks%", String.valueOf(timerManager.getBlockCountPlaceholder(playerUuid)));
        }
        if (result.contains("%coins%")) {
            result = result.replace("%coins%", String.valueOf(timerManager.getCoinsPlaceholder(playerUuid)));
        }
        if (result.contains("%receivedcoins%")) {
            // Only meaningful at finish; show 0 otherwise
            result = result.replace("%receivedcoins%", "0");
        }
        if (result.contains("%attempts%")) {
            String arenaName = playerManager.getPlayerArena(playerUuid);
            int attempts = arenaName != null ? timerManager.getAttemptsPlaceholder(playerUuid, arenaName) : 0;
            result = result.replace("%attempts%", String.valueOf(attempts));
        }
        if (result.contains("%pb%")) {
            String arenaName = playerManager.getPlayerArena(playerUuid);
            double pb = arenaName != null ? timerManager.getPersonalBestPlaceholder(playerUuid, arenaName) : 0.0;
            result = result.replace("%pb%", pb == 0.0 ? "N/A" : String.format("%.3f", pb));
        }
        if (result.contains("%player%")) {
            PlayerStatsManager stats = main.getStatsManager();
            Player playerObj = Bukkit.getPlayer(playerUuid);
            String playerName = playerObj == null ? "" : playerObj.getName();
            result = result.replace("%player%", playerName);
        }
        if (result.contains("%speed%")) {
            result = result.replace("%speed%", timerManager.getSpeedPlaceholder(playerUuid));
        }
        if (result.contains("%averagespeed%")) {
            result = result.replace("%averagespeed%", timerManager.getAverageSpeedPlaceholder(playerUuid));
        }
        if (result.contains("%xp%")) {
            result = result.replace("%xp%", String.valueOf(main.getStatsManager().getXp(playerUuid)));
        }
        if (result.contains("%level%")) {
            result = result.replace("%level%", String.valueOf(timerManager.getLevelForXp(main.getStatsManager().getXp(playerUuid))));
        }
        if (result.contains("%next_level_xp%")) {
            result = result.replace("%next_level_xp%", String.valueOf(timerManager.getNextLevelXpForXp(main.getStatsManager().getXp(playerUuid))));
        }
        if (result.contains("%bossbar_percent%")) {
            result = result.replace("%bossbar_percent%", String.valueOf(timerManager.getBossbarPercentForXp(main.getStatsManager().getXp(playerUuid))));
        }
        if (result.contains("%mode%") || result.contains("%top%") || result.contains("%averagetime%") || result.contains("%completions%")) {
            PlayerStatsManager stats = main.getStatsManager();
            Player playerObj = Bukkit.getPlayer(playerUuid);
            String playerName = playerObj == null ? "" : playerObj.getName();
            String arenaName = playerManager.getPlayerArena(playerUuid);
            String mode = main.getTimerManager().isPracticeMode(playerUuid) ? "Practice" : (arenaName != null ? arenaName : "None");
            double topPercent = arenaName == null ? -1.0 : this.getTopPercentile(playerUuid, arenaName);
            double averageTime = arenaName == null ? 0.0 : stats.getAverageCompletionTime(playerUuid, arenaName);
            int completions = arenaName == null ? 0 : stats.getCompletions(playerUuid, arenaName);

            result = result.replace("%player%", playerName);
            result = result.replace("%mode%", mode);
            result = result.replace("%top%", topPercent < 0.0 ? "N/A" : String.format(Locale.ROOT, "%.2f%%", topPercent));
            result = result.replace("%averagetime%", averageTime <= 0.0 ? "N/A" : String.format(Locale.ROOT, "%.3f", averageTime));
            result = result.replace("%completions%", completions <= 0 ? "N/A" : String.valueOf(completions));
        }

        // pbdiff: compare current timer to stored personal best
        if (result.contains("%pbdiff%")) {
            String arenaName = playerManager.getPlayerArena(playerUuid);
            double pb = arenaName != null ? timerManager.getPersonalBestPlaceholder(playerUuid, arenaName) : 0.0;
            double current = 0.0;
            try {
                current = Double.parseDouble(timerManager.getTimerPlaceholder(playerUuid));
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

        // Session Top placeholders
        if (result.contains("%sessiontop1%") || result.contains("%sessiontop2%") || result.contains("%sessiontop3%") || result.contains("%sessiontop4%") || result.contains("%sessiontop5%")) {
            String arenaName = playerManager.getPlayerArena(playerUuid);
            for (int i = 1; i <= 5; i++) {
                String key = "%sessiontop" + i + "%";
                if (result.contains(key)) {
                    String entry = main.getSessionTopManager().getFormattedEntry(arenaName, i, player);
                    result = result.replace(key, entry);
                }
            }
        }

        return result;
    }

    private String translateColorCodes(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private String makeUniqueEntry(String line, int index) {
        ChatColor suffix = ChatColor.values()[index % ChatColor.values().length];
        return line + suffix;
    }
}
