package me.skepi.skepifb.stats;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerStatsManager {

    private final JavaPlugin plugin;
    private final File statsFile;
    private FileConfiguration configuration;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    public PlayerStatsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.statsFile = new File(dataFolder, "stats.yml");
        createDefaultStatsFileIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(statsFile);
        loadStats();
    }

    private void createDefaultStatsFileIfMissing() {
        if (statsFile.exists()) {
            return;
        }

        String defaultContent = "players:\n";
        try {
            Files.write(statsFile.toPath(), defaultContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default stats.yml: " + ex.getMessage());
        }
    }

    private void loadStats() {
        if (!statsFile.exists()) {
            createDefaultStatsFileIfMissing();
        }

        configuration = YamlConfiguration.loadConfiguration(statsFile);
        if (!configuration.isConfigurationSection("players")) {
            return;
        }

        for (String playerKey : configuration.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID playerUuid = UUID.fromString(playerKey);
                int coins = configuration.getInt("players." + playerKey + ".coins", 0);
                Map<String, Double> personalBests = new LinkedHashMap<>();
                Map<String, Integer> attempts = new LinkedHashMap<>();
                if (configuration.isConfigurationSection("players." + playerKey + ".personalBests")) {
                    for (String arenaName : configuration.getConfigurationSection("players." + playerKey + ".personalBests").getKeys(false)) {
                        double bestTime = configuration.getDouble("players." + playerKey + ".personalBests." + arenaName, 0.0);
                        personalBests.put(arenaName, bestTime);
                    }
                }
                if (configuration.isConfigurationSection("players." + playerKey + ".attempts")) {
                    for (String arenaName : configuration.getConfigurationSection("players." + playerKey + ".attempts").getKeys(false)) {
                        int attemptCount = configuration.getInt("players." + playerKey + ".attempts." + arenaName, 0);
                        attempts.put(arenaName, attemptCount);
                    }
                }
                Map<String, Integer> completions = new LinkedHashMap<>();
                Map<String, Double> totalCompletionTime = new LinkedHashMap<>();
                if (configuration.isConfigurationSection("players." + playerKey + ".completions")) {
                    for (String arenaName : configuration.getConfigurationSection("players." + playerKey + ".completions").getKeys(false)) {
                        int completionCount = configuration.getInt("players." + playerKey + ".completions." + arenaName, 0);
                        completions.put(arenaName, completionCount);
                    }
                }
                if (configuration.isConfigurationSection("players." + playerKey + ".totalCompletionTime")) {
                    for (String arenaName : configuration.getConfigurationSection("players." + playerKey + ".totalCompletionTime").getKeys(false)) {
                        double totalTime = configuration.getDouble("players." + playerKey + ".totalCompletionTime." + arenaName, 0.0);
                        totalCompletionTime.put(arenaName, totalTime);
                    }
                }
                int xp = configuration.getInt("players." + playerKey + ".xp", 0);
                stats.put(playerUuid, new PlayerStats(playerUuid, coins, personalBests, attempts, completions, totalCompletionTime, xp));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid player UUID in stats.yml: " + playerKey);
            }
        }
    }

    public int getCoins(UUID playerUuid) {
        return getOrCreateStats(playerUuid).coins;
    }

    public void addCoins(UUID playerUuid, int amount) {
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        playerStats.coins += amount;
        saveStats();
    }

    public void setCoins(UUID playerUuid, int amount) {
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        playerStats.coins = amount;
        saveStats();
    }

    public double getPersonalBest(UUID playerUuid, String arenaName) {
        if (arenaName == null) {
            return 0.0;
        }
        return getOrCreateStats(playerUuid).personalBests.getOrDefault(arenaName, 0.0);
    }

    public java.util.List<Double> getPersonalBestsForArena(String arenaName) {
        if (arenaName == null) {
            return java.util.List.of();
        }
        java.util.List<Double> result = new java.util.ArrayList<>();
        for (PlayerStats playerStats : stats.values()) {
            double best = playerStats.personalBests.getOrDefault(arenaName, 0.0);
            if (best > 0.0) {
                result.add(best);
            }
        }
        return result;
    }

    public boolean updatePersonalBestIfFaster(UUID playerUuid, String arenaName, double finishedTime) {
        if (arenaName == null) {
            return false;
        }
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        double currentBest = playerStats.personalBests.getOrDefault(arenaName, 0.0);
        if (currentBest == 0.0 || finishedTime < currentBest) {
            playerStats.personalBests.put(arenaName, finishedTime);
            saveStats();
            return true;
        }
        return false;
    }

    public int getAttempts(UUID playerUuid, String arenaName) {
        if (arenaName == null) {
            return 0;
        }
        return getOrCreateStats(playerUuid).attempts.getOrDefault(arenaName, 0);
    }

    public void incrementAttempts(UUID playerUuid, String arenaName) {
        if (arenaName == null) {
            return;
        }
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        int current = playerStats.attempts.getOrDefault(arenaName, 0);
        playerStats.attempts.put(arenaName, current + 1);
        saveStats();
    }

    public int getCompletions(UUID playerUuid, String arenaName) {
        if (arenaName == null) {
            return 0;
        }
        return getOrCreateStats(playerUuid).completions.getOrDefault(arenaName, 0);
    }

    public double getAverageCompletionTime(UUID playerUuid, String arenaName) {
        if (arenaName == null) {
            return 0.0;
        }
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        int completions = playerStats.completions.getOrDefault(arenaName, 0);
        if (completions <= 0) {
            return 0.0;
        }
        double totalTime = playerStats.totalCompletionTime.getOrDefault(arenaName, 0.0);
        return totalTime / completions;
    }

    public void recordCompletion(UUID playerUuid, String arenaName, double finishedTime) {
        if (arenaName == null || finishedTime <= 0.0) {
            return;
        }
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        int currentCount = playerStats.completions.getOrDefault(arenaName, 0);
        double currentTotal = playerStats.totalCompletionTime.getOrDefault(arenaName, 0.0);
        playerStats.completions.put(arenaName, currentCount + 1);
        playerStats.totalCompletionTime.put(arenaName, currentTotal + finishedTime);
        saveStats();
    }

    public int getXp(UUID playerUuid) {
        return getOrCreateStats(playerUuid).xp;
    }

    public void addXp(UUID playerUuid, int amount) {
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        playerStats.xp = Math.max(0, playerStats.xp + amount);
        saveStats();
    }

    public void setXp(UUID playerUuid, int amount) {
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        playerStats.xp = Math.max(0, amount);
        saveStats();
    }

    public void resetStatsForArena(UUID playerUuid, String arenaName) {
        if (playerUuid == null || arenaName == null) return;
        PlayerStats playerStats = getOrCreateStats(playerUuid);
        if (playerStats == null) return;
        playerStats.personalBests.remove(arenaName);
        playerStats.attempts.remove(arenaName);
        playerStats.completions.remove(arenaName);
        playerStats.totalCompletionTime.remove(arenaName);
        saveStats();
    }

    private PlayerStats getOrCreateStats(UUID playerUuid) {
        return stats.computeIfAbsent(playerUuid, uuid -> new PlayerStats(uuid, 0, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), 0));
    }

    public void saveStats() {
        configuration.options().copyDefaults(false);
        configuration.set("players", null);

        for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
            String playerKey = entry.getKey().toString();
            PlayerStats playerStats = entry.getValue();
            configuration.set("players." + playerKey + ".coins", playerStats.coins);
            configuration.set("players." + playerKey + ".personalBests", null);
            for (Map.Entry<String, Double> bestEntry : playerStats.personalBests.entrySet()) {
                configuration.set("players." + playerKey + ".personalBests." + bestEntry.getKey(), bestEntry.getValue());
            }
            configuration.set("players." + playerKey + ".attempts", null);
            for (Map.Entry<String, Integer> attemptEntry : playerStats.attempts.entrySet()) {
                configuration.set("players." + playerKey + ".attempts." + attemptEntry.getKey(), attemptEntry.getValue());
            }
            configuration.set("players." + playerKey + ".completions", null);
            for (Map.Entry<String, Integer> completionEntry : playerStats.completions.entrySet()) {
                configuration.set("players." + playerKey + ".completions." + completionEntry.getKey(), completionEntry.getValue());
            }
            configuration.set("players." + playerKey + ".totalCompletionTime", null);
            for (Map.Entry<String, Double> totalTimeEntry : playerStats.totalCompletionTime.entrySet()) {
                configuration.set("players." + playerKey + ".totalCompletionTime." + totalTimeEntry.getKey(), totalTimeEntry.getValue());
            }
            configuration.set("players." + playerKey + ".xp", playerStats.xp);
        }

        try {
            configuration.save(statsFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save stats.yml: " + ex.getMessage());
        }
    }

    private static final class PlayerStats {
        private final UUID playerUuid;
        private int coins;
        private final Map<String, Double> personalBests;
        private final Map<String, Integer> attempts;
        private final Map<String, Integer> completions;
        private final Map<String, Double> totalCompletionTime;
        private int xp;

        private PlayerStats(UUID playerUuid, int coins, Map<String, Double> personalBests, Map<String, Integer> attempts, Map<String, Integer> completions, Map<String, Double> totalCompletionTime, int xp) {
            this.playerUuid = playerUuid;
            this.coins = coins;
            this.personalBests = personalBests;
            this.attempts = attempts;
            this.completions = completions;
            this.totalCompletionTime = totalCompletionTime;
            this.xp = Math.max(0, xp);
        }
    }
}
