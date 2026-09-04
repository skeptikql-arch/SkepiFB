package me.skepi.skepifb.statsmenu;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Backs both /stats [player] (and /fb stats) and the generic "stats" item type usable in ANY
 * menu - built-in or custom_menus.yml - via:
 *   material: RED_BED
 *   action: stats
 *   stats: <player name, or "self"/blank for whoever opened the menu>
 *   statmode: <arena name>
 *
 * A "stats" item is display-only (dynamic lore, no click behavior) everywhere except the /stats
 * command's own generated menu, where the interior slots are auto-populated with one such item
 * per known arena.
 */
public class StatsMenuManager {

    private static final List<Integer> STATS_MENU_INTERIOR_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );

    private final SkepiFBPlugin plugin;
    private final File legacyStatsMenuFile;
    private final File statsMenuFile;
    private YamlConfiguration statsMenuConfiguration;

    public StatsMenuManager(SkepiFBPlugin plugin) {
        this.plugin = plugin;
        // Legacy top-level location, kept only so a pre-existing stats_menu.yml can be migrated
        // into menus/ below (see migrateLegacyFileIfPresent()) - same non-destructive pattern
        // ConfigManager uses for menu.yml -> menus/. The plugin never writes to this path again
        // once that migration has happened.
        this.legacyStatsMenuFile = new File(plugin.getDataFolder(), "stats_menu.yml");
        File menusFolder = new File(plugin.getDataFolder(), "menus");
        if (!menusFolder.exists()) {
            menusFolder.mkdirs();
        }
        this.statsMenuFile = new File(menusFolder, "stats_menu.yml");
        migrateLegacyFileIfPresent();
        createDefaultIfMissing();
        this.statsMenuConfiguration = loadConfigurationSafely();
    }

    public void reload() {
        migrateLegacyFileIfPresent();
        createDefaultIfMissing();
        this.statsMenuConfiguration = loadConfigurationSafely();
        syncArenaEntries();
    }

    /**
     * Ensures every currently-registered arena has a real, visible "action: stats" entry under
     * items: in menus/stats_menu.yml, instead of that arena's paper icon only ever existing as an
     * invisible, code-only auto-fill (see the per-arena loop in openStatsMenu()). Called once at
     * startup - after ArenaManager has actually loaded its arenas, since this manager is
     * constructed before that happens - and again on every reload() (i.e. every /fb reload), so an
     * arena added later still gets an entry without needing a full restart.
     * <p>
     * An arena already covered by an existing "action: stats" item - matched by its "statmode:"
     * value, case-insensitively, and only when that item's "stats:" is blank/"self"/"%player%"
     * (a literal player name means it's a deliberate "always show this person" item, not this
     * arena's generic slot) - is left completely alone, wherever the admin has it and however
     * they've styled it (material/name/slot). Only genuinely uncovered arenas get a brand new
     * entry, dropped into the next free interior slot. If every interior slot is already taken,
     * that one arena is simply left to the runtime fallback in openStatsMenu(), silently -
     * existing entries are never displaced or overwritten to make room.
     */
    public synchronized void syncArenaEntries() {
        List<Arena> arenas = plugin.getArenaManager().getArenas();
        if (arenas.isEmpty()) {
            return;
        }

        ConfigurationSection items = statsMenuConfiguration.getConfigurationSection("items");
        if (items == null) {
            items = statsMenuConfiguration.createSection("items");
        }

        java.util.Set<Integer> usedSlots = new java.util.HashSet<>();
        java.util.Set<String> coveredArenas = new java.util.HashSet<>();
        for (String slotKey : items.getKeys(false)) {
            int slot;
            try {
                slot = Integer.parseInt(slotKey);
            } catch (NumberFormatException ex) {
                continue;
            }
            usedSlots.add(slot);
            ConfigurationSection itemSection = items.getConfigurationSection(slotKey);
            if (itemSection == null || !"stats".equalsIgnoreCase(itemSection.getString("action", ""))) {
                continue;
            }
            String statmode = itemSection.getString("statmode", "");
            String statsField = itemSection.getString("stats", "self");
            boolean genericTarget = statsField == null || statsField.isBlank()
                    || statsField.equalsIgnoreCase("self") || statsField.equalsIgnoreCase("%player%");
            if (!statmode.isBlank() && genericTarget) {
                coveredArenas.add(statmode.toLowerCase(Locale.ROOT));
            }
        }

        boolean changed = false;
        for (Arena arena : arenas) {
            if (coveredArenas.contains(arena.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Integer freeSlot = null;
            for (int candidate : STATS_MENU_INTERIOR_SLOTS) {
                if (!usedSlots.contains(candidate)) {
                    freeSlot = candidate;
                    break;
                }
            }
            if (freeSlot == null) {
                // No warning logged here on purpose - with more arenas than interior slots this
                // fires once per uncovered arena on every load/reload and floods the console.
                // Behavior is unchanged: the arena still shows up automatically via the runtime
                // fallback in openStatsMenu(), it just isn't a directly-editable persistent entry
                // in menus/stats_menu.yml until a slot is freed up (e.g. by increasing "size").
                continue;
            }
            usedSlots.add(freeSlot);
            coveredArenas.add(arena.getName().toLowerCase(Locale.ROOT));
            ConfigurationSection entry = items.createSection(String.valueOf(freeSlot));
            entry.set("material", "PAPER");
            entry.set("name", "&b" + arena.getName());
            entry.set("action", "stats");
            entry.set("stats", "self");
            entry.set("statmode", arena.getName());
            changed = true;
        }

        if (changed) {
            try {
                statsMenuConfiguration.save(statsMenuFile);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save menus/stats_menu.yml after adding new arena stats "
                        + "entries: " + ex.getMessage());
            }
        }
    }

    /**
     * One-time migration: if a stats_menu.yml still exists at the old top-level location (from
     * before this file joined every other built-in menu under menus/) and nothing has been created
     * at the new location yet, move it there as-is so nobody's title/size/items customization is
     * lost. The old path is left behind renamed to stats_menu.yml.migrated as a backup, exactly
     * like ConfigManager does for the legacy menu.yml.
     */
    private void migrateLegacyFileIfPresent() {
        if (statsMenuFile.exists() || !legacyStatsMenuFile.exists()) {
            return;
        }
        try {
            Files.copy(legacyStatsMenuFile.toPath(), statsMenuFile.toPath());
            File backup = new File(legacyStatsMenuFile.getParentFile(), "stats_menu.yml.migrated");
            Files.move(legacyStatsMenuFile.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Migrated stats_menu.yml into the menus/ folder, alongside every other "
                    + "built-in menu. The old stats_menu.yml was renamed to stats_menu.yml.migrated as a backup.");
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not migrate stats_menu.yml into menus/: " + ex.getMessage());
        }
    }

    /**
     * Loads stats_menu.yml, self-healing if the file on disk is corrupt (invalid YAML), the same
     * way PermissionsManager now handles a broken permissions.yml: back up the broken file rather
     * than silently keep failing or crash, and regenerate a fresh valid default so /stats keeps
     * working.
     */
    private YamlConfiguration loadConfigurationSafely() {
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(statsMenuFile);
            return loaded;
        } catch (Exception ex) {
            plugin.getLogger().warning("menus/stats_menu.yml could not be parsed (" + ex.getMessage()
                    + "). Backing up the broken file and regenerating a fresh default.");
            try {
                File backup = new File(statsMenuFile.getParentFile(),
                        "stats_menu.yml.broken-" + System.currentTimeMillis());
                Files.move(statsMenuFile.toPath(), backup.toPath());
            } catch (IOException moveEx) {
                plugin.getLogger().warning("Could not back up broken stats_menu.yml: " + moveEx.getMessage());
            }
            createDefaultIfMissing();
            try {
                YamlConfiguration recovered = new YamlConfiguration();
                recovered.load(statsMenuFile);
                return recovered;
            } catch (Exception fatal) {
                plugin.getLogger().severe("Could not load even a freshly-regenerated stats_menu.yml: " + fatal.getMessage());
                return new YamlConfiguration();
            }
        }
    }

    /**
     * Opens the /stats menu for "viewer" showing "target"'s stats - one auto-generated item per
     * known arena, dropped into the same free interior slots every other generic menu uses.
     * Title/size/border all come from stats_menu.yml (falling back to sane defaults if any of
     * those keys are missing), so admins can restyle the shell without touching plugin code.
     */
    public void openStatsMenu(Player viewer, OfflinePlayer target) {
        String rawTitle = statsMenuConfiguration.getString("title", "&6%player%'s Stats");
        String title = ChatColor.translateAlternateColorCodes('&',
                rawTitle.replace("%player%", target.getName() == null ? "Unknown" : target.getName()));
        int size = statsMenuConfiguration.getInt("size", 45);
        if (size <= 0 || size % 9 != 0) {
            size = 45;
        }
        Inventory menu = Bukkit.createInventory(new StatsMenuHolder(), size, title);

        // Optional custom border/decoration items, same "items:" format as every other menu - PLUS
        // support for "action: stats" items placed anywhere here (border or otherwise), which
        // render as a live stat display for this menu's target the same way they do in any other
        // menu (island menu, custom_menus.yml, etc). Every slot successfully placed here is
        // recorded in configuredSlots so the auto-generated per-arena entries below never
        // overwrite it, no matter which interior slot it happens to land on.
        //
        // Each slot is built inside its OWN try/catch (Throwable, not just NumberFormatException):
        // one malformed/mistyped entry used to be able to throw partway through building the item
        // (e.g. an empty "material:" or an arena name typo in "statmode:") and abort this entire
        // method before it ever reached the per-arena loop below or opened the inventory at all -
        // which looked exactly like "the menu is broken" (no arena paper icons AND the item you
        // were testing silently doing nothing), when only that one slot was ever actually bad. Now
        // a bad slot logs exactly what's wrong with it and is skipped; everything else still opens.
        java.util.Set<Integer> configuredSlots = new java.util.HashSet<>();
        ConfigurationSection items = statsMenuConfiguration.getConfigurationSection("items");
        if (items != null) {
            for (String slotKey : items.getKeys(false)) {
                int slot;
                try {
                    slot = Integer.parseInt(slotKey);
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("menus/stats_menu.yml has a non-numeric item key \""
                            + slotKey + "\" under items: - slot keys must be plain numbers. Skipping it.");
                    continue;
                }
                if (slot < 0 || slot >= size) {
                    plugin.getLogger().warning("menus/stats_menu.yml has an item at slot " + slot
                            + ", which is outside this menu's size (" + size + "). Skipping it.");
                    continue;
                }
                // Reserved BEFORE attempting to build it, not after: previously, if building threw
                // partway through, this slot was never added to configuredSlots at all (both the
                // build and the .add() were inside the same try block) - meaning the per-arena loop
                // below saw it as "still free" and silently filled it with an auto-generated paper
                // entry instead. That made a broken custom item look like it was simply never
                // there, with no visible sign anything was even configured at that slot. Now the
                // slot is claimed the moment we know it's configured, regardless of build outcome,
                // and a failure renders a visible in-game error item (with the exception message in
                // its lore) instead of quietly ceding the slot to the arena auto-fill.
                configuredSlots.add(slot);
                try {
                    ConfigurationSection itemSection = items.getConfigurationSection(slotKey);
                    if (itemSection == null) {
                        continue;
                    }
                    String action = itemSection.getString("action", "");
                    ItemStack built = "stats".equalsIgnoreCase(action)
                            ? buildStatsItemForMenuTarget(itemSection, target)
                            : buildStaticItem(itemSection);
                    menu.setItem(slot, built);
                } catch (Throwable ex) {
                    plugin.getLogger().warning("menus/stats_menu.yml's item at slot " + slot
                            + " failed to build (" + ex + ") and was skipped. Check its material/action/"
                            + "stats/statmode fields.");
                    menu.setItem(slot, buildBrokenItemPlaceholder(slot, ex));
                }
            }
        }

        // One paper entry per arena, filling whichever interior slots are still free (i.e. NOT
        // already claimed by an explicit items: entry above) in order. Iterating the slot list
        // itself - rather than the arena list, as before - means a slot this menu's size is too
        // small to include, or one the admin already configured, is simply skipped over instead of
        // silently stalling the rest of the arenas behind it. Each arena's item is also built in
        // its own try/catch so one arena with bad data can't take every other arena's paper icon
        // down with it, or prevent the menu from opening at all.
        List<Arena> arenas = plugin.getArenaManager().getArenas();
        int arenaIndex = 0;
        for (int slot : STATS_MENU_INTERIOR_SLOTS) {
            if (arenaIndex >= arenas.size()) {
                break;
            }
            if (slot >= size || configuredSlots.contains(slot)) {
                continue;
            }
            Arena arena = arenas.get(arenaIndex);
            arenaIndex++;
            try {
                menu.setItem(slot, buildStatsItem(Material.PAPER, ChatColor.AQUA + arena.getName(), target, arena.getName()));
            } catch (Throwable ex) {
                plugin.getLogger().warning("Failed to build the stats entry for arena \"" + arena.getName()
                        + "\" (" + ex + "). Skipping just that entry.");
            }
        }

        viewer.openInventory(menu);
    }

    private ItemStack buildBrokenItemPlaceholder(int slot, Throwable ex) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "Broken item at slot " + slot);
            meta.setLore(List.of(
                    ChatColor.GRAY + "This item failed to load:",
                    ChatColor.GRAY + String.valueOf(ex),
                    ChatColor.GRAY + "Check menus/stats_menu.yml for this slot."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildStaticItem(ConfigurationSection itemSection) {
        Material material;
        try {
            material = Material.valueOf(itemSection.getString("material", "STONE").toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            material = Material.STONE;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = itemSection.getString("name");
            if (name != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            }
            List<String> lore = itemSection.getStringList("lore");
            if (!lore.isEmpty()) {
                List<String> translated = new ArrayList<>();
                for (String line : lore) {
                    translated.add(ChatColor.translateAlternateColorCodes('&', line));
                }
                meta.setLore(translated);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Builds the generic dynamic stats display item: personal best, average time, attempts, and
     * top percentile for "target" in "modeArenaName", using the exact same data sources the
     * scoreboard/statboard/finish-message placeholders already use. This is called both for the
     * /stats command's auto-generated per-arena items AND for any "action: stats" item configured
     * in any other menu.
     */
    public ItemStack buildStatsItem(Material material, String displayName, OfflinePlayer target, String modeArenaName) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(displayName == null ? "" : ChatColor.translateAlternateColorCodes('&', displayName));

        UUID targetUuid = target.getUniqueId();
        double pb = plugin.getStatsManager().getPersonalBest(targetUuid, modeArenaName);
        double avg = plugin.getStatsManager().getAverageCompletionTime(targetUuid, modeArenaName);
        int completions = plugin.getStatsManager().getCompletions(targetUuid, modeArenaName);
        int attempts = plugin.getTimerManager().getAttemptsPlaceholder(targetUuid, modeArenaName);
        double topPercentile = plugin.getScoreboardManager().getTopPercentile(targetUuid, modeArenaName);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + modeArenaName);
        lore.add(ChatColor.GRAY + "Player: " + ChatColor.WHITE + (target.getName() == null ? "Unknown" : target.getName()));
        lore.add("");
        lore.add(ChatColor.GRAY + "Personal Best: " + ChatColor.YELLOW + (pb > 0 ? String.format(Locale.ROOT, "%.3f", pb) + "s" : "N/A"));
        lore.add(ChatColor.GRAY + "Average Time: " + ChatColor.YELLOW + (avg > 0 ? String.format(Locale.ROOT, "%.3f", avg) + "s" : "N/A"));
        lore.add(ChatColor.GRAY + "Attempts: " + ChatColor.YELLOW + attempts);
        lore.add(ChatColor.GRAY + "Completions: " + ChatColor.YELLOW + (completions > 0 ? completions : "N/A"));
        lore.add(ChatColor.GRAY + "Top Percentile: " + ChatColor.YELLOW + (topPercentile >= 0 ? String.format(Locale.ROOT, "%.2f%%", topPercentile) : "N/A"));
        meta.setLore(lore);

        NamespacedKey key = NamespacedKey.fromString("skepifb:menu_action");
        if (key != null) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "stats");
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Resolves the "stats:" field of a generic stats item: blank, "self", or "%player%" all mean
     * "whoever is viewing the menu"; anything else is looked up as a literal player name (works
     * for offline players too, so a fixed "hall of fame" style item pointing at a specific person
     * works even when they're not online).
     */
    public OfflinePlayer resolveStatsTarget(Player viewer, String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()
                || configuredValue.equalsIgnoreCase("self") || configuredValue.equalsIgnoreCase("%player%")) {
            return viewer;
        }
        Player online = Bukkit.getPlayerExact(configuredValue);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayer(configuredValue);
    }

    /**
     * Resolves the "statmode:" field of a generic stats item: if left blank, falls back to
     * whichever arena "target" is CURRENTLY playing, if any - this is what makes a bare
     * "action: stats" item (material/action only, no statmode at all) work as a simple "show my
     * current run's stats" shortcut instead of always requiring an explicit arena name up front.
     * Returns null if there's nothing sensible to show (blank AND target isn't in an arena).
     */
    private String resolveStatmode(OfflinePlayer target, String configuredStatmode) {
        if (configuredStatmode != null && !configuredStatmode.isBlank()) {
            return configuredStatmode;
        }
        if (target == null) {
            return null;
        }
        String currentArena = plugin.getPlayerManager().getPlayerArena(target.getUniqueId());
        return (currentArena == null || currentArena.isBlank()) ? null : currentArena;
    }

    /**
     * Builds a live "action: stats" item from its menu.yml/custom_menus.yml/shop.yml-style
     * ConfigurationSection (material/name are taken from config; the dynamic stat lore is always
     * appended fresh). Used by the generic menu-rendering code path so a stats item looks and
     * behaves the same no matter which menu file it's declared in.
     */
    public ItemStack buildStatsItemFromSection(ConfigurationSection itemSection, Player viewer) {
        Material material;
        try {
            material = Material.valueOf(itemSection.getString("material", "PAPER").toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            material = Material.PAPER;
        }
        String name = itemSection.getString("name", "&6Stats");
        String statsField = itemSection.getString("stats", "self");
        OfflinePlayer target = resolveStatsTarget(viewer, statsField);
        String statmode = resolveStatmode(target, itemSection.getString("statmode", ""));
        if (statmode == null) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                meta.setLore(List.of(ChatColor.RED + "No statmode configured, and not currently in an arena"));
                item.setItemMeta(meta);
            }
            return item;
        }
        return buildStatsItem(material, name, target, statmode);
    }

    /**
     * Same as buildStatsItemFromSection, but purpose-built for use inside the /stats menu itself:
     * blank/"self"/"%player%" in the "stats:" field resolves to "menuTarget" (the player this
     * whole menu is showing - i.e. whoever /stats [player] was actually run for), not the viewer.
     * That matters when an admin runs /stats on someone else: a border item left on "self" should
     * show the same person every other item in the menu is about, not the admin's own stats. A
     * literal player name in "stats:" is unaffected either way and still looks that name up
     * directly (works offline too).
     */
    private ItemStack buildStatsItemForMenuTarget(ConfigurationSection itemSection, OfflinePlayer menuTarget) {
        Material material;
        try {
            material = Material.valueOf(itemSection.getString("material", "PAPER").toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            material = Material.PAPER;
        }
        String name = itemSection.getString("name", "&6Stats");
        String statsField = itemSection.getString("stats", "self");
        OfflinePlayer target;
        if (statsField == null || statsField.isBlank()
                || statsField.equalsIgnoreCase("self") || statsField.equalsIgnoreCase("%player%")) {
            target = menuTarget;
        } else {
            Player online = Bukkit.getPlayerExact(statsField);
            target = online != null ? online : Bukkit.getOfflinePlayer(statsField);
        }
        String statmode = resolveStatmode(target, itemSection.getString("statmode", ""));
        if (statmode == null) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                meta.setLore(List.of(ChatColor.RED + "No statmode configured, and not currently in an arena"));
                item.setItemMeta(meta);
            }
            return item;
        }
        return buildStatsItem(material, name, target, statmode);
    }

    /**
     * Shared handler for both /stats [player] and /fb stats [player]. Resolves the target player
     * (self if no argument given), checks that they've actually played before, and opens the menu.
     */
    public boolean handleStatsCommand(org.bukkit.command.CommandSender sender, String[] args, int nameArgIndex) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(ChatColor.RED + "Only players may use this command.");
            return true;
        }
        if (!plugin.getPermissionsManager().hasCommandPermission(sender, "stats")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        OfflinePlayer target = viewer;
        if (args.length > nameArgIndex) {
            String name = args[nameArgIndex];
            Player online = Bukkit.getPlayerExact(name);
            target = online != null ? online : Bukkit.getOfflinePlayer(name);
            if (target.getName() == null) {
                sender.sendMessage(ChatColor.RED + "That player has never played on this server.");
                return true;
            }
        }
        openStatsMenu(viewer, target);
        return true;
    }

    private void createDefaultIfMissing() {
        if (statsMenuFile.exists()) {
            return;
        }
        String content = "# SkepiFB stats menu configuration file\n"
                + "#\n"
                + "# Controls the shell of the menu opened by /stats and /fb stats: title, size, and any\n"
                + "# optional decorative/static items (same \"items:\" format as every other menu - material/\n"
                + "# name/lore only, since this menu has no click behavior of its own, see below). The actual\n"
                + "# stat entries (one per arena) are generated automatically, one per known arena, and placed\n"
                + "# into whichever free interior slots aren't listed under \"items:\" below - you don't list\n"
                + "# them here, and they will never overwrite an item you do configure.\n"
                + "#\n"
                + "# Ships with a bordered default layout, same look as every other built-in menu (island_menu,\n"
                + "# mode_changer_menu, etc): slots 0-9, 17, 18, 26, 27, 35, 36, 44 are the border, a title item\n"
                + "# sits at slot 4, and the free interior slots (10-16, 19-25, 28-34, 37-43) are where the\n"
                + "# auto-generated per-arena stat entries land. Feel free to restyle, resize, or remove any of\n"
                + "# it - it's a completely normal \"items:\" map like any other menu.\n"
                + "#\n"
                + "# The exact same stats display can also be embedded in ANY OTHER menu (built-in or\n"
                + "# custom_menus.yml) using a plain item entry like:\n"
                + "#   material: RED_BED\n"
                + "#   action: stats\n"
                + "#   stats: \"self\"       # or a literal player name, e.g. \"Notch\", to always show their stats\n"
                + "#   statmode: \"Ranked\"  # the arena/mode name to show stats for\n"
                + "# That kind of item is purely informational (dynamic lore, no click behavior) wherever it\n"
                + "# appears - including in this generated menu, which is why there's no close/back button\n"
                + "# below: press Escape/E like any other inventory to leave it.\n"
                + "#\n"
                + "title: \"&6%player%'s Stats\"\n"
                + "size: 45\n"
                + "items:\n"
                + "  4:\n"
                + "    material: PLAYER_HEAD\n"
                + "    name: \"&6&l%player%'s Stats\"\n"
                + "    lore:\n"
                + "      - \"&7One entry below per arena.\"\n"
                + "  0:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  1:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  2:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  3:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  5:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  6:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  7:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  8:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  9:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  17:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  18:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  26:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  27:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  35:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  36:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n"
                + "  44:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7\"\n";
        try {
            Files.write(statsMenuFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default stats_menu.yml: " + ex.getMessage());
        }
    }

    public static final class StatsMenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
