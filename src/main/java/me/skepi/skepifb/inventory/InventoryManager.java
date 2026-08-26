package me.skepi.skepifb.inventory;

import me.skepi.skepifb.config.ConfigManager;
import me.skepi.skepifb.SkepiFBPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class InventoryManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ArenaBlockProvider blockProvider;

    public InventoryManager(JavaPlugin plugin, ConfigManager configManager, ArenaBlockProvider blockProvider) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.blockProvider = blockProvider;
    }

    public Material getArenaBlockMaterial() {
        return blockProvider.getArenaBlockMaterial();
    }

    public void giveArenaBlock(Player player) {
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        me.skepi.skepifb.hotbar.HotbarManager hotbarManager = main.getHotbarManager();
        Integer blockSlot = hotbarManager.getBlockSlot();
        ItemStack arenaStack = buildArenaBlockStack(player);
        player.getInventory().setItem(blockSlot != null ? blockSlot : 0, arenaStack);
    }

    public void refillHotbarIfNeeded(Player player) {
        // Only refill slots configured with action=BLOCK and when amount <= threshold
        try {
            SkepiFBPlugin main = (SkepiFBPlugin) plugin;
            me.skepi.skepifb.hotbar.HotbarManager hotbarManager = main.getHotbarManager();
            int threshold = configManager.getRefillBlockThreshold();
            for (int slot = 0; slot < 9; slot++) {
                ItemStack slotItem = player.getInventory().getItem(slot);
                if (slotItem == null) continue;
                me.skepi.skepifb.hotbar.HotbarItem configured = hotbarManager.getHotbarItem(slot);
                if (configured == null) continue;
                if (configured.getAction() == me.skepi.skepifb.hotbar.HotbarAction.BLOCK || configured.getAction() == me.skepi.skepifb.hotbar.HotbarAction.PRACTICE_BLOCK) {
                    if (slotItem.getAmount() <= threshold) {
                        ItemStack replacement = hotbarManager.buildConfiguredHotbarItem(slot, player.getUniqueId());
                        if (replacement != null) {
                            player.getInventory().setItem(slot, replacement);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private ItemStack buildArenaBlockStack(Player player) {
        Material material = getArenaBlockMaterial();
        try {
            SkepiFBPlugin main = (SkepiFBPlugin) plugin;
            if (main.getShopManager() != null && player != null) {
                Material equipped = main.getShopManager().getEquippedBlockMaterial(player.getUniqueId());
                if (equipped != null) {
                    material = equipped;
                }
            }
        } catch (Throwable ignored) {
        }
        int max = Math.min(material.getMaxStackSize(), 64);
        return new ItemStack(material, max);
    }

    private boolean isArenaBlock(ItemStack item) {
        return item != null && item.getType() == getArenaBlockMaterial();
    }
}
