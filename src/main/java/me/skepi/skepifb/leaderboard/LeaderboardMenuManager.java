package me.skepi.skepifb.leaderboard;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.leaderboard.LeaderboardManager.LeaderboardEntry;
import me.skepi.skepifb.leaderboard.LeaderboardManager.LeaderboardType;
import me.skepi.skepifb.util.LuckPermsUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backs both /lb and /leaderboard: a graphical, always-live top 10 leaderboard menu built from
 * real player heads - the GUI counterpart to the existing chat-based "/fb lb add/remove/list/
 * user" commands (see LeaderboardManager/FBCommand). Entirely separate system: "/fb lb ..." stays
 * exactly as it was for admin management, this only ever READS from LeaderboardManager to render
 * a menu.
 * <p>
 * Its shell (border/title/toggle/close button styling, AND which slots show leaderboard
 * positions) lives entirely in menus/leaderboard_menu.yml, generated on first use, using the
 * exact same "items:" format (material/alt-material/name/lore/action) every other menu in this
 * plugin already uses - see StatsMenuManager for the closest sibling of this pattern. A player
 * head slot is just a normal item with "action: leaderboard", plus two extra fields:
 * "lbplacement" (which position, 1-10, that slot shows) and "lbmode" ("self" to follow whichever
 * mode/board is currently being viewed, or an explicit mode name to always show that one
 * regardless). The entries themselves are never stored in the file; they are always rendered live
 * from LeaderboardManager, with an unoccupied position simply showing as a BARRIER.
 */
public class LeaderboardMenuManager implements Listener {

    // Which position (1-10) each of the default podium/rank slots renders - used only to generate
    // the default file below. The actual slot -> position mapping a running server uses always
    // comes from menus/leaderboard_menu.yml's "items:" section (any slot with "action: leaderboard"
    // and an "lbplacement:"), never from a hardcoded slot list - see buildLeaderboardActionItem().
    private static final int TITLE_SLOT = 4;
    private static final int TOGGLE_SLOT = 39;
    private static final int CLOSE_SLOT = 40;
    private static final int DEFAULT_MENU_SIZE = 45;

    private final SkepiFBPlugin plugin;
    private final File menuFile;
    private YamlConfiguration menuConfiguration;

    // Per-viewer state for whichever leaderboard menu they currently have open - which board
    // (verified/unverified) and which mode it's showing - so a toggle click knows exactly what to
    // rebuild without needing to re-parse the inventory title. Cleared the moment they close it.
    private final Map<UUID, LeaderboardType> openType = new ConcurrentHashMap<>();
    private final Map<UUID, String> openMode = new ConcurrentHashMap<>();

    public LeaderboardMenuManager(SkepiFBPlugin plugin) {
        this.plugin = plugin;
        File menusFolder = new File(plugin.getDataFolder(), "menus");
        if (!menusFolder.exists()) {
            menusFolder.mkdirs();
        }
        this.menuFile = new File(menusFolder, "leaderboard_menu.yml");
        createDefaultIfMissing();
        this.menuConfiguration = loadConfigurationSafely();
    }

    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Re-reads menus/leaderboard_menu.yml from disk - called from /fb reload, same as every other
     * menu-backed manager (StatsMenuManager, ShopManager, etc). Does not touch a menu a player
     * already has open; they'll see the refreshed styling next time they open it.
     */
    public void reload() {
        createDefaultIfMissing();
        this.menuConfiguration = loadConfigurationSafely();
    }

    private YamlConfiguration loadConfigurationSafely() {
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(menuFile);
            return loaded;
        } catch (Exception ex) {
            plugin.getLogger().warning("menus/leaderboard_menu.yml could not be parsed (" + ex.getMessage()
                    + "). Backing up the broken file and regenerating a fresh default.");
            try {
                File backup = new File(menuFile.getParentFile(),
                        "leaderboard_menu.yml.broken-" + System.currentTimeMillis());
                Files.move(menuFile.toPath(), backup.toPath());
            } catch (IOException moveEx) {
                plugin.getLogger().warning("Could not back up broken leaderboard_menu.yml: " + moveEx.getMessage());
            }
            createDefaultIfMissing();
            try {
                YamlConfiguration recovered = new YamlConfiguration();
                recovered.load(menuFile);
                return recovered;
            } catch (Exception fatal) {
                plugin.getLogger().severe("Could not load even a freshly-regenerated leaderboard_menu.yml: " + fatal.getMessage());
                return new YamlConfiguration();
            }
        }
    }

    // ------------------------------------------------------------------
    // Opening the menu
    // ------------------------------------------------------------------

    /**
     * Shared handler for plain "/lb" and "/leaderboard" (no mode argument) - always opens showing
     * the UNVERIFIED board by default (per-open default, not persisted - closing and reopening
     * always starts back on unverified), for whichever mode the viewer is CURRENTLY standing in
     * (see resolveViewerMode()) - falling back to resolveDefaultMode() if they aren't in an arena
     * at all (e.g. lobby).
     */
    public void openLeaderboardMenu(Player viewer) {
        openLeaderboardMenu(viewer, resolveViewerMode(viewer));
    }

    public void openLeaderboardMenu(Player viewer, String mode) {
        LeaderboardType defaultType = LeaderboardType.fromArgument(menuConfiguration.getString("default-type", "unverified"));
        openMenu(viewer, defaultType == null ? LeaderboardType.UNVERIFIED : defaultType, mode);
    }

    /**
     * What mode a bare "/lb"/"/leaderboard" (no argument) should open for this particular viewer -
     * the arena/mode they are currently playing in takes priority so the menu opens "on" whatever
     * they're doing, falling back to resolveDefaultMode() for a viewer who isn't in an arena.
     */
    private String resolveViewerMode(Player viewer) {
        String currentArena = plugin.getPlayerManager().getPlayerArena(viewer.getUniqueId());
        if (currentArena != null && !currentArena.isBlank()) {
            return currentArena;
        }
        return resolveDefaultMode();
    }

    /**
     * Resolves a user-typed mode argument (e.g. "/lb Snow") to the mode's canonical display-case
     * name, case-insensitively - so "/lb snow", "/lb SNOW" and "/lb Snow" all land on the same
     * leaderboard. Prefers a live arena's exact name; falls back to whatever casing is already
     * stored for that mode on either board (covers a mode whose arena was since removed but still
     * has leaderboard data); if neither matches anything at all, the raw argument is used as-is so
     * the menu simply renders every position empty rather than silently refusing to open.
     */
    public String resolveModeArgument(String rawArg) {
        if (rawArg == null || rawArg.isBlank()) {
            return resolveDefaultMode();
        }
        String trimmed = rawArg.trim();
        Arena arena = plugin.getArenaManager().getArena(trimmed);
        if (arena != null) {
            return arena.getName();
        }
        LeaderboardManager leaderboardManager = plugin.getLeaderboardManager();
        for (LeaderboardType type : LeaderboardType.values()) {
            for (String existingMode : leaderboardManager.getLeaderboards(type)) {
                if (existingMode.equalsIgnoreCase(trimmed)) {
                    String display = leaderboardManager.getLeaderboardDisplayName(type, existingMode);
                    return display == null || display.isBlank() ? existingMode : display;
                }
            }
        }
        return trimmed;
    }

    /**
     * Resolves which mode the menu shows when nothing else forces a specific one (no viewer arena,
     * no explicit argument): an explicit "mode:" in leaderboard_menu.yml wins outright; otherwise
     * the first mode with any unverified entries, then the first with any verified entries, then
     * simply the first known arena - so the menu always has something sensible to display with
     * zero configuration on a fresh install.
     */
    private String resolveDefaultMode() {
        String configured = menuConfiguration.getString("mode", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        LeaderboardManager leaderboardManager = plugin.getLeaderboardManager();
        for (String mode : leaderboardManager.getLeaderboards(LeaderboardType.UNVERIFIED)) {
            return mode;
        }
        for (String mode : leaderboardManager.getLeaderboards(LeaderboardType.VERIFIED)) {
            return mode;
        }
        List<Arena> arenas = plugin.getArenaManager().getArenas();
        if (!arenas.isEmpty()) {
            return arenas.get(0).getName();
        }
        return "";
    }

    private void openMenu(Player viewer, LeaderboardType type, String mode) {
        int size = menuConfiguration.getInt("size", DEFAULT_MENU_SIZE);
        if (size <= 0 || size % 9 != 0) {
            size = DEFAULT_MENU_SIZE;
        }
        LeaderboardMenuHolder holder = new LeaderboardMenuHolder();
        String rawTitle = menuConfiguration.getString("title", "&6&lLeaderboard &8\u00bb &e%mode% &7(%type%)");
        String title = ChatColor.translateAlternateColorCodes('&', applyPlaceholders(rawTitle, type, mode));
        Inventory menu = Bukkit.createInventory(holder, size, title);
        holder.setInventory(menu);
        try {
            fillMenu(menu, type, mode);
        } catch (Throwable ex) {
            plugin.getLogger().warning("[SkepiFB] Failed to build the leaderboard menu for " + viewer.getName() + ": " + ex);
        }
        openType.put(viewer.getUniqueId(), type);
        openMode.put(viewer.getUniqueId(), mode);
        viewer.openInventory(menu);
    }

    private void fillMenu(Inventory menu, LeaderboardType type, String mode) {
        int size = menu.getSize();

        ConfigurationSection items = menuConfiguration.getConfigurationSection("items");
        if (items != null) {
            for (String slotKey : items.getKeys(false)) {
                int slot;
                try {
                    slot = Integer.parseInt(slotKey);
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("menus/leaderboard_menu.yml has a non-numeric item key \""
                            + slotKey + "\" under items: - slot keys must be plain numbers. Skipping it.");
                    continue;
                }
                // The toggle button always lives on TOGGLE_SLOT and is always built by the special
                // path below regardless of what's declared here - see buildToggleItem().
                if (slot < 0 || slot >= size || slot == TOGGLE_SLOT) {
                    continue;
                }
                ConfigurationSection itemSection = items.getConfigurationSection(slotKey);
                if (itemSection == null) {
                    continue;
                }
                try {
                    String action = itemSection.getString("action", "");
                    ItemStack built = "leaderboard".equalsIgnoreCase(action)
                            ? buildLeaderboardActionItem(slot, itemSection, type, mode)
                            : buildStaticItem(itemSection, type, mode);
                    menu.setItem(slot, built);
                } catch (Throwable ex) {
                    plugin.getLogger().warning("menus/leaderboard_menu.yml's item at slot " + slot
                            + " failed to build (" + ex + ") and was skipped. Check its material/name/lore/"
                            + "lbmode/lbplacement fields.");
                }
            }
        }

        // Toggle button gets special handling - its material depends on which board is currently
        // showing (see "verified-material"/"unverified-material" in the default file), so it can't
        // be built by the plain static-item path above.
        try {
            menu.setItem(TOGGLE_SLOT, buildToggleItem(items, type, mode));
        } catch (Throwable ex) {
            plugin.getLogger().warning("menus/leaderboard_menu.yml's toggle item (slot " + TOGGLE_SLOT
                    + ") failed to build (" + ex + ").");
        }

        // Anything still empty (unconfigured border slot, or an interior slot never meant to hold
        // an entry) gets a plain blank pane, same "fill remaining empty slots" pattern the island
        // menu uses, so the menu never looks unfinished regardless of how it's been restyled.
        for (int slot = 0; slot < size; slot++) {
            ItemStack existing = menu.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                menu.setItem(slot, buildBlankPane());
            }
        }
    }

    // ------------------------------------------------------------------
    // Item builders
    // ------------------------------------------------------------------

    /**
     * Builds whatever belongs at a slot declared with "action: leaderboard" in leaderboard_menu.yml
     * - this is what makes the ten player-head slots fully configurable: any slot, any count, any
     * arrangement, purely by listing them under "items:" with an "lbplacement" (1-10, which
     * position that slot shows) and an "lbmode" ("self" to follow whichever mode/board is
     * currently being viewed - the normal case - or an explicit mode name to always pin that slot
     * to one specific mode regardless of what the rest of the menu is showing).
     */
    private ItemStack buildLeaderboardActionItem(int slot, ConfigurationSection itemSection, LeaderboardType type, String mode) {
        int position = itemSection.getInt("lbplacement", -1);
        if (position < 1 || position > LeaderboardManager.MAX_POSITIONS) {
            plugin.getLogger().warning("menus/leaderboard_menu.yml's leaderboard item at slot " + slot
                    + " has a missing or out-of-range \"lbplacement\" (must be 1-" + LeaderboardManager.MAX_POSITIONS
                    + ") - rendering it blank.");
            return buildBlankPane();
        }
        String lbMode = itemSection.getString("lbmode", "self");
        String resolvedMode = (lbMode == null || lbMode.isBlank() || "self".equalsIgnoreCase(lbMode.trim()))
                ? mode
                : lbMode.trim();
        return buildPositionItem(position, type, resolvedMode);
    }

    private ItemStack buildPositionItem(int position, LeaderboardType type, String mode) {
        LeaderboardEntry entry = plugin.getLeaderboardManager().getEntryAtPosition(type, mode, position);
        if (entry == null) {
            return buildEmptyPositionItem(position);
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            OfflinePlayer owner = entry.getPlayerUuid() != null
                    ? Bukkit.getOfflinePlayer(entry.getPlayerUuid())
                    : Bukkit.getOfflinePlayer(entry.getPlayerName());
            // setOwningPlayer(OfflinePlayer) rather than the deprecated setOwner(String) - see the
            // comment on buildIslandItem() in HotbarManager for exactly why: setOwner(String) can
            // throw for a UUID the server has no cached name for and would silently break the
            // whole menu from opening at all.
            meta.setOwningPlayer(owner);

            String nameColor = entry.getPlayerUuid() != null ? LuckPermsUtil.getRankColor(entry.getPlayerUuid()) : null;
            String coloredName = (nameColor != null ? nameColor : ChatColor.WHITE.toString()) + entry.getPlayerName();
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', medalPrefix(position)) + coloredName);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Time: " + ChatColor.YELLOW + String.format(Locale.ROOT, "%.3f", entry.getScore()) + "s");
            lore.add(ChatColor.GRAY + "Position: " + ChatColor.YELLOW + "#" + position);
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    /**
     * "&6&l#1 ", "&7&l#2 ", "&c&l#3 " for the podium, plain "&e&l#N " for everyone else - gives the
     * top 3 a bit of gold/silver/bronze-ish color distinction on top of their position on the menu.
     */
    private String medalPrefix(int position) {
        return switch (position) {
            case 1 -> "&6&l#1 &r";
            case 2 -> "&7&l#2 &r";
            case 3 -> "&c&l#3 &r";
            default -> "&e&l#" + position + " &r";
        };
    }

    private ItemStack buildEmptyPositionItem(int position) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&8&l#" + position + " &7- Empty"));
            meta.setLore(List.of(
                    ChatColor.DARK_GRAY + "No one has claimed",
                    ChatColor.DARK_GRAY + "this position yet."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildStaticItem(ConfigurationSection itemSection, LeaderboardType type, String mode) {
        String materialName = itemSection.getString("material", null);
        if (materialName == null) {
            materialName = itemSection.getString("item", "STONE");
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            String altMaterial = itemSection.getString("alt-material", null);
            material = altMaterial != null ? Material.matchMaterial(altMaterial) : null;
        }
        if (material == null) {
            material = Material.STONE;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = itemSection.getString("name");
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', applyPlaceholders(name, type, mode)));
            }
            List<String> lore = itemSection.getStringList("lore");
            if (!lore.isEmpty()) {
                List<String> translated = new ArrayList<>();
                for (String line : lore) {
                    translated.add(ChatColor.translateAlternateColorCodes('&', applyPlaceholders(line, type, mode)));
                }
                meta.setLore(translated);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * The dyed glass pane toggle (slot 39): its material switches between "verified-material" and
     * "unverified-material" (configurable, defaulting to lime/yellow) depending on which board is
     * currently displayed - everything else (name/lore) is the normal static-item path, with
     * %type%/%othertype% swapped in.
     */
    private ItemStack buildToggleItem(ConfigurationSection items, LeaderboardType type, String mode) {
        ConfigurationSection sec = items == null ? null : items.getConfigurationSection(String.valueOf(TOGGLE_SLOT));
        String verifiedMaterial = sec != null ? sec.getString("verified-material", "LIME_STAINED_GLASS_PANE") : "LIME_STAINED_GLASS_PANE";
        String unverifiedMaterial = sec != null ? sec.getString("unverified-material", "YELLOW_STAINED_GLASS_PANE") : "YELLOW_STAINED_GLASS_PANE";
        String materialName = type == LeaderboardType.VERIFIED ? verifiedMaterial : unverifiedMaterial;
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = type == LeaderboardType.VERIFIED ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = sec != null ? sec.getString("name", "&eViewing: &f%type%") : "&eViewing: &f%type%";
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', applyPlaceholders(name, type, mode)));
            List<String> lore = sec != null ? sec.getStringList("lore") : List.of();
            if (lore.isEmpty()) {
                lore = List.of("&7Click to switch to the", "&f%othertype% &7leaderboard.");
            }
            List<String> translated = new ArrayList<>();
            for (String line : lore) {
                translated.add(ChatColor.translateAlternateColorCodes('&', applyPlaceholders(line, type, mode)));
            }
            meta.setLore(translated);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildBlankPane() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Supported placeholders (same style as every other menu/lore field in this plugin):
     *   %mode%       - the leaderboard's display name for the mode currently being viewed
     *   %type%       - "Verified" or "Unverified", whichever board is currently showing
     *   %othertype%  - the OTHER board - what clicking the toggle switches to
     */
    private String applyPlaceholders(String text, LeaderboardType type, String mode) {
        if (text == null) {
            return "";
        }
        String modeDisplay = plugin.getLeaderboardManager().getLeaderboardDisplayName(type, mode);
        LeaderboardType other = type == LeaderboardType.VERIFIED ? LeaderboardType.UNVERIFIED : LeaderboardType.VERIFIED;
        return text.replace("%mode%", modeDisplay == null || modeDisplay.isBlank() ? "None" : modeDisplay)
                .replace("%type%", type.getLabel())
                .replace("%othertype%", other.getLabel());
    }

    // ------------------------------------------------------------------
    // Click handling
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory() == null
                || !(event.getView().getTopInventory().getHolder() instanceof LeaderboardMenuHolder)) {
            return;
        }
        // Purely a display/navigation menu - nothing can ever be taken out of it or put into it.
        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getRawSlot();
        LeaderboardType type = openType.getOrDefault(player.getUniqueId(), LeaderboardType.UNVERIFIED);
        String mode = openMode.getOrDefault(player.getUniqueId(), resolveDefaultMode());

        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }

        if (slot == TOGGLE_SLOT) {
            if (!plugin.getPermissionsManager().hasActionPermission(player, "leaderboard_toggle")) {
                return;
            }
            LeaderboardType newType = type == LeaderboardType.VERIFIED ? LeaderboardType.UNVERIFIED : LeaderboardType.VERIFIED;
            try {
                fillMenu(event.getView().getTopInventory(), newType, mode);
            } catch (Throwable ex) {
                plugin.getLogger().warning("[SkepiFB] Failed to refresh the leaderboard menu after a toggle for "
                        + player.getName() + ": " + ex);
                return;
            }
            openType.put(player.getUniqueId(), newType);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof LeaderboardMenuHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof LeaderboardMenuHolder
                && event.getPlayer() instanceof Player player) {
            openType.remove(player.getUniqueId());
            openMode.remove(player.getUniqueId());
        }
    }

    // ------------------------------------------------------------------
    // Default file
    // ------------------------------------------------------------------

    private void createDefaultIfMissing() {
        if (menuFile.exists()) {
            return;
        }
        // Podium row (top 3, emphasized): 2nd - 1st - 3rd, framed by two gold accent panes.
        // Rank row (4th-10th, de-emphasized below the podium): exactly 7 slots for exactly 7 spots.
        final int SECOND_SLOT = 12;
        final int FIRST_SLOT = 13;
        final int THIRD_SLOT = 14;
        final int ACCENT_LEFT_SLOT = 11;
        final int ACCENT_RIGHT_SLOT = 15;
        final int[] RANK_4_TO_10_SLOTS = {19, 20, 21, 22, 23, 24, 25};

        StringBuilder content = new StringBuilder();
        content.append("# SkepiFB leaderboard menu configuration file\n")
                .append("#\n")
                .append("# Backs both /lb and /leaderboard: a graphical top 10 leaderboard, built from real player\n")
                .append("# heads, for one mode at a time. Completely separate from \"/fb lb add/remove/list/user\"\n")
                .append("# (see LeaderboardManager) - this only ever reads that same data to render a menu, it never\n")
                .append("# writes to it.\n")
                .append("#\n")
                .append("# Uses the exact same \"items:\" format (material/alt-material/name/lore/action) as every\n")
                .append("# other menu in this plugin (menu.yml, menus/stats_menu.yml, shop.yml) for its border and\n")
                .append("# static buttons. Recognized actions:\n")
                .append("#   close_menu             - closes the inventory (slot 40 by default)\n")
                .append("#   leaderboard_toggle      - switches the menu between the verified/unverified boards\n")
                .append("#                             (slot 39 by default; see verified-material/unverified-\n")
                .append("#                             material below - this action ALWAYS lives on that slot)\n")
                .append("#   leaderboard              - a player-head slot showing one leaderboard position. Fully\n")
                .append("#                             configurable: put this action on ANY slot(s) you want, as\n")
                .append("#                             many or as few as you like, with two extra fields:\n")
                .append("#                               lbplacement: <1-10>  which position this slot shows\n")
                .append("#                               lbmode: \"self\"       follow whichever mode/board is\n")
                .append("#                                                     currently being viewed (the normal\n")
                .append("#                                                     case) - or give an explicit mode\n")
                .append("#                                                     name instead to always pin this\n")
                .append("#                                                     slot to that one mode regardless\n")
                .append("#                             An unoccupied position simply renders as a BARRIER.\n")
                .append("#\n")
                .append("# mode - which mode/arena's leaderboard \"/lb\"/\"/leaderboard\" show with no argument, for a\n")
                .append("#        player who isn't currently in an arena (a player who IS in one always sees that\n")
                .append("#        arena's leaderboard first). Leave blank to automatically use the first mode with\n")
                .append("#        any unverified entries, then the first with any verified entries, then simply the\n")
                .append("#        first known arena - so this works with zero setup. \"/lb <mode>\" always overrides\n")
                .append("#        this and opens that mode directly, case-insensitively.\n")
                .append("mode: \"\"\n")
                .append("\n")
                .append("# Which board the menu opens showing by default (\"verified\" or \"unverified\"). Players can\n")
                .append("# freely toggle between the two with the glass pane button - this only controls what they\n")
                .append("# see the instant the menu opens. Per the spec this ships as \"unverified\".\n")
                .append("default-type: \"unverified\"\n")
                .append("\n")
                .append("# Placeholders available in title/name/lore anywhere in this file: %mode%, %type%\n")
                .append("# (\"Verified\"/\"Unverified\", whichever board is currently showing), %othertype% (the other\n")
                .append("# one - what the toggle switches to).\n")
                .append("title: \"&6&lLeaderboard &8\u00bb &e%mode% &7(%type%)\"\n")
                .append("size: 45\n")
                .append("\n")
                .append("items:\n")
                .append("  ").append(TITLE_SLOT).append(":\n")
                .append("    material: PLAYER_HEAD\n")
                .append("    name: \"&6&lTop 10 &7- &e%mode%\"\n")
                .append("    lore:\n")
                .append("      - \"&7Showing the &f%type% &7leaderboard.\"\n")
                .append("      - \"&7Toggle below to switch boards.\"\n")
                .append("  # Podium: the top 3 positions, front and center, framed by a gold accent on each side.\n")
                .append("  ").append(ACCENT_LEFT_SLOT).append(":\n")
                .append("    material: YELLOW_STAINED_GLASS_PANE\n")
                .append("    name: \"&6&l\u2605\"\n")
                .append("  ").append(SECOND_SLOT).append(":\n")
                .append("    action: leaderboard\n")
                .append("    lbmode: \"self\"\n")
                .append("    lbplacement: 2\n")
                .append("  ").append(FIRST_SLOT).append(":\n")
                .append("    action: leaderboard\n")
                .append("    lbmode: \"self\"\n")
                .append("    lbplacement: 1\n")
                .append("  ").append(THIRD_SLOT).append(":\n")
                .append("    action: leaderboard\n")
                .append("    lbmode: \"self\"\n")
                .append("    lbplacement: 3\n")
                .append("  ").append(ACCENT_RIGHT_SLOT).append(":\n")
                .append("    material: YELLOW_STAINED_GLASS_PANE\n")
                .append("    name: \"&6&l\u2605\"\n")
                .append("  # 4th-10th place, one row down - kept smaller/plainer than the podium above on purpose.\n");
        for (int i = 0; i < RANK_4_TO_10_SLOTS.length; i++) {
            content.append("  ").append(RANK_4_TO_10_SLOTS[i]).append(":\n")
                    .append("    action: leaderboard\n")
                    .append("    lbmode: \"self\"\n")
                    .append("    lbplacement: ").append(i + 4).append("\n");
        }
        content.append("  ").append(TOGGLE_SLOT).append(":\n")
                .append("    # Dyed glass pane toggle. verified-material/unverified-material pick which material\n")
                .append("    # shows for each state; name/lore use %type%/%othertype% and are shared between both.\n")
                .append("    verified-material: LIME_STAINED_GLASS_PANE\n")
                .append("    unverified-material: YELLOW_STAINED_GLASS_PANE\n")
                .append("    name: \"&eViewing: &f%type%\"\n")
                .append("    lore:\n")
                .append("      - \"&7Click to switch to the\"\n")
                .append("      - \"&f%othertype% &7leaderboard.\"\n")
                .append("    action: leaderboard_toggle\n")
                .append("  ").append(CLOSE_SLOT).append(":\n")
                .append("    material: BARRIER\n")
                .append("    name: \"&cClose Menu\"\n")
                .append("    action: close_menu\n");

        // Plain gray glass pane fills every slot in the 45-slot shell that isn't one of the special
        // buttons or leaderboard-entry slots declared above - covers both the actual border AND the
        // interior slots the layout doesn't use, in one pass, so nothing is ever declared twice and
        // nothing is ever missed. Same bordered-shell look as every other built-in menu (island_menu,
        // stats_menu.yml, etc), just computed instead of hand-listed.
        Set<Integer> reserved = new HashSet<>(Set.of(TITLE_SLOT, TOGGLE_SLOT, CLOSE_SLOT,
                ACCENT_LEFT_SLOT, SECOND_SLOT, FIRST_SLOT, THIRD_SLOT, ACCENT_RIGHT_SLOT));
        for (int slot : RANK_4_TO_10_SLOTS) {
            reserved.add(slot);
        }
        for (int slot = 0; slot < DEFAULT_MENU_SIZE; slot++) {
            if (reserved.contains(slot)) {
                continue;
            }
            content.append("  ").append(slot).append(":\n")
                    .append("    material: GRAY_STAINED_GLASS_PANE\n")
                    .append("    name: \"&7\"\n");
        }

        try {
            Files.write(menuFile.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default leaderboard_menu.yml: " + ex.getMessage());
        }
    }

    public static final class LeaderboardMenuHolder implements InventoryHolder {
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
