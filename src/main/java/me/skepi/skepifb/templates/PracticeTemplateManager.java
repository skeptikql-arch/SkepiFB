package me.skepi.skepifb.templates;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores each player's saved practice-block templates as "data/practice_templates/<uuid>.yml",
 * one file per player, keyed inside by arena/mode name then by slot (1-5) - so templates saved
 * while playing one FastBuilder mode are never shown/loadable while playing a different mode,
 * and everything survives a server restart (plain YAML on disk, loaded on demand and re-saved
 * immediately after every change).
 */
public class PracticeTemplateManager {

    public static final int MAX_TEMPLATES = 5;

    private final JavaPlugin plugin;
    private final File folder;
    private final Map<UUID, YamlConfiguration> cache = new java.util.HashMap<>();

    public PracticeTemplateManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "practice_templates");
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid.toString() + ".yml");
    }

    private YamlConfiguration configFor(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> YamlConfiguration.loadConfiguration(fileFor(id)));
    }

    private void save(UUID uuid) {
        YamlConfiguration cfg = cache.get(uuid);
        if (cfg == null) {
            return;
        }
        try {
            cfg.save(fileFor(uuid));
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save practice templates for " + uuid + ": " + ex.getMessage());
        }
    }

    private static String base(String arena, int slot) {
        return arena + "." + slot;
    }

    public boolean hasTemplate(UUID uuid, String arena, int slot) {
        if (uuid == null || arena == null || slot < 1 || slot > MAX_TEMPLATES) {
            return false;
        }
        return configFor(uuid).contains(base(arena, slot) + ".blocks");
    }

    public int getBlockCount(UUID uuid, String arena, int slot) {
        if (!hasTemplate(uuid, arena, slot)) {
            return 0;
        }
        return configFor(uuid).getMapList(base(arena, slot) + ".blocks").size();
    }

    public PracticeTemplate getTemplate(UUID uuid, String arena, int slot) {
        if (!hasTemplate(uuid, arena, slot)) {
            return null;
        }
        List<Map<?, ?>> raw = configFor(uuid).getMapList(base(arena, slot) + ".blocks");
        List<PracticeTemplate.BlockEntry> blocks = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            try {
                int x = ((Number) m.get("x")).intValue();
                int y = ((Number) m.get("y")).intValue();
                int z = ((Number) m.get("z")).intValue();
                Object matObj = m.get("material");
                String material = matObj == null ? "STONE" : String.valueOf(matObj);
                blocks.add(new PracticeTemplate.BlockEntry(x, y, z, material));
            } catch (Exception ignored) {
                // Skip malformed entries rather than failing the whole template load
            }
        }
        return new PracticeTemplate(blocks);
    }

    /**
     * First slot (1-5) with no saved template for this player/arena, or -1 if all 5 are full.
     */
    public int findFirstEmptySlot(UUID uuid, String arena) {
        if (uuid == null || arena == null) {
            return -1;
        }
        for (int slot = 1; slot <= MAX_TEMPLATES; slot++) {
            if (!hasTemplate(uuid, arena, slot)) {
                return slot;
            }
        }
        return -1;
    }

    public boolean saveTemplate(UUID uuid, String arena, int slot, List<PracticeTemplate.BlockEntry> blocks) {
        if (uuid == null || arena == null || slot < 1 || slot > MAX_TEMPLATES || blocks == null || blocks.isEmpty()) {
            return false;
        }
        YamlConfiguration cfg = configFor(uuid);
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (PracticeTemplate.BlockEntry entry : blocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", entry.dx);
            m.put("y", entry.dy);
            m.put("z", entry.dz);
            m.put("material", entry.material);
            serialized.add(m);
        }
        cfg.set(base(arena, slot) + ".blocks", serialized);
        save(uuid);
        return true;
    }

    public boolean deleteTemplate(UUID uuid, String arena, int slot) {
        if (!hasTemplate(uuid, arena, slot)) {
            return false;
        }
        configFor(uuid).set(base(arena, slot), null);
        save(uuid);
        return true;
    }
}
