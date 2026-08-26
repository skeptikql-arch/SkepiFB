package me.skepi.skepifb.inventory;

import me.skepi.skepifb.config.ConfigManager;
import org.bukkit.Material;

public class ArenaBlockProvider {

    private final ConfigManager configManager;

    public ArenaBlockProvider(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Material getArenaBlockMaterial() {
        try {
            org.bukkit.configuration.ConfigurationSection hotbar = configManager.getHotbarSection();
            if (hotbar != null) {
                org.bukkit.configuration.ConfigurationSection items = hotbar.getConfigurationSection("items");
                if (items != null) {
                    org.bukkit.configuration.ConfigurationSection slot0 = items.getConfigurationSection("slot0");
                    if (slot0 != null) {
                        String materialName = slot0.getString("material", "SANDSTONE");
                        Material m = Material.matchMaterial(materialName);
                        if (m != null) return m;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return Material.SANDSTONE;
    }
}
