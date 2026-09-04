package me.skepi.skepifb.storage;

import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaBoundary;
import me.skepi.skepifb.arena.ArenaIsland;
import me.skepi.skepifb.arena.Layout;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StorageManager {

    private final JavaPlugin plugin;
    private final File arenasFile;
    private FileConfiguration configuration;

    public StorageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.arenasFile = new File(dataFolder, "arenas.yml");
        createDefaultArenasFileIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(arenasFile);
    }

    public void reloadConfiguration() {
        if (!arenasFile.exists()) {
            createDefaultArenasFileIfMissing();
        }
        this.configuration = YamlConfiguration.loadConfiguration(arenasFile);
    }

    public void loadArenas(Map<String, Arena> target) {
        if (target == null) {
            return;
        }
        reloadConfiguration();
        target.clear();
        if (!arenasFile.exists()) {
            return;
        }
        configuration.options().copyDefaults(true);

        for (String key : configuration.getKeys(false)) {
            String name = key;
            String schematic = configuration.getString(key + ".schematic", "");
            int islandCount = configuration.getInt(key + ".islandCount", 0);
            int spacing = configuration.getInt(key + ".spacing", 20);
            Layout layout = Layout.fromString(configuration.getString(key + ".layout", "straight"));
            int originX = configuration.getInt(key + ".originX", 0);
            int originY = configuration.getInt(key + ".originY", 100);
            int originZ = configuration.getInt(key + ".originZ", 0);
            float spawnYaw = configuration.contains(key + ".spawnYaw") ? (float) configuration.getDouble(key + ".spawnYaw") : 0.0f;
            float spawnPitch = configuration.contains(key + ".spawnPitch") ? (float) configuration.getDouble(key + ".spawnPitch") : 0.0f;
            ArenaBoundary boundary;
            if (configuration.isConfigurationSection(key + ".boundary")) {
                int boundaryLeft = configuration.getInt(key + ".boundary.left", -1);
                int boundaryRight = configuration.getInt(key + ".boundary.right", -1);
                int boundaryBack = configuration.getInt(key + ".boundary.back", -1);
                int boundaryDown = configuration.getInt(key + ".boundary.down", -1);
                int boundaryForward = configuration.getInt(key + ".boundary.forward", -1);
                int boundaryUp = configuration.getInt(key + ".boundary.up", -1);
                boundary = new ArenaBoundary(boundaryLeft, boundaryRight, boundaryBack, boundaryDown, boundaryForward, boundaryUp);
            } else {
                boundary = ArenaBoundary.defaultBoundary(spacing, layout);
            }
            Arena arena = new Arena(name, schematic, islandCount, spacing, layout, originX, originY, originZ, boundary, spawnYaw, spawnPitch);
            target.put(name.toLowerCase(), arena);
        }
    }

    public void saveArenas(Map<String, Arena> source) {
        configuration.options().copyDefaults(false);
        for (String key : source.keySet()) {
            configuration.set(key, null);
        }

        for (Arena arena : source.values()) {
            String key = arena.getName();
            configuration.set(key + ".schematic", arena.getSchematic());
            configuration.set(key + ".islandCount", arena.getIslandCount());
            configuration.set(key + ".spacing", arena.getSpacing());
            configuration.set(key + ".layout", arena.getLayout().name().toLowerCase());
            configuration.set(key + ".originX", arena.getOriginX());
            configuration.set(key + ".originY", arena.getOriginY());
            configuration.set(key + ".originZ", arena.getOriginZ());
            configuration.set(key + ".spawnYaw", arena.getSpawnYaw());
            configuration.set(key + ".spawnPitch", arena.getSpawnPitch());
            configuration.set(key + ".boundary.left", arena.getBoundary().getLeft());
            configuration.set(key + ".boundary.right", arena.getBoundary().getRight());
            configuration.set(key + ".boundary.back", arena.getBoundary().getBack());
            configuration.set(key + ".boundary.down", arena.getBoundary().getDown());
            configuration.set(key + ".boundary.forward", arena.getBoundary().getForward());
            configuration.set(key + ".boundary.up", arena.getBoundary().getUp());
        }

        try {
            configuration.save(arenasFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save arena data: " + ex.getMessage());
        }
    }

    private void createDefaultArenasFileIfMissing() {
        if (arenasFile.exists()) {
            return;
        }

        String defaultContent = "# SkepiFB arenas configuration\n"
                + "#\n"
                + "# Each arena is keyed by name.\n"
                + "# spacing: distance between islands.\n"
                + "# layout: straight or diagonal.\n"
                + "# originX/originY/originZ: island origin coordinates.\n"
                + "# boundary: leave -1 for unlimited in that direction.\n"
                + "# origin: the starting location for the arena layout.\n"
                + "#\n"
                + "# Example:\n"
                + "# myArena:\n"
                + "#   schematic: example\n"
                + "#   islandCount: 4\n"
                + "#   spacing: 20\n"
                + "#   layout: straight\n"
                + "#   originX: 0\n"
                + "#   originY: 100\n"
                + "#   originZ: 0\n"
                + "#   boundary:\n"
                + "#     left: -1\n"
                + "#     right: -1\n"
                + "#     back: -1\n"
                + "#     down: -1\n"
                + "#     forward: -1\n"
                + "#     up: -1\n";

        try {
            Files.writeString(arenasFile.toPath(), defaultContent, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default arenas.yml: " + ex.getMessage());
        }
    }
}
