package me.skepi.skepifb.hotbar;

import org.bukkit.Material;

public class HotbarItem {
    private final int slot;
    private final Material material;
    private final String displayName;
    private final HotbarAction action;

    public HotbarItem(int slot, Material material, String displayName, HotbarAction action) {
        this.slot = slot;
        this.material = material;
        this.displayName = displayName;
        this.action = action;
    }

    public int getSlot() {
        return slot;
    }

    public Material getMaterial() {
        return material;
    }

    public String getDisplayName() {
        return displayName;
    }

    public HotbarAction getAction() {
        return action;
    }
}
