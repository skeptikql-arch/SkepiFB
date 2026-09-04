package me.skepi.skepifb.templates;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Stores each player's saved spawn-position templates as "data/spawn_templates/<uuid>.yml", one
 * file per player, keyed inside by arena/mode name then by slot (1-5) - templates saved while
 * playing one FastBuilder mode are never shown/loadable while playing a different mode, and
 * everything survives a server restart.
 */
public class SpawnTemplateManager {

    public static final int MAX_TEMPLATES = 5;

    private final JavaPlugin plugin;
    private final File folder;
    private final java.util.Map<UUID, YamlConfiguration> cache = new java.util.HashMap<>();

    public SpawnTemplateManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "spawn_templates");
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
            plugin.getLogger().warning("Failed to save spawn templates for " + uuid + ": " + ex.getMessage());
        }
    }

    private static String base(String arena, int slot) {
        return arena + "." + slot;
    }

    public boolean hasTemplate(UUID uuid, String arena, int slot) {
        if (uuid == null || arena == null || slot < 1 || slot > MAX_TEMPLATES) {
            return false;
        }
        return configFor(uuid).contains(base(arena, slot) + ".x");
    }

    public SpawnTemplate getTemplate(UUID uuid, String arena, int slot) {
        if (!hasTemplate(uuid, arena, slot)) {
            return null;
        }
        YamlConfiguration cfg = configFor(uuid);
        String b = base(arena, slot);
        double x = cfg.getDouble(b + ".x", 0.0);
        double y = cfg.getDouble(b + ".y", 0.0);
        double z = cfg.getDouble(b + ".z", 0.0);
        float yaw = (float) cfg.getDouble(b + ".yaw", 0.0);
        float pitch = (float) cfg.getDouble(b + ".pitch", 0.0);
        return new SpawnTemplate(x, y, z, yaw, pitch);
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

    public boolean saveTemplate(UUID uuid, String arena, int slot, SpawnTemplate template) {
        if (uuid == null || arena == null || slot < 1 || slot > MAX_TEMPLATES || template == null) {
            return false;
        }
        YamlConfiguration cfg = configFor(uuid);
        String b = base(arena, slot);
        cfg.set(b + ".x", template.dx);
        cfg.set(b + ".y", template.dy);
        cfg.set(b + ".z", template.dz);
        cfg.set(b + ".yaw", (double) template.yaw);
        cfg.set(b + ".pitch", (double) template.pitch);
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
