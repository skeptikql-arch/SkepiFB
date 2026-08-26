package me.skepi.skepifb.hotbar;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.config.ConfigManager;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaIsland;
import me.skepi.skepifb.replay.ReplayManager;
import me.skepi.skepifb.replay.ReplayMetadata;
import me.skepi.skepifb.replay.ReplayRecord;
import me.skepi.skepifb.timer.AttemptSession;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HotbarManager implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private static final int FAILED_REPLAY_SLOT = 10;
    private static final int REPLAY_PREVIOUS_PAGE_SLOT = 47;
    private static final int REPLAY_NEXT_PAGE_SLOT = 51;
    private static final int REPLAY_PB_SLOT = 28;
    private static final int REPLAY_FAVORITE_SLOT = 19;
    private static final int REPLAY_SORT_SLOT = 37;
    // Index 0-3, wrapping in both directions. Order here defines both the click-cycling order and
    // what each index means - keep these two arrays in lockstep with each other and with
    // applyReplaySortOrder(...) below.
    private static final String[] REPLAY_SORT_LABELS = {"Most Recent", "Best Times", "Worst Times", "Oldest"};
    private static final List<Integer> REPLAY_MENU_SLOTS = List.of(
            11, 12, 13, 14, 15, 16,
            20, 21, 22, 23, 24, 25,
            29, 30, 31, 32, 33, 34,
            38, 39, 40, 41, 42, 43
    );

    private final Map<Integer, HotbarItem> hotbarItems = new HashMap<>();
    private final Map<UUID, UUID> replayMenuOwners = new HashMap<>();
    private final Map<UUID, Integer> replayMenuPages = new HashMap<>();
    private final Map<UUID, String> replayMenuArenas = new HashMap<>();
    private final Map<UUID, Integer> replayMenuSortOrder = new HashMap<>();
    private final Map<ItemStack, HotbarItem> itemActionMap = new HashMap<>();
    private final java.util.Set<UUID> statResetInProgress = new java.util.HashSet<>();
    private static final NamespacedKey LEAVE_CONFIRM_KEY = NamespacedKey.fromString("skepifb:leave_action");

    private void logInfo(String message) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info(message);
        } else {
            System.out.println(message);
        }
    }

    public HotbarManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        loadHotbarConfig();
    }

    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Start per-tick inventory validator for FastBuilder players
        try {
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
                        if (main.getTimerManager().isInReplay(p.getUniqueId())) {
                            continue;
                        }
                        if (main.getPlayerManager().isInArena(p.getUniqueId()) || main.getPlayerManager().isInTestMode(p.getUniqueId())) {
                            if (p.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                                try { validateFastBuilderInventory(p); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }, 1L, 1L);
        } catch (Throwable ignored) {}
    }

    public void reload() {
        loadHotbarConfig();
    }

    private void loadHotbarConfig() {
        hotbarItems.clear();
        itemActionMap.clear();

        ConfigurationSection hotbarSection = configManager.getHotbarSection();
        if (hotbarSection == null) {
            plugin.getLogger().warning("Hotbar section not found in config.yml");
            return;
        }

        ConfigurationSection itemsSection = hotbarSection.getConfigurationSection("items");
        if (itemsSection == null) {
            plugin.getLogger().warning("Items section not found in hotbar config");
            return;
        }

        for (String slotKey : itemsSection.getKeys(false)) {
            try {
                ConfigurationSection itemConfig = itemsSection.getConfigurationSection(slotKey);
                if (itemConfig == null) {
                    continue;
                }

                int slot = parseSlotNumber(slotKey);
                if (slot < 0 || slot > 8) {
                    plugin.getLogger().warning("Invalid slot in hotbar config: " + slotKey);
                    continue;
                }

                String materialName = itemConfig.getString("material", "STONE");
                Material material = Material.matchMaterial(materialName);
                if (material == null) {
                    plugin.getLogger().warning("Invalid material in hotbar config: " + materialName);
                    material = Material.STONE;
                }

                String displayName = itemConfig.getString("name", "Item");
                String actionName = itemConfig.getString("action", "none");
                HotbarAction action = HotbarAction.fromString(actionName);

                HotbarItem item = new HotbarItem(slot, material, displayName, action);
                hotbarItems.put(slot, item);

            } catch (Exception ex) {
                plugin.getLogger().warning("Error loading hotbar item " + slotKey + ": " + ex.getMessage());
            }
        }
    }

    private int parseSlotNumber(String slotKey) {
        // Parse "slot0", "slot1", etc.
        if (slotKey.startsWith("slot")) {
            try {
                return Integer.parseInt(slotKey.substring(4));
            } catch (NumberFormatException ex) {
                return -1;
            }
        }
        return -1;
    }

    public void giveHotbarToPlayer(Player player) {
        if (player == null) {
            return;
        }

        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        boolean practiceModeEnabled = mainPlugin.getTimerManager().isPracticeMode(player.getUniqueId());
        if (!practiceModeEnabled) {
            removePracticeBlocksFromInventory(player);
        }

        itemActionMap.clear();
        // Clear hotbar slots (0-8)
        for (int i = 0; i < 9; i++) {
            player.getInventory().setItem(i, null);
        }

        // Give configured items
        for (HotbarItem hotbarItem : hotbarItems.values()) {
            ItemStack item = buildConfiguredHotbarItem(hotbarItem.getSlot(), player.getUniqueId());
            player.getInventory().setItem(hotbarItem.getSlot(), item);
            if (item != null) {
                itemActionMap.put(item, hotbarItem);
            }
        }

        // The rank helmet is owned entirely by TimerManager (see equipRankHelmet) - this used to
        // also be independently enforced here via restoreExpectedHelmet() on every single tick,
        // which raced with TimerManager's own helmet writes (both classes setting the helmet slot
        // in the same window, sometimes with slightly different ItemStacks) and was the actual
        // cause of the helmet intermittently disappearing/flickering. HotbarManager no longer
        // touches the helmet slot at all; it just asks TimerManager to (re)apply the correct one
        // whenever gear is (re)given, e.g. on arena/test-mode entry or respawn.
        if (mainPlugin.getPlayerManager().isInArena(player.getUniqueId()) || mainPlugin.getPlayerManager().isInTestMode(player.getUniqueId())) {
            mainPlugin.getTimerManager().equipRankHelmet(player);
        }

        // Force an inventory sync to the client. Items (including the helmet set just above)
        // are frequently applied very early in a player's lifecycle on the server (e.g. during
        // PlayerJoinEvent, immediately after a teleport, or right after a mode/session toggle).
        // At that point the server-side state is already correct, but the client does not always
        // receive/render the change without an explicit resync. Because the periodic per-tick
        // validator (validateFastBuilderInventory) only resends packets when it detects a mismatch
        // against server-side state, a helmet/hotbar item that is "correct" server-side but never
        // rendered client-side will never get corrected by that watchdog either - the mismatch only
        // exists on the client, which the server can't see. Explicitly syncing here closes that gap.
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
        }
    }

    

    public Integer getBlockSlot() {
        for (HotbarItem hotbarItem : hotbarItems.values()) {
            if (hotbarItem.getAction() == HotbarAction.BLOCK) {
                return hotbarItem.getSlot();
            }
        }
        return null;
    }

    public Integer getPracticeBlockSlot() {
        for (HotbarItem hotbarItem : hotbarItems.values()) {
            if (hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK) {
                return hotbarItem.getSlot();
            }
        }
        return null;
    }

    public void removePracticeBlocksFromInventory(Player player) {
        if (player == null) {
            return;
        }

        java.util.Set<Integer> practiceSlots = new java.util.HashSet<>();
        for (HotbarItem hotbarItem : hotbarItems.values()) {
            if (hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK) {
                practiceSlots.add(hotbarItem.getSlot());
            }
        }

        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            HotbarItem hotbarItem = findHotbarItem(item);
            if (hotbarItem != null && hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK) {
                player.getInventory().setItem(slot, null);
            }
        }

        for (int slot : practiceSlots) {
            player.getInventory().setItem(slot, null);
        }
    }

    public void validateFastBuilderInventory(Player player) {
        if (player == null) {
            return;
        }

        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        if (mainPlugin.getTimerManager().isInReplay(player.getUniqueId())) {
            return;
        }
        if (!mainPlugin.getPlayerManager().isInArena(player.getUniqueId()) && !mainPlugin.getPlayerManager().isInTestMode(player.getUniqueId())) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        boolean changed = false;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack expected = buildConfiguredHotbarItem(slot, playerUuid);
            ItemStack current = player.getInventory().getItem(slot);
            if (!itemStacksMatch(expected, current)) {
                player.getInventory().setItem(slot, expected);
                changed = true;
            }
        }

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() != Material.AIR) {
            player.getInventory().setItemInOffHand(null);
            changed = true;
        }

        // NOTE: this validator does not explicitly touch the helmet slot anywhere above - TimerManager
        // owns rank-helmet equip/restore exclusively (see equipRankHelmet). The "extra inventory
        // slots" cleanup loop below WAS also touching it, indirectly and by accident - see the
        // comment on that loop for the actual root cause found and fixed.

        // Clear other armor slots only if the plugin does not intentionally use them.
        if (player.getInventory().getChestplate() != null && player.getInventory().getChestplate().getType() != Material.AIR) {
            player.getInventory().setChestplate(null);
            changed = true;
        }
        if (player.getInventory().getLeggings() != null && player.getInventory().getLeggings().getType() != Material.AIR) {
            player.getInventory().setLeggings(null);
            changed = true;
        }
        if (player.getInventory().getBoots() != null && player.getInventory().getBoots().getType() != Material.AIR) {
            player.getInventory().setBoots(null);
            changed = true;
        }

        // Clear any leftover items in the player's main inventory (slots 9-35) beyond the
        // configured hotbar. THE ACTUAL BUG: this used to read player.getInventory().getContents(),
        // then loop `idx` up to contents.length and call setItem(idx, null) for anything non-air.
        // For a PlayerInventory specifically (unlike a generic Inventory), getContents() returns 41
        // slots, not 36 - it includes armor (36=boots, 37=leggings, 38=chestplate, 39=HELMET) and
        // offhand (40) in that same array, on top of the 36 main storage slots. So contents.length
        // was 41, and the loop happily walked straight through idx=39 and called
        // setItem(39, null) - which IS setHelmet(null) - on every single tick this ran (20x/second
        // for every arena/test-mode player). That's why grepping the whole codebase for
        // "setHelmet" never turned up a culprit: this was a generic setItem() call, not a setHelmet()
        // call, doing the exact same thing through the back door. getStorageContents() is the
        // PlayerInventory-specific method that explicitly returns ONLY the 36 main slots (excludes
        // armor and offhand by definition), which is what this loop actually needs.
        try {
            ItemStack[] storageContents = player.getInventory().getStorageContents();
            for (int idx = 9; idx < storageContents.length; idx++) {
                ItemStack it = storageContents[idx];
                if (it != null && it.getType() != Material.AIR) {
                    player.getInventory().setItem(idx, null);
                    changed = true;
                }
            }
        } catch (Throwable ignored) {
        }

        if (changed) {
            try {
                player.updateInventory();
            } catch (Throwable ignored) {
            }
        }
    }


    private boolean itemStacksMatch(ItemStack a, ItemStack b) {
        if (a == null || a.getType() == Material.AIR) {
            return b == null || b.getType() == Material.AIR;
        }
        if (b == null || b.getType() == Material.AIR) {
            return false;
        }
        return a.isSimilar(b) && a.getAmount() == b.getAmount();
    }

    private boolean isProtectedFastBuilderItem(ItemStack item, UUID playerUuid) {
        if (item == null || item.getType() == Material.AIR || playerUuid == null) {
            return false;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack expected = buildConfiguredHotbarItem(slot, playerUuid);
            if (expected != null && item.isSimilar(expected)) {
                return true;
            }
        }
        Material expectedHelmetMaterial = ((SkepiFBPlugin) plugin).getTimerManager().getExpectedRankHelmet(playerUuid);
        if (expectedHelmetMaterial != null) {
            if (item.getType() == expectedHelmetMaterial) {
                return true;
            }
        }
        return false;
    }

    private ItemStack createItemStack(HotbarItem hotbarItem, UUID playerUuid) {
        int amount = 1;
        Material material = hotbarItem.getMaterial();
        if (hotbarItem.getAction() == HotbarAction.BLOCK || hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK) {
            amount = 64;
            try {
                SkepiFBPlugin main = (SkepiFBPlugin) plugin;
                if (main.getShopManager() != null && playerUuid != null) {
                    if (hotbarItem.getAction() == HotbarAction.BLOCK) {
                        Material equipped = main.getShopManager().getEquippedBlockMaterial(playerUuid);
                        if (equipped != null) {
                            material = equipped;
                        }
                    } else {
                        Material equipped = main.getShopManager().getEquippedPracticeBlockMaterial(playerUuid);
                        if (equipped != null) {
                            material = equipped;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        } else if (hotbarItem.getAction() == HotbarAction.TOOL) {
            try {
                SkepiFBPlugin main = (SkepiFBPlugin) plugin;
                if (main.getShopManager() != null && playerUuid != null) {
                    Material equipped = main.getShopManager().getEquippedToolMaterial(playerUuid);
                    if (equipped != null) {
                        material = equipped;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', hotbarItem.getDisplayName()));
            if (hotbarItem.getAction() == HotbarAction.TOOL) {
                // Make tools unbreakable
                meta.setUnbreakable(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public HotbarItem getHotbarItem(int slot) {
        return hotbarItems.get(slot);
    }

    public ItemStack buildConfiguredHotbarItem(int slot) {
        return buildConfiguredHotbarItem(slot, null);
    }

    public ItemStack buildConfiguredHotbarItem(int slot, UUID playerUuid) {
        HotbarItem hotbarItem = hotbarItems.get(slot);
        if (hotbarItem == null) return null;
        if (hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK) {
            if (playerUuid == null) {
                return null;
            }
            SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
            if (!mainPlugin.getTimerManager().isPracticeMode(playerUuid)) {
                return null;
            }
        }
        return createItemStack(hotbarItem, playerUuid);
    }

    public HotbarItem getHotbarItemForStack(ItemStack item) {
        return findHotbarItem(item);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        // Close Replay hotbar handling when in replay mode
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        try {
            if (main.getTimerManager().isInReplay(player.getUniqueId())) {
                if (item.getType() == Material.BARRIER) {
                    String name = null;
                    if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) name = item.getItemMeta().getDisplayName();
                    if (ChatColor.stripColor(name == null ? "" : name).equals("Close Replay") ) {
                        event.setCancelled(true);
                        main.getTimerManager().closeReplay(player);
                        return;
                    }
                }
                boolean handled = main.getTimerManager().handleReplayControlClick(player, item);
                if (handled) {
                    event.setCancelled(true);
                    return;
                }
                event.setCancelled(true);
                return;
            }
        } catch (Throwable ignored) {}

        HotbarItem hotbarItem = findHotbarItem(item);
        if (hotbarItem == null) {
            return;
        }

        if (hotbarItem.getAction() == HotbarAction.NONE
                || hotbarItem.getAction() == HotbarAction.BLOCK
                || hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK
                || hotbarItem.getAction() == HotbarAction.TOOL) {
            // BLOCK, PRACTICE_BLOCK, and TOOL items should be placeable/breakable and are not hotbar actions.
            return;
        }

        event.setCancelled(true);
        executeHotbarAction(player, hotbarItem.getAction());
    }

    // Duplicate handlers removed: consolidated versions exist later with EventPriority.HIGH

    private HotbarItem findHotbarItem(ItemStack item) {
        // Find by display name match in configured items
        String itemDisplayName = null;
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            itemDisplayName = item.getItemMeta().getDisplayName();
        }

        for (HotbarItem hotbarItem : hotbarItems.values()) {
            String configName = ChatColor.translateAlternateColorCodes('&', hotbarItem.getDisplayName());
            if (itemDisplayName == null || !itemDisplayName.equals(configName)) {
                continue;
            }
            // BLOCK, PRACTICE_BLOCK, and TOOL items can change material from the configured base item,
            // so match by display name and action rather than requiring exact material equality.
            if (hotbarItem.getAction() == HotbarAction.BLOCK
                    || hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK
                    || hotbarItem.getAction() == HotbarAction.TOOL) {
                return hotbarItem;
            }
            if (item.getType() == hotbarItem.getMaterial()) {
                return hotbarItem;
            }
        }
        return null;
    }

    private void executeHotbarAction(Player player, HotbarAction action) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;

        switch (action) {
            case NONE:
                break;
            case RESPAWN:
                handleRespawn(player, mainPlugin);
                break;
            case ISLAND_MENU:
                handleIslandMenu(player);
                break;
            case REPLAYS_MENU:
                handleReplaysMenu(player);
                break;
            case SETTINGS_MENU:
                handleSettingsMenu(player);
                break;
            case LEAVE:
                openLeaveConfirmationMenu(player);
                break;
            case BLOCK:
            case PRACTICE_BLOCK:
            case TOOL:
                break;
        }
    }

    private void openLeaveConfirmationMenu(Player player) {
        if (player == null) {
            return;
        }
        String title = ChatColor.translateAlternateColorCodes('&', "&cConfirm Leave");
        Inventory menu = Bukkit.createInventory(new MenuInventoryHolder("leave_confirm"), 27, title);

        ItemStack yesItem = new ItemStack(Material.LIME_TERRACOTTA);
        ItemMeta yesMeta = yesItem.getItemMeta();
        if (yesMeta != null) {
            yesMeta.setDisplayName(ChatColor.GREEN + "Yes");
            yesMeta.setLore(java.util.List.of(ChatColor.GRAY + "Confirm leaving the arena."));
            yesMeta.getPersistentDataContainer().set(LEAVE_CONFIRM_KEY, PersistentDataType.STRING, "leave_confirm_yes");
            yesItem.setItemMeta(yesMeta);
        }
        menu.setItem(11, yesItem);

        ItemStack noItem = new ItemStack(Material.RED_TERRACOTTA);
        ItemMeta noMeta = noItem.getItemMeta();
        if (noMeta != null) {
            noMeta.setDisplayName(ChatColor.RED + "No");
            noMeta.setLore(java.util.List.of(ChatColor.GRAY + "Stay in the arena."));
            noMeta.getPersistentDataContainer().set(LEAVE_CONFIRM_KEY, PersistentDataType.STRING, "leave_confirm_no");
            noItem.setItemMeta(noMeta);
        }
        menu.setItem(15, noItem);

        player.openInventory(menu);
    }

    private void handleLeaveConfirmed(Player player, SkepiFBPlugin mainPlugin) {
        if (player == null) {
            return;
        }
        handleLeave(player, mainPlugin);
    }

    private String getLeaveConfirmAction(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String action = meta.getPersistentDataContainer().get(LEAVE_CONFIRM_KEY, PersistentDataType.STRING);
        if (action != null && !action.isBlank()) {
            return action;
        }
        String name = meta.hasDisplayName() ? ChatColor.stripColor(meta.getDisplayName()) : null;
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "yes" -> "leave_confirm_yes";
            case "no" -> "leave_confirm_no";
            default -> null;
        };
    }

    private void handleRespawn(Player player, SkepiFBPlugin mainPlugin) {
        AttemptSession session = mainPlugin.getTimerManager().getSession(player.getUniqueId());
        if (session != null) {
            mainPlugin.getTimerManager().saveFailedReplayIfApplicable(player, session);
            session.clearFinished();
            // play sequential removal animation then end attempt without cancelling active cleanup visuals
            if (!mainPlugin.getTimerManager().isCleanupActive(player.getUniqueId())) {
                mainPlugin.getTimerManager().playRemovalAnimation(session, player, "respawn", null);
            }
            session.endAttempt();
        }
        // Stop timer but keep the displayed final time
        mainPlugin.getTimerManager().pausePlayerSession(player.getUniqueId());
        // Teleport to resolved respawn destination (temporary custom spawn if set, otherwise island spawn)
        org.bukkit.Location dest = mainPlugin.getPlayerManager().getResolvedRespawnLocation(player.getUniqueId());
        if (dest != null) {
            try { player.teleport(dest); } catch (Throwable ignored) {}
        }
        mainPlugin.getInventoryManager().giveArenaBlock(player);
        mainPlugin.getHotbarManager().giveHotbarToPlayer(player);
    }

    private void handleIslandMenu(Player player) {
        openIslandMenu(player, 0);
    }

    public void openIslandMenu(Player player, int page) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        String arenaName = mainPlugin.getPlayerManager().getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            player.sendMessage(ChatColor.RED + "You are not in an arena.");
            return;
        }

        Arena arena = mainPlugin.getArenaManager().getArena(arenaName);
        if (arena == null) {
            player.sendMessage(ChatColor.RED + "That arena is unavailable.");
            return;
        }

        try {
            int pageCount = IslandMenuLayout.getPageCount(arena.getIslandCount());
            int normalizedPage = IslandMenuLayout.normalizePage(page, pageCount);
            Inventory menu = Bukkit.createInventory(new MenuInventoryHolder("island_menu"), configManager.getIslandMenuSize(), ChatColor.translateAlternateColorCodes('&', configManager.getIslandMenuTitle()) + " - Page " + (normalizedPage + 1));
            fillIslandMenu(menu, arena, normalizedPage, player);
            player.openInventory(menu);
        } catch (Throwable t) {
            // Previously an exception anywhere in here (see buildIslandItem's old setOwner bug)
            // meant player.openInventory(...) was simply never reached - the menu silently failed to
            // open with no feedback at all. Now it's caught, logged, and the player is told.
            plugin.getLogger().warning("[SkepiFB] Failed to open island menu for " + player.getName() + ": " + t);
            player.sendMessage(ChatColor.RED + "Could not open the island menu - please tell an admin to check console.");
        }
    }

    private void fillIslandMenu(Inventory menu, Arena arena, int page, Player viewer) {
        ConfigurationSection menuSection = configManager.getIslandMenuSection();
        if (menuSection == null) {
            return;
        }

        for (int slot = 0; slot < menu.getSize(); slot++) {
            ConfigurationSection itemSection = configManager.getMenuItemsBySlot("island_menu").get(slot);
            if (itemSection == null) {
                continue;
            }

            ItemStack slotItem;
            String action = itemSection.getString("action");
            if ("previous_page".equals(action)) {
                slotItem = createPreviousPageItem(itemSection, page);
            } else if ("next_page".equals(action)) {
                slotItem = createNextPageItem(itemSection, page, IslandMenuLayout.getPageCount(arena.getIslandCount()));
            } else {
                slotItem = createConfiguredMenuItem(itemSection);
            }
            menu.setItem(slot, slotItem);
        }

        List<Integer> islandSlots = configManager.getIslandMenuSlots();
        int startIndex = page * IslandMenuLayout.ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + IslandMenuLayout.ITEMS_PER_PAGE, arena.getIslandCount());
        int actualCount = endIndex - startIndex;
        for (int i = startIndex; i < endIndex; i++) {
            ArenaIsland island = arena.getIslands().get(i);
            int slotIndex = islandSlots.get(i - startIndex);
            menu.setItem(slotIndex, buildIslandItem(island, viewer));
        }
        for (int i = actualCount; i < islandSlots.size(); i++) {
            menu.setItem(islandSlots.get(i), createEmptyIslandSlot());
        }
        // Fill any remaining empty slots with gray panes for visual consistency
        for (int slot = 0; slot < menu.getSize(); slot++) {
            ItemStack existing = menu.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                menu.setItem(slot, createEmptyIslandSlot());
            }
        }
    }

    private ItemStack buildIslandItem(ArenaIsland island, Player viewer) {
        try {
            UUID occupiedPlayer = island.getOccupiedPlayer();
            if (occupiedPlayer != null) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.RED + "Island " + island.getIndex());
                    // THE ACTUAL BUG: this used to call the deprecated
                    // meta.setOwner(Bukkit.getOfflinePlayer(occupiedPlayer).getName()) - getName()
                    // returns null for any UUID the server has no cached name for (e.g. a player who
                    // hasn't been online in a while, or whose profile was never cached), and passing
                    // that null into setOwner(String) can throw. Since this runs before
                    // openIslandMenu ever reaches player.openInventory(...), that exception meant the
                    // island menu silently never opened at all whenever ANY island happened to be
                    // occupied by such a player. setOwningPlayer(OfflinePlayer) takes the UUID-backed
                    // OfflinePlayer directly, never needs to resolve a name, and is the current
                    // non-deprecated API for this.
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(occupiedPlayer));
                    meta.setLore(List.of(ChatColor.GRAY + "Occupied", ChatColor.YELLOW + "Click to switch"));
                    head.setItemMeta(meta);
                }
                return head;
            }

            ItemStack free = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
            ItemMeta freeMeta = free.getItemMeta();
            if (freeMeta != null) {
                freeMeta.setDisplayName(ChatColor.GREEN + "Island " + island.getIndex());
                freeMeta.setLore(List.of(ChatColor.GRAY + "Available", ChatColor.YELLOW + "Click to switch"));
                free.setItemMeta(freeMeta);
            }
            return free;
        } catch (Throwable t) {
            // Never let one bad island entry take down the whole menu (previously an exception here
            // meant openIslandMenu never reached player.openInventory(...) at all, so the menu just
            // silently failed to open). Fall back to a safe, generic item for this one slot instead.
            plugin.getLogger().warning("[SkepiFB] Failed to build island menu item for island "
                    + (island != null ? island.getIndex() : "?") + ": " + t);
            ItemStack fallback = new ItemStack(Material.STONE);
            ItemMeta fallbackMeta = fallback.getItemMeta();
            if (fallbackMeta != null) {
                fallbackMeta.setDisplayName(ChatColor.YELLOW + "Island " + (island != null ? island.getIndex() : "?"));
                fallback.setItemMeta(fallbackMeta);
            }
            return fallback;
        }
    }

    private ItemStack createConfiguredMenuItem(ConfigurationSection itemSection) {
        if (itemSection == null) {
            return new ItemStack(Material.STONE);
        }

        String materialName = itemSection.getString("material", null);
        if (materialName == null) {
            materialName = itemSection.getString("item", "STONE");
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.STONE;
        }
        int amount = Math.max(1, itemSection.getInt("amount", 1));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = itemSection.getString("name", "");
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            }
            List<String> lore = itemSection.isList("lore")
                    ? itemSection.getStringList("lore")
                    : (itemSection.getString("lore") == null || itemSection.getString("lore").isBlank()
                        ? List.of()
                        : List.of(itemSection.getString("lore")));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).toList());
            }
            // Apply leather color if requested
            if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta) {
                org.bukkit.inventory.meta.LeatherArmorMeta lam = (org.bukkit.inventory.meta.LeatherArmorMeta) meta;
                String colorName = itemSection.getString("color", null);
                if (colorName != null && !colorName.isBlank()) {
                    org.bukkit.Color dye = null;
                    switch (colorName.toUpperCase(Locale.ROOT)) {
                        case "RED" -> dye = org.bukkit.Color.RED;
                        case "BLUE" -> dye = org.bukkit.Color.BLUE;
                        case "GREEN" -> dye = org.bukkit.Color.GREEN;
                        case "WHITE" -> dye = org.bukkit.Color.WHITE;
                        case "BLACK" -> dye = org.bukkit.Color.BLACK;
                        default -> {
                            try {
                                // support hex like #FF0000 or FF0000
                                String hex = colorName.startsWith("#") ? colorName.substring(1) : colorName;
                                int rgb = Integer.parseInt(hex, 16);
                                int r = (rgb >> 16) & 0xFF;
                                int g = (rgb >> 8) & 0xFF;
                                int b = rgb & 0xFF;
                                dye = org.bukkit.Color.fromRGB(r, g, b);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    if (dye != null) {
                        lam.setColor(dye);
                        item.setItemMeta(lam);
                        return item;
                    }
                }
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPreviousPageItem(ConfigurationSection itemSection, int page) {
        ItemStack item = createConfiguredMenuItem(itemSection);
        if (page <= 0) {
            item.setType(Material.matchMaterial(itemSection.getString("alt-material", "STICK")) == null ? Material.STICK : Material.matchMaterial(itemSection.getString("alt-material", "STICK")));
        }
        return item;
    }

    private ItemStack createEmptyIslandSlot() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createNextPageItem(ConfigurationSection itemSection, int page, int pageCount) {
        ItemStack item = createConfiguredMenuItem(itemSection);
        if (page >= pageCount - 1) {
            item.setType(Material.matchMaterial(itemSection.getString("alt-material", "STICK")) == null ? Material.STICK : Material.matchMaterial(itemSection.getString("alt-material", "STICK")));
        }
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        if (main.getTimerManager().isInReplay(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        boolean isFastBuilderPlayer = main.getPlayerManager().isInArena(player.getUniqueId()) || main.getPlayerManager().isInTestMode(player.getUniqueId());

        if (event.getView().getTopInventory() == null || event.getClickedInventory() == null) {
            return;
        }

        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof MenuInventoryHolder menuHolder) {
            String menuKey = menuHolder.getMenuKey();
            if (menuKey == null) {
                return;
            }

            String title = ChatColor.stripColor(event.getView().getTitle());

            int rawSlot = event.getRawSlot();
            int lookupSlot = rawSlot;
            if (event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
            }

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }

            if ("replay_menu".equals(menuKey)) {
                if (rawSlot == FAILED_REPLAY_SLOT) {
                    handleFailedReplayClick(player);
                    return;
                }
                int page = replayMenuPages.getOrDefault(player.getUniqueId(), 0);
                UUID replayOwnerUuid = replayMenuOwners.getOrDefault(player.getUniqueId(), player.getUniqueId());
                String arenaName = replayMenuArenas.getOrDefault(player.getUniqueId(), main.getPlayerManager().getPlayerArena(player.getUniqueId()));
                if (rawSlot == REPLAY_PREVIOUS_PAGE_SLOT) {
                    openReplayMetadataMenu(player, replayOwnerUuid, arenaName, Math.max(0, page - 1));
                    return;
                }
                if (rawSlot == REPLAY_NEXT_PAGE_SLOT) {
                    openReplayMetadataMenu(player, replayOwnerUuid, arenaName, page + 1);
                    return;
                }
                // Reserved shortcut slots: just open the referenced replay, no other click behavior.
                if (rawSlot == REPLAY_PB_SLOT) {
                    if (arenaName != null) {
                        main.getReplayManager().getCurrentPersonalBestReplay(replayOwnerUuid, arenaName)
                                .ifPresent(metadata -> playReplay(player, metadata));
                    }
                    return;
                }
                if (rawSlot == REPLAY_FAVORITE_SLOT) {
                    if (arenaName != null) {
                        main.getReplayManager().getFavoriteReplay(replayOwnerUuid, arenaName)
                                .ifPresent(metadata -> playReplay(player, metadata));
                    }
                    return;
                }
                if (rawSlot == REPLAY_SORT_SLOT) {
                    handleReplaySortClick(player, event.isRightClick(), replayOwnerUuid, arenaName, page);
                    return;
                }
                int replayPosition = resolveReplaySlotIndex(rawSlot, page);
                if (replayPosition >= 0) {
                    if (event.isRightClick() && arenaName != null) {
                        handleReplayFavoriteToggle(player, replayOwnerUuid, arenaName, replayPosition, page);
                    } else {
                        handleReplayClick(player, replayPosition);
                    }
                    return;
                }
            }

            // Island menu: island entries are placed dynamically (buildIslandItem) and never have a
            // configured "action" string in config.yml, so they must be resolved from the clicked slot
            // + current page instead of going through the generic config-action lookup below.
            if ("island_menu".equals(menuKey)) {
                String arenaName = main.getPlayerManager().getPlayerArena(player.getUniqueId());
                Arena arenaContext = arenaName != null ? main.getArenaManager().getArena(arenaName) : null;
                int currentPage = Math.max(0, parsePage(event.getView().getTitle()));
                int islandIndex = resolveIslandIndexFromSlot(rawSlot, currentPage, arenaContext);
                if (islandIndex > 0) {
                    switchIsland(player, islandIndex);
                    return;
                }
            }

            String action = configManager.getMenuActionExact(menuKey, lookupSlot);
            if (action == null && "leave_confirm".equals(menuKey)) {
                action = getLeaveConfirmAction(clickedItem);
            }

            // Special handling for spawn position clicks in fastbuilder settings menu
            if ("spawn_position".equals(action)) {
                SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
                UUID pu = player.getUniqueId();
                AttemptSession session = mainPlugin.getTimerManager().getSession(pu);
                if (event.isLeftClick()) {
                    // Set temporary spawn only when not in an active attempt
                    if (session != null && session.isRunning()) {
                        String msg = ChatColor.translateAlternateColorCodes('&', main.getConfigManager().getConfiguration().getString("spawn-position.cannot-set-while-running", "&cCannot set spawn while an attempt is active."));
                        player.sendMessage(msg);
                        return;
                    }
                    main.getPlayerManager().setTemporarySpawn(pu, player.getLocation());
                    String msg = ChatColor.translateAlternateColorCodes('&', main.getConfigManager().getConfiguration().getString("spawn-position.set-success", "&aTemporary spawn set."));
                    player.sendMessage(msg);
                } else if (event.isRightClick()) {
                    main.getPlayerManager().clearTemporarySpawn(player.getUniqueId());
                    String msg = ChatColor.translateAlternateColorCodes('&', main.getConfigManager().getConfiguration().getString("spawn-position.reset-success", "&aTemporary spawn cleared."));
                    player.sendMessage(msg);
                }
                return;
            }

            if (action != null) {
                handleMenuAction(player, menuKey, action, lookupSlot);
                return;
            }

            return;
        }

        // Only enforce the protected FastBuilder survival inventory in Survival mode. Creative mode
        // (e.g. staff testing/building) must never be treated as protected FastBuilder inventory.
        if (isFastBuilderPlayer && player.getGameMode() == org.bukkit.GameMode.SURVIVAL
                && event.getClickedInventory() == player.getInventory()) {
            if (shouldCancelProtectedInventoryClick(event)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        UUID playerUuid = player.getUniqueId();
        if (main.getTimerManager().isInReplay(playerUuid)) {
            event.setCancelled(true);
            return;
        }
        if (!main.getPlayerManager().isInArena(playerUuid) && !main.getPlayerManager().isInTestMode(playerUuid)) {
            return;
        }
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            return;
        }
        if (isProtectedFastBuilderItem(player.getInventory().getItemInMainHand(), playerUuid)
                || isProtectedFastBuilderItem(player.getInventory().getItemInOffHand(), playerUuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        // Allow normal hotbar slot switching; do not cancel held-slot changes.
    }

    private boolean shouldCancelProtectedInventoryClick(InventoryClickEvent event) {
        if (event == null) return false;

        // Only evaluate player inventories
        if (event.getClickedInventory() == null || event.getClickedInventory().getType() != InventoryType.PLAYER) {
            return false;
        }

        // Get involved item stacks
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // Who is interacting
        if (!(event.getWhoClicked() instanceof Player player)) return false;

        // NOTE: helmet/armor protection below is deliberately content-based (what item is
        // actually being moved) rather than slot-number-based. A fixed "raw slot" number does not
        // reliably identify the helmet slot: the raw slot for armor differs depending on which
        // inventory view is open (the player's own inventory screen numbers armor differently than
        // a chest/GUI view with the player's inventory as the bottom half), so a hardcoded slot
        // check silently fails to protect the helmet in some contexts while doing nothing useful in
        // others. Checking the actual item content works correctly in every context.
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        UUID playerUuid = player.getUniqueId();

        // Predicate: whether an ItemStack matches a configured protected hotbar item or helmet
        java.util.function.Predicate<ItemStack> isProtectedItem = item -> {
            if (item == null || item.getType() == Material.AIR) return false;
            try {
                // Check against configured hotbar items
                for (int s = 0; s < 9; s++) {
                    ItemStack expected = buildConfiguredHotbarItem(s, playerUuid);
                    if (expected != null && item.isSimilar(expected)) return true;
                }
                // Helmet enforcement: protect the configured rankup helmet only
                Material expectedHelmetMaterial = main.getTimerManager().getExpectedRankHelmet(playerUuid);
                if (expectedHelmetMaterial != null) {
                    if (item.getType() == expectedHelmetMaterial) return true;
                }
            } catch (Throwable ignored) {}
            return false;
        };

        // Shift-click moves: cancel only if the moving item is protected
        if (event.isShiftClick()) {
            return isProtectedItem.test(current) || isProtectedItem.test(cursor);
        }

        ClickType click = event.getClick();

        // Number-key swap with hotbar button
        if (click == ClickType.NUMBER_KEY) {
            int hotbarButton = event.getHotbarButton();
            ItemStack targetHotbar = player.getInventory().getItem(hotbarButton);
            return isProtectedItem.test(targetHotbar) || isProtectedItem.test(current) || isProtectedItem.test(cursor);
        }

        // Swap offhand
        if (click == ClickType.SWAP_OFFHAND) {
            ItemStack off = player.getInventory().getItemInOffHand();
            return isProtectedItem.test(off) || isProtectedItem.test(current) || isProtectedItem.test(cursor);
        }

        // Other actions that place/pickup: check items involved
        org.bukkit.event.inventory.InventoryAction action = event.getAction();
        switch (action) {
            case PLACE_ONE:
            case PLACE_SOME:
            case PLACE_ALL:
            case PICKUP_ONE:
            case PICKUP_SOME:
            case PICKUP_ALL:
            case COLLECT_TO_CURSOR:
            case DROP_ALL_CURSOR:
            case DROP_ONE_CURSOR:
            case DROP_ALL_SLOT:
            case DROP_ONE_SLOT:
            case HOTBAR_SWAP:
            case HOTBAR_MOVE_AND_READD:
                return isProtectedItem.test(current) || isProtectedItem.test(cursor);
            case SWAP_WITH_CURSOR:
                // Clicking directly on an occupied slot (e.g. the worn helmet) while holding a
                // different item in hand. This is the primary way a player swaps armor by clicking
                // straight on the armor slot, and it was previously unhandled here - allowing the
                // rankup helmet to be swapped out silently despite every other click type being
                // protected.
                return isProtectedItem.test(current) || isProtectedItem.test(cursor);
            default:
                return false;
        }
    }

    private boolean isProtectedHotbarSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot <= 8;
    }

    /**
     * Best-effort armor-slot detection for raw slots, covering both numbering schemes seen in
     * practice: 36-39 (PlayerInventory's own getItem()-style indexing, also used as raw slots when
     * the player's inventory is the bottom half of some GUI contexts) and 5-8 (the raw slot layout
     * used by the player's own inventory screen opened via the default 'E' key, where 5=helmet,
     * 6=chestplate, 7=leggings, 8=boots). This is intentionally a superset/heuristic used only to
     * decide whether to run the (accurate) content-based protection check below - it is never the
     * sole reason an interaction is blocked.
     */
    private boolean isLikelyArmorRawSlot(int rawSlot) {
        return (rawSlot >= 36 && rawSlot <= 40) || (rawSlot >= 5 && rawSlot <= 8);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());
        if (configManager.isFastBuilderMenuTitle(title)) {
            int topSize = event.getView().getTopInventory().getSize();
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < topSize) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        if (main.getTimerManager().isInReplay(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!main.getPlayerManager().isInArena(player.getUniqueId()) && !main.getPlayerManager().isInTestMode(player.getUniqueId())) {
            return;
        }
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            return;
        }

        ItemStack cursor = event.getCursor();
        UUID playerUuid = player.getUniqueId();
        for (int rawSlot : event.getRawSlots()) {
            if (isProtectedHotbarSlot(rawSlot) || isLikelyArmorRawSlot(rawSlot)) {
                if (isProtectedFastBuilderItem(cursor, playerUuid)) {
                    event.setCancelled(true);
                }
                return;
            }
        }
    }

    private void handleMenuAction(Player player, String menuKey, String action, int slot) {
        if (action != null && action.startsWith("shop:")) {
            String shopId = action.substring("shop:".length()).trim();
            if (!shopId.isBlank()) {
                ((SkepiFBPlugin) plugin).getShopManager().openShopMenu(player, shopId);
            }
            return;
        }

        // Global "none" button: any item in ANY menu.yml menu can be turned into a "clear this
        // cosmetic" button just by setting its action to cosmetic_none:<shopId>:<categoryKey>
        // (categoryKey is optional when the shop only has one category). This reuses the exact
        // same equip pipeline as clicking a "none" item inside a shop.yml category (purchaseOrEquip
        // -> equipItem, which already treats the literal item key "none" as "clear the equipped
        // cosmetic for this category" for every shop/category, not just reset_animation/
        // firework_color) - so it also works automatically for a "none" item defined directly in
        // shop.yml. This action is just a menu.yml-side entry point into that same mechanism.
        if (action != null && action.startsWith("cosmetic_none:")) {
            String remainder = action.substring("cosmetic_none:".length()).trim();
            if (!remainder.isBlank()) {
                SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
                String shopId;
                String categoryKey;
                int separator = remainder.indexOf(':');
                if (separator >= 0) {
                    shopId = remainder.substring(0, separator).trim();
                    categoryKey = remainder.substring(separator + 1).trim();
                } else {
                    shopId = remainder;
                    categoryKey = mainPlugin.getShopManager().getShopDefinition(shopId)
                            .map(shop -> shop.getCategories().isEmpty() ? null : shop.getCategories().get(0).getKey())
                            .orElse(null);
                }
                if (shopId != null && !shopId.isBlank() && categoryKey != null && !categoryKey.isBlank()) {
                    mainPlugin.getShopManager().purchaseOrEquip(player, shopId + ":" + categoryKey + ":none");
                }
            }
            return;
        }

        switch (action) {
            case "close_menu":
            case "close_gui":
                player.closeInventory();
                break;
            case "previous_page":
                if ("island_menu".equals(menuKey)) {
                    openIslandMenu(player, parsePage(player.getOpenInventory().getTitle()) - 1);
                }
                break;
            case "next_page":
                if ("island_menu".equals(menuKey)) {
                    openIslandMenu(player, parsePage(player.getOpenInventory().getTitle()) + 1);
                }
                break;
            case "mode_changer_menu":
            case "fastbuilder_settings_menu":
            case "cosmetics_menu":
                openBlankSubmenu(player, action);
                break;
            case "toggle_practice_mode": {
                SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
                boolean enabled = mainPlugin.getTimerManager().togglePracticeMode(player.getUniqueId());
                player.sendMessage(enabled ? ChatColor.GREEN + "Practice Mode enabled." : ChatColor.YELLOW + "Practice Mode disabled.");
                break;
            }
            case "stat_reset_confirm": {
                handleStatisticResetConfirm(player);
                break;
            }
            case "stat_reset_cancel": {
                player.closeInventory();
                break;
            }
            case "leave_confirm_yes": {
                player.closeInventory();
                handleLeaveConfirmed(player, (SkepiFBPlugin) plugin);
                break;
            }
            case "leave_confirm_no": {
                player.closeInventory();
                break;
            }
            case "menu": {
                ConfigurationSection itemSection = configManager.getMenuItemSection(menuKey, slot);
                if (itemSection != null) {
                    String targetMenu = itemSection.getString("menu");
                    if (targetMenu != null && configManager.menuExists(targetMenu)) {
                        // If entering statistic reset menu, check coins first
                        if ("stat_reset".equalsIgnoreCase(targetMenu)) {
                            SkepiFBPlugin main = (SkepiFBPlugin) plugin;
                            int coins = main.getStatsManager().getCoins(player.getUniqueId());
                            int cost = main.getConfigManager().getConfiguration().getInt("statistic-reset.cost", 75);
                            if (coins < cost) {
                                String msg = main.getConfigManager().getConfiguration().getString("statistic-reset.insufficient-coins", "&cYou need %cost% coins to reset your stats.");
                                msg = msg.replace("%cost%", String.valueOf(cost));
                                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                                return;
                            }
                        }
                        openBlankSubmenu(player, targetMenu);
                    } else {
                        player.sendMessage(ChatColor.RED + "That menu does not exist.");
                    }
                }
                break;
            }
            case "mode": {
                ConfigurationSection itemSection = configManager.getMenuItemSection(menuKey, slot);
                if (itemSection != null) {
                    String modeName = itemSection.getString("mode");
                    if (modeName != null && !modeName.isBlank()) {
                        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
                        SkepiFBPlugin.ArenaJoinResult result = mainPlugin.joinPlayerToArena(player, modeName);
                        if (result != SkepiFBPlugin.ArenaJoinResult.SUCCESS) {
                            switch (result) {
                                case ALREADY_IN_ARENA -> player.sendMessage(ChatColor.RED + "You are already in an arena.");
                                case ARENA_NOT_FOUND -> player.sendMessage(ChatColor.RED + "That arena does not exist.");
                                case NO_ISLANDS_AVAILABLE -> player.sendMessage(ChatColor.YELLOW + "No islands are currently available.");
                                case FAILED -> player.sendMessage(ChatColor.RED + "Unable to join that mode right now.");
                                default -> {}
                            }
                        }
                    }
                }
                break;
            }
            default:
                if (action.startsWith("island_")) {
                    int islandIndex = parseIslandIndex(action);
                    if (islandIndex > 0) {
                        switchIsland(player, islandIndex);
                    }
                }
                break;
        }
    }

    private int parsePage(String title) {
        if (title == null) {
            return 0;
        }
        String stripped = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', title)).trim();
        try {
            String pagePart = stripped.substring(stripped.lastIndexOf(' ') + 1);
            return Integer.parseInt(pagePart) - 1;
        } catch (Exception ex) {
            return 0;
        }
    }

    private int parseIslandIndex(String action) {
        try {
            return Integer.parseInt(action.substring("island_".length()));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private int resolveIslandIndexFromSlot(int slot, int page, Arena arena) {
        List<Integer> slots = configManager.getIslandMenuSlots();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) == slot) {
                int index = page * IslandMenuLayout.ITEMS_PER_PAGE + i + 1;
                if (arena == null || index > arena.getIslandCount()) {
                    return -1;
                }
                return index;
            }
        }
        return -1;
    }

    private void handleFailedReplayClick(Player player) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        UUID replayOwnerUuid = replayMenuOwners.getOrDefault(player.getUniqueId(), player.getUniqueId());
        String arenaName = replayMenuArenas.getOrDefault(player.getUniqueId(), mainPlugin.getPlayerManager().getPlayerArena(player.getUniqueId()));
        if (arenaName == null) {
            return;
        }
        mainPlugin.getReplayManager().getFailedReplayMetadata(replayOwnerUuid, arenaName).ifPresent(metadata -> {
            player.closeInventory();
            mainPlugin.getTimerManager().startReplay(player, metadata);
        });
    }

    private void handleStatisticResetConfirm(Player player) {
        if (player == null) return;
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        UUID pu = player.getUniqueId();
        if (statResetInProgress.contains(pu)) return; // already processing
        String arenaName = main.getPlayerManager().getPlayerArena(pu);
        if (arenaName == null) {
            player.sendMessage(ChatColor.RED + "You are not in an arena.");
            return;
        }

        int cost = main.getConfigManager().getConfiguration().getInt("statistic-reset.cost", 75);
        // Deduct coins (we already checked affordability when opening menu)
        statResetInProgress.add(pu);
        try {
            main.getStatsManager().addCoins(pu, -cost);
            // Reset only for this arena
            main.getStatsManager().resetStatsForArena(pu, arenaName);

            // Remove any runtime session top entries for this player in the arena
            try { main.getSessionTopManager().removePlayerFromArena(pu, arenaName); } catch (Throwable ignored) {}

            // Refresh statboards and scoreboards for players in the same arena
            try { main.getStatboardManager().updateAll(); } catch (Throwable ignored) {}
            try {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (arenaName.equals(main.getPlayerManager().getPlayerArena(online.getUniqueId()))) {
                        try { main.getScoreboardManager().showScoreboard(online); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "Your statistics for this arena have been reset.");
        } finally {
            statResetInProgress.remove(pu);
        }
    }

    private void switchIsland(Player player, int islandIndex) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        String arenaName = mainPlugin.getPlayerManager().getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            return;
        }

        Arena arena = mainPlugin.getArenaManager().getArena(arenaName);
        if (arena == null) {
            return;
        }

        ArenaIsland targetIsland = arena.getIslands().stream()
                .filter(island -> island.getIndex() == islandIndex)
                .findFirst()
                .orElse(null);
        if (targetIsland == null) {
            return;
        }

        if (targetIsland.getOccupiedPlayer() != null && !targetIsland.getOccupiedPlayer().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "That island is already occupied.");
            return;
        }

        if (arena.findIslandByPlayer(player.getUniqueId()).isPresent()) {
            ArenaIsland currentIsland = arena.findIslandByPlayer(player.getUniqueId()).get();
            if (currentIsland.getIndex() == targetIsland.getIndex()) {
                player.sendMessage(ChatColor.YELLOW + "You are already on that island.");
                return;
            }
        }

        ArenaIsland previousIsland = arena.findIslandByPlayer(player.getUniqueId()).orElse(null);
        if (previousIsland != null) {
            previousIsland.setOccupiedPlayer(null);
            try {
                mainPlugin.getArenaManager().restoreIsland(arena, previousIsland);
            } catch (Throwable ignored) {}
            try { mainPlugin.getStatboardManager().removeStatboard(player.getUniqueId()); } catch (Throwable ignored) {}
        }

        // Clear player's temporary placed blocks and reset their session
        mainPlugin.getTimerManager().resetPlayerSession(player.getUniqueId(), player.getWorld());

        // Rebuild the player's hotbar and arena block
        mainPlugin.getInventoryManager().giveArenaBlock(player);
        mainPlugin.getHotbarManager().giveHotbarToPlayer(player);

        // Ensure the destination island is reset by pasting its schematic before teleporting.
        // Use the player's UUID so their selected island cosmetic is applied.
        try {
            mainPlugin.getArenaManager().pasteIsland(arena, targetIsland, player.getUniqueId());
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to paste schematic for island " + targetIsland.getIndex() + ": " + t.getMessage());
        }

        targetIsland.setOccupiedPlayer(player.getUniqueId());
        mainPlugin.getArenaManager().saveArenas();
        mainPlugin.getPlayerManager().trackPlayer(player.getUniqueId(), arena.getName(), targetIsland.getIndex());

        Location teleportLocation = new Location(player.getWorld(), targetIsland.getSpawnLocation().getX(), targetIsland.getSpawnLocation().getY(), targetIsland.getSpawnLocation().getZ(), targetIsland.getSpawnLocation().getYaw(), targetIsland.getSpawnLocation().getPitch());
        boolean teleported = player.teleport(teleportLocation);
        if (teleported) {
            try { mainPlugin.getStatboardManager().createStatboard(player.getUniqueId(), arena, targetIsland); } catch (Throwable ignored) {}
        }
        player.sendMessage(ChatColor.GREEN + "You switched to island " + targetIsland.getIndex() + ".");
    }

    private void handleReplaysMenu(Player player) {
        openReplayMetadataMenu(player);
    }

    private void openReplayMetadataMenu(Player player) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        String arenaName = mainPlugin.getPlayerManager().getPlayerArena(player.getUniqueId());
        openReplayMetadataMenu(player, player.getUniqueId(), arenaName, 0);
    }

    public void openReplayMenuForPlayer(Player player, UUID replayOwnerUuid, String arenaName) {
        openReplayMetadataMenu(player, replayOwnerUuid, arenaName, 0);
    }

    private void openReplayMetadataMenu(Player player, UUID replayOwnerUuid, String arenaName, int page) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        if (arenaName == null) {
            arenaName = mainPlugin.getPlayerManager().getPlayerArena(player.getUniqueId());
        }
        String title = "&6Replays";
        int size = 54;
        ConfigurationSection menuSection = configManager.getMenuSection("replay_menu");
        if (menuSection != null) {
            title = menuSection.getString("title", title);
            size = menuSection.getInt("size", size);
        }

        List<ReplayMetadata> replays = new ArrayList<>(mainPlugin.getReplayManager().listReplayMetadata(replayOwnerUuid == null ? player.getUniqueId() : replayOwnerUuid, arenaName));
        Collections.reverse(replays);
        List<Integer> pageSlots = getReplayInteriorSlots(size);
        int pageCount = Math.max(1, (int) Math.ceil(replays.size() / (double) pageSlots.size()));
        int normalizedPage = Math.max(0, Math.min(page, pageCount - 1));

        replayMenuOwners.put(player.getUniqueId(), replayOwnerUuid == null ? player.getUniqueId() : replayOwnerUuid);
        replayMenuPages.put(player.getUniqueId(), normalizedPage);
        replayMenuArenas.put(player.getUniqueId(), arenaName);

        Inventory menu = Bukkit.createInventory(new MenuInventoryHolder("replay_menu"), size, ChatColor.translateAlternateColorCodes('&', title + " - Page " + (normalizedPage + 1)));
        fillReplayMenu(menu, player, replayOwnerUuid == null ? player.getUniqueId() : replayOwnerUuid, arenaName, normalizedPage);
        player.openInventory(menu);
    }

    private void fillReplayMenu(Inventory menu, Player player, UUID replayOwnerUuid, String arenaName, int page) {
        for (int slot = 0; slot < menu.getSize(); slot++) {
            if (isBorderSlot(slot, menu.getSize())) {
                ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                ItemMeta meta = filler.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(" ");
                    filler.setItemMeta(meta);
                }
                menu.setItem(slot, filler);
            }
        }

        ItemStack header = new ItemStack(Material.BOOK);
        ItemMeta headerMeta = header.getItemMeta();
        if (headerMeta != null) {
            headerMeta.setDisplayName(ChatColor.GOLD + "Replay Library");
            headerMeta.setLore(List.of(
                    ChatColor.GRAY + (arenaName != null ? "Arena: " + arenaName : "No arena selected"),
                    ChatColor.GRAY + "Browse your latest successful runs"
            ));
            header.setItemMeta(headerMeta);
        }
        if (menu.getSize() > 4) {
            menu.setItem(4, header);
        }

        if (arenaName != null) {
            SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
            Optional<ReplayMetadata> failedReplay = mainPlugin.getReplayManager().getFailedReplayMetadata(replayOwnerUuid, arenaName);
            ItemStack failedItem = createConfiguredMenuItem(configManager.getMenuItemSection("replay_menu", FAILED_REPLAY_SLOT));
            if (failedItem.getType() == Material.STONE) {
                failedItem = new ItemStack(Material.FILLED_MAP);
            }
            ItemMeta failedMeta = failedItem.getItemMeta();
            if (failedMeta != null) {
                if (failedMeta.getDisplayName() == null || failedMeta.getDisplayName().isBlank()) {
                    failedMeta.setDisplayName(ChatColor.GOLD + "Last Failed Attempt");
                }
                if (failedReplay.isPresent()) {
                    failedMeta.setLore(List.of(
                            ChatColor.GRAY + "Click to replay your last failed attempt",
                            ChatColor.GRAY + "Time: " + String.format(Locale.ROOT, "%.3f", failedReplay.get().getDurationSeconds()) + "s",
                            ChatColor.GRAY + "Blocks: " + failedReplay.get().getBlocksPlaced()
                    ));
                } else {
                    failedMeta.setLore(List.of(ChatColor.GRAY + "No failed attempt available"));
                }
                failedItem.setItemMeta(failedMeta);
            }
            menu.setItem(FAILED_REPLAY_SLOT, failedItem);

            List<ReplayMetadata> replays = getDisplayedReplays(player, replayOwnerUuid, arenaName);
            int sortOrder = replayMenuSortOrder.getOrDefault(player.getUniqueId(), 0);

            List<Integer> pageSlots = getReplayInteriorSlots(menu.getSize());
            int pageCount = Math.max(1, (int) Math.ceil(replays.size() / (double) pageSlots.size()));
            int normalizedPage = Math.min(page, Math.max(0, pageCount - 1));
            int startIndex = normalizedPage * pageSlots.size();
            if (page != normalizedPage) {
                replayMenuPages.put(player.getUniqueId(), normalizedPage);
            }

            if (normalizedPage > 0) {
                ItemStack previousPageItem = new ItemStack(Material.ARROW);
                ItemMeta previousMeta = previousPageItem.getItemMeta();
                if (previousMeta != null) {
                    previousMeta.setDisplayName(ChatColor.YELLOW + "Previous Page");
                    previousPageItem.setItemMeta(previousMeta);
                }
                menu.setItem(REPLAY_PREVIOUS_PAGE_SLOT, previousPageItem);
            } else {
                menu.setItem(REPLAY_PREVIOUS_PAGE_SLOT, createDisabledPageItem(ChatColor.GRAY + "Previous Page"));
            }
            if (normalizedPage < pageCount - 1) {
                ItemStack nextPageItem = new ItemStack(Material.ARROW);
                ItemMeta nextMeta = nextPageItem.getItemMeta();
                if (nextMeta != null) {
                    nextMeta.setDisplayName(ChatColor.YELLOW + "Next Page");
                    nextPageItem.setItemMeta(nextMeta);
                }
                menu.setItem(REPLAY_NEXT_PAGE_SLOT, nextPageItem);
            } else {
                menu.setItem(REPLAY_NEXT_PAGE_SLOT, createDisabledPageItem(ChatColor.GRAY + "Next Page"));
            }

            UUID favoriteReplayId = mainPlugin.getReplayManager().getFavoriteReplay(replayOwnerUuid, arenaName)
                    .map(ReplayMetadata::getReplayId).orElse(null);

            for (int i = 0; i < pageSlots.size() && startIndex + i < replays.size(); i++) {
                ReplayMetadata metadata = replays.get(startIndex + i);
                double personalBest = mainPlugin.getStatsManager().getPersonalBest(replayOwnerUuid, arenaName);
                // "Current PB" = this replay's time matches the player's live best right now.
                // "Was a PB" = this replay was recorded as a personal best at the time it finished
                // (persisted on the replay itself, so it doesn't change retroactively when a newer,
                // faster run supersedes it - only the *current* PB replay stays enchanted).
                boolean isCurrentPersonalBest = personalBest > 0 && Math.abs(metadata.getDurationSeconds() - personalBest) < 0.0005;
                boolean isFavorite = metadata.getReplayId().equals(favoriteReplayId);
                ItemStack replayItem = buildReplayListItem(metadata, isCurrentPersonalBest, isFavorite);
                menu.setItem(pageSlots.get(i), replayItem);
            }

            // Reserved shortcut slots (18 = PB Replay, 19 = Favorite Replay). These are placed after
            // the normal replay list/pagination above so they can never be overwritten by it, and
            // they are excluded from REPLAY_MENU_SLOTS/pagination entirely so normal replay entries
            // never render on top of them either.
            menu.setItem(REPLAY_PB_SLOT, buildPersonalBestShortcutItem(mainPlugin, replayOwnerUuid, arenaName));
            menu.setItem(REPLAY_FAVORITE_SLOT, buildFavoriteShortcutItem(mainPlugin, replayOwnerUuid, arenaName));
            menu.setItem(REPLAY_SORT_SLOT, buildReplaySortItem(sortOrder));
        }
    }

    /**
     * Sorts the given replay list in place according to the selected sort index:
     * 0 = Most Recent (newest replayIndex first), 1 = Best Times (fastest duration first),
     * 2 = Worst Times (slowest duration first), 3 = Oldest (lowest replayIndex first).
     * listReplayMetadata already returns replays sorted by replayIndex ascending (oldest first), so
     * "Oldest" needs no reordering and "Most Recent" is simply that order reversed.
     */
    private void applyReplaySortOrder(List<ReplayMetadata> replays, int sortOrder) {
        switch (sortOrder) {
            case 1 -> replays.sort(Comparator.comparingDouble(ReplayMetadata::getDurationSeconds));
            case 2 -> replays.sort(Comparator.comparingDouble(ReplayMetadata::getDurationSeconds).reversed());
            case 3 -> replays.sort(Comparator.comparingInt(ReplayMetadata::getReplayIndex));
            default -> replays.sort(Comparator.comparingInt(ReplayMetadata::getReplayIndex).reversed());
        }
    }

    private ItemStack buildReplaySortItem(int sortOrder) {
        // Defensive clamp: an out-of-range value here should never actually crash the menu, it
        // should just fall back to the default sort rather than throwing.
        int safeSortOrder = (sortOrder < 0 || sortOrder >= REPLAY_SORT_LABELS.length) ? 0 : sortOrder;
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Sort By:");
            List<String> lore = new ArrayList<>();
            for (int i = 0; i < REPLAY_SORT_LABELS.length; i++) {
                String label = REPLAY_SORT_LABELS[i];
                lore.add(i == safeSortOrder
                        ? ChatColor.GREEN + "\u25B6 " + label
                        : ChatColor.GRAY + label);
            }
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Left-click: next  |  Right-click: previous");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildReplayListItem(ReplayMetadata metadata, boolean isCurrentPersonalBest, boolean isFavorite) {
        boolean wasPersonalBest = metadata.isWasPersonalBest();
        ItemStack replayItem = new ItemStack(wasPersonalBest ? Material.DIAMOND_BLOCK : Material.IRON_BLOCK);
        ItemMeta replayMeta = replayItem.getItemMeta();
        if (replayMeta != null) {
            replayMeta.setDisplayName((isFavorite ? ChatColor.GOLD + "\u2605 " : "") + ChatColor.YELLOW + "Replay " + metadata.getReplayIndex());
            List<String> lore = new ArrayList<>(List.of(
                    ChatColor.GRAY + "Player: " + metadata.getPlayerName(),
                    ChatColor.GRAY + "Time: " + String.format(Locale.ROOT, "%.3f", metadata.getDurationSeconds()) + "s",
                    ChatColor.GRAY + "Blocks: " + metadata.getBlocksPlaced(),
                    ChatColor.GRAY + "Saved: " + ReplayManager.formatTimestamp(metadata.getTimestamp())
            ));
            lore.add(isFavorite
                    ? ChatColor.GOLD + "Right-click to remove favorite"
                    : ChatColor.GRAY + "Right-click to favorite");
            replayMeta.setLore(lore);
            if (isCurrentPersonalBest) {
                org.bukkit.enchantments.Enchantment unbreaking = org.bukkit.enchantments.Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
                if (unbreaking != null) {
                    replayMeta.addEnchant(unbreaking, 1, true);
                }
                replayMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            replayItem.setItemMeta(replayMeta);
        }
        return replayItem;
    }

    private ItemStack buildPersonalBestShortcutItem(SkepiFBPlugin mainPlugin, UUID replayOwnerUuid, String arenaName) {
        Optional<ReplayMetadata> pb = mainPlugin.getReplayManager().getCurrentPersonalBestReplay(replayOwnerUuid, arenaName);
        ItemStack item = new ItemStack(Material.DIAMOND_BLOCK);
        if (pb.isEmpty()) {
            ItemMeta emptyMeta = item.getItemMeta();
            if (emptyMeta != null) {
                emptyMeta.setDisplayName(ChatColor.AQUA + "\u2605 PB Replay");
                emptyMeta.setLore(List.of(ChatColor.DARK_GRAY + "No personal best yet"));
                item.setItemMeta(emptyMeta);
            }
            return item;
        }
        ReplayMetadata metadata = pb.get();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "\u2605 PB Replay " + ChatColor.GRAY + "(#" + metadata.getReplayIndex() + ")");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Your current personal best",
                    ChatColor.GRAY + "Time: " + String.format(Locale.ROOT, "%.3f", metadata.getDurationSeconds()) + "s",
                    ChatColor.GRAY + "Blocks: " + metadata.getBlocksPlaced(),
                    ChatColor.GRAY + "Click to view"
            ));
            org.bukkit.enchantments.Enchantment unbreaking = org.bukkit.enchantments.Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (unbreaking != null) {
                meta.addEnchant(unbreaking, 1, true);
            }
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildFavoriteShortcutItem(SkepiFBPlugin mainPlugin, UUID replayOwnerUuid, String arenaName) {
        Optional<ReplayMetadata> favorite = mainPlugin.getReplayManager().getFavoriteReplay(replayOwnerUuid, arenaName);
        ItemStack item = new ItemStack(Material.GOLD_BLOCK);
        if (favorite.isEmpty()) {
            ItemMeta emptyMeta = item.getItemMeta();
            if (emptyMeta != null) {
                emptyMeta.setDisplayName(ChatColor.GOLD + "\u2605 Favorite Replay");
                emptyMeta.setLore(List.of(ChatColor.DARK_GRAY + "No favorite selected", ChatColor.DARK_GRAY + "Right-click a replay to favorite it"));
                item.setItemMeta(emptyMeta);
            }
            return item;
        }
        ReplayMetadata metadata = favorite.get();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "\u2605 Favorite Replay " + ChatColor.GRAY + "(#" + metadata.getReplayIndex() + ")");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Time: " + String.format(Locale.ROOT, "%.3f", metadata.getDurationSeconds()) + "s",
                    ChatColor.GRAY + "Blocks: " + metadata.getBlocksPlaced(),
                    ChatColor.GRAY + "Saved: " + ReplayManager.formatTimestamp(metadata.getTimestamp()),
                    ChatColor.GRAY + "Click to view"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createDisabledPageItem(String displayName) {
        ItemStack barrier = new ItemStack(Material.BARRIER);
        ItemMeta barrierMeta = barrier.getItemMeta();
        if (barrierMeta != null) {
            barrierMeta.setDisplayName(displayName);
            barrier.setItemMeta(barrierMeta);
        }
        return barrier;
    }

    /**
     * Fetches this player/arena's replay list sorted by whatever order is CURRENTLY selected for the
     * viewing player. Every place that resolves a clicked slot back to a specific ReplayMetadata
     * (opening a replay, toggling favorite) must go through this same method so the list it resolves
     * against always matches what was actually rendered on screen - resolving against a
     * differently-sorted list would open/favorite the wrong replay entirely.
     */
    private List<ReplayMetadata> getDisplayedReplays(Player viewer, UUID replayOwnerUuid, String arenaName) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        List<ReplayMetadata> replays = new ArrayList<>(mainPlugin.getReplayManager().listReplayMetadata(replayOwnerUuid, arenaName));
        int sortOrder = replayMenuSortOrder.getOrDefault(viewer.getUniqueId(), 0);
        applyReplaySortOrder(replays, sortOrder);
        return replays;
    }

    private void handleReplayClick(Player player, int replayPosition) {
        String arenaName = replayMenuArenas.getOrDefault(player.getUniqueId(), null);
        if (arenaName == null) {
            player.sendMessage(ChatColor.RED + "You must be in an arena to play a replay.");
            return;
        }

        UUID replayOwnerUuid = replayMenuOwners.getOrDefault(player.getUniqueId(), player.getUniqueId());
        List<ReplayMetadata> replays = getDisplayedReplays(player, replayOwnerUuid, arenaName);
        if (replayPosition < 0 || replayPosition >= replays.size()) {
            player.sendMessage(ChatColor.RED + "That replay selection is invalid.");
            return;
        }

        playReplay(player, replays.get(replayPosition));
    }

    private void playReplay(Player player, ReplayMetadata metadata) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        player.closeInventory();
        mainPlugin.getTimerManager().startReplay(player, metadata);
    }

    /**
     * Right-clicking a normal replay entry toggles its favorite status (left-click keeps opening
     * the replay as before, handled separately in onInventoryClick). The menu is re-rendered in
     * place afterward so the star indicator and the slot 19 shortcut update immediately without
     * closing the GUI.
     */
    private void handleReplayFavoriteToggle(Player player, UUID replayOwnerUuid, String arenaName, int replayPosition, int page) {
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        List<ReplayMetadata> replays = getDisplayedReplays(player, replayOwnerUuid, arenaName);
        if (replayPosition < 0 || replayPosition >= replays.size()) {
            return;
        }
        ReplayMetadata selected = replays.get(replayPosition);
        boolean nowFavorited = mainPlugin.getReplayManager().toggleFavoriteReplay(replayOwnerUuid, arenaName, selected.getReplayId());
        player.sendMessage(nowFavorited
                ? ChatColor.GREEN + "Favorited Replay " + selected.getReplayIndex() + "."
                : ChatColor.YELLOW + "Removed Replay " + selected.getReplayIndex() + " from favorites.");
        Inventory currentMenu = player.getOpenInventory().getTopInventory();
        if (currentMenu != null && currentMenu.getHolder() instanceof MenuInventoryHolder holder && "replay_menu".equals(holder.getMenuKey())) {
            fillReplayMenu(currentMenu, player, replayOwnerUuid, arenaName, page);
        }
    }

    /**
     * Left-click advances to the next sort mode, right-click goes back one, both wrapping around
     * (index 3 + left-click -> 0, index 0 + right-click -> 3). Resets to page 0 since the replay
     * ordering just changed and the old page position would otherwise show an arbitrary,
     * inconsistent slice of the newly-sorted list.
     */
    private void handleReplaySortClick(Player player, boolean isRightClick, UUID replayOwnerUuid, String arenaName, int page) {
        if (arenaName == null) {
            return;
        }
        int current = replayMenuSortOrder.getOrDefault(player.getUniqueId(), 0);
        int size = REPLAY_SORT_LABELS.length;
        int next = isRightClick
                ? ((current - 1) % size + size) % size
                : (current + 1) % size;
        replayMenuSortOrder.put(player.getUniqueId(), next);
        openReplayMetadataMenu(player, replayOwnerUuid, arenaName, 0);
    }

    private int resolveReplaySlotIndex(int slot, int page) {
        List<Integer> slots = getReplayInteriorSlots(54);
        int baseIndex = page * slots.size();
        int localIndex = slots.indexOf(slot);
        if (localIndex < 0) {
            return -1;
        }
        return baseIndex + localIndex;
    }

    private List<Integer> getReplayInteriorSlots(int size) {
        return new ArrayList<>(REPLAY_MENU_SLOTS);
    }

    private boolean isBorderSlot(int slot, int size) {
        if (size != 54) {
            return slot < 9 || slot % 9 == 0 || slot % 9 == 8 || slot >= 45;
        }
        return slot < 9 || slot % 9 == 0 || slot % 9 == 8 || slot >= 45;
    }

    private void handleSettingsMenu(Player player) {
        openSettingsMenu(player);
    }

    public void openSettingsMenu(Player player) {
        ConfigurationSection menuSection = configManager.getSettingsMenuSection();
        if (menuSection == null) {
            player.sendMessage(ChatColor.RED + "Settings menu is not configured.");
            return;
        }

        String title = ChatColor.translateAlternateColorCodes('&', menuSection.getString("title", "&aSettings"));
        int size = menuSection.getInt("size", 27);
        Inventory menu = Bukkit.createInventory(new MenuInventoryHolder("settings_menu"), size, title);
        fillSettingsMenu(menu, menuSection);
        player.openInventory(menu);
    }

    private void fillSettingsMenu(Inventory menu, ConfigurationSection menuSection) {
        for (int slot = 0; slot < menu.getSize(); slot++) {
            ConfigurationSection itemSection = configManager.getMenuItemsBySlot("settings_menu").get(slot);
            if (itemSection == null) {
                continue;
            }
            menu.setItem(slot, createConfiguredMenuItem(itemSection));
        }
    }

    public void openBlankSubmenu(Player player, String menuKey) {
        if (menuKey == null || menuKey.isBlank()) {
            return;
        }

        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        boolean isKnownShopMenu = switch (menuKey.toLowerCase(Locale.ROOT)) {
            case "block_shop", "tools_shop", "reset_animation", "firework_color", "practice_shop", "rankups", "island_shop", "tag_shop" -> true;
            default -> false;
        };

        if (isKnownShopMenu) {
            mainPlugin.getShopManager().openShopMenu(player, menuKey);
            return;
        }

        // island_menu is dynamically populated (actual per-island buttons reflecting live
        // occupancy) by openIslandMenu/fillIslandMenu, NOT by the generic static-item renderer
        // below - falling through to the generic path here (e.g. if reached via a menu.yml button
        // using action: menu / menu: island_menu, rather than the hotbar's island_menu action)
        // rendered a technically-valid but empty menu with no actual islands in it, since the
        // dynamic population never ran.
        if ("island_menu".equalsIgnoreCase(menuKey)) {
            openIslandMenu(player, 0);
            return;
        }

        ConfigurationSection menuSection = configManager.getMenuSection(menuKey);
        if (menuSection == null) {
            player.sendMessage(ChatColor.RED + "That menu is not configured.");
            return;
        }

        String title = ChatColor.translateAlternateColorCodes('&', menuSection.getString("title", menuKey));
        int size = menuSection.getInt("size", 54);
        Inventory menu = Bukkit.createInventory(new MenuInventoryHolder(menuKey), size, title);
        boolean isShiftedMenu = "mode_changer_menu".equals(menuKey) || "fastbuilder_settings_menu".equals(menuKey) || "cosmetics_menu".equals(menuKey);

        if (isShiftedMenu) {
            // Top row decorative black panes
            ItemStack blackPane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta bpMeta = blackPane.getItemMeta();
            if (bpMeta != null) {
                bpMeta.setDisplayName(" ");
                blackPane.setItemMeta(bpMeta);
            }
            for (int i = 0; i <= 8 && i < menu.getSize(); i++) {
                menu.setItem(i, blackPane);
            }

            // Title item in center
            ItemStack titleItem;
            switch (menuKey) {
                case "mode_changer_menu" -> {
                    titleItem = new ItemStack(Material.CLOCK);
                    ItemMeta m = titleItem.getItemMeta(); if (m != null) { m.setDisplayName(ChatColor.YELLOW + "Mode Switcher"); titleItem.setItemMeta(m); }
                }
                case "fastbuilder_settings_menu" -> {
                    titleItem = new ItemStack(Material.SANDSTONE_STAIRS);
                    ItemMeta m = titleItem.getItemMeta(); if (m != null) { m.setDisplayName(ChatColor.GOLD + "Fastbuilder Settings"); titleItem.setItemMeta(m); }
                }
                case "cosmetics_menu" -> {
                    titleItem = new ItemStack(Material.ENDER_CHEST);
                    ItemMeta m = titleItem.getItemMeta(); if (m != null) { m.setDisplayName(ChatColor.LIGHT_PURPLE + "Cosmetics"); titleItem.setItemMeta(m); }
                }
                default -> titleItem = new ItemStack(Material.BOOK);
            }
            if (menu.getSize() > 4) {
                menu.setItem(4, titleItem);
            }
        }

        Map<Integer, ConfigurationSection> items = configManager.getMenuItemsBySlot(menuKey);
        if (items != null) {
            for (Map.Entry<Integer, ConfigurationSection> e : items.entrySet()) {
                int configuredSlot = e.getKey();
                ConfigurationSection itemSection = e.getValue();
                if (itemSection == null) continue;
                int targetSlot = configuredSlot;
                if (targetSlot >= 0 && targetSlot < menu.getSize()) {
                    menu.setItem(targetSlot, createConfiguredMenuItem(itemSection));
                }
            }
        }
        player.openInventory(menu);
    }

    private static final class MenuInventoryHolder implements InventoryHolder {
        private final String menuKey;

        private MenuInventoryHolder(String menuKey) {
            this.menuKey = menuKey;
        }

        public String getMenuKey() {
            return menuKey;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private void handleLeave(Player player, SkepiFBPlugin mainPlugin) {
        UUID playerUuid = player.getUniqueId();
        // Fully clear all session state
        mainPlugin.getTimerManager().clearSession(playerUuid);
        mainPlugin.getPlayerManager().leaveArena(player);
        mainPlugin.getScoreboardManager().hideScoreboard(player);
        
        // Clear player inventory
        player.getInventory().clear();
        
        player.sendMessage(ChatColor.GREEN + "You left the arena.");
    }

    public HotbarManager getHotbarManager() {
        return this;
    }
}
