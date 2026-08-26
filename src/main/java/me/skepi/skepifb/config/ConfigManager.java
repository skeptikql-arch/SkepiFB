package me.skepi.skepifb.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConfigManager {

    private static final int DEFAULT_REFILL_BLOCK_THRESHOLD = 8;

    public static String normalizeConfigKey(String rawValue) {
        if (rawValue == null) {
            return "default";
        }
        String trimmed = rawValue.trim();
        if (trimmed.isBlank()) {
            return "default";
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "default" : normalized;
    }
    private static final double DEFAULT_FINISH_HORIZONTAL_SHRINK = 0.3;
    private static final double DEFAULT_FINISH_JUMP_PLATES = 3.0;
    private static final int DEFAULT_FINISH_REWARD_PERSONAL_BEST = 50;
    private static final int DEFAULT_FINISH_REWARD_NORMAL = 30;
    private static final String DEFAULT_ARENA_START_MODE = "BLOCK";
    private static final String DEFAULT_ARENA_FINISH_MODE = "PLATE";

    private final JavaPlugin plugin;
    private final File configFile;
    private final File menuConfigFile;
    private final File arenaSettingsFile;
    private YamlConfiguration configuration;
    private YamlConfiguration menuConfiguration;
    private YamlConfiguration arenaSettingsConfiguration;
    private boolean arenaSettingsLoaded;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.configFile = new File(dataFolder, "config.yml");
        this.menuConfigFile = new File(dataFolder, "menu.yml");
        this.arenaSettingsFile = new File(dataFolder, "arena_settings.yml");
        createDefaultConfigIfMissing();
        // THE ACTUAL BUG behind "settings menu not configured" / mode changer missing / island
        // switcher empty after deleting menu.yml: createDefaultMenuConfigIfMissing() used to only
        // ever be called from inside createDefaultConfigIfMissing(), which returns immediately if
        // config.yml already exists - so deleting menu.yml on its own (config.yml still present,
        // the normal case) never regenerated it at all, and menuConfiguration below loaded a
        // completely empty file. Calling it here, independently, with its own "does menu.yml
        // exist" guard (already in the method), means menu.yml regenerates whenever IT is missing,
        // regardless of whether config.yml is.
        createDefaultMenuConfigIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(configFile);
        this.menuConfiguration = YamlConfiguration.loadConfiguration(menuConfigFile);
        this.arenaSettingsConfiguration = loadArenaSettingsConfiguration();
        ensureCosmeticsShopMenuEntry();
        ensurePracticeModeMenuEntry();
        ensureLeaveConfirmationMenuEntry();
        ensureArenaSettingsFile();
        ensurePracticeBlockHotbarEntry();
    }

    // Self-healing migration for installs whose config.yml predates the practice_block
    // hotbar action being wired up. createDefaultConfigIfMissing() only ever writes the
    // template once, on first-ever startup, so any server that already had a config.yml
    // before this slot existed never received it - the "practice_block" action was fully
    // implemented in HotbarManager (and documented in the hotbar comment above), but no
    // slot in hotbar.items ever actually used it. This adds the missing slot without
    // touching any slot the server owner has already configured.
    private void ensurePracticeBlockHotbarEntry() {
        ConfigurationSection hotbar = configuration.getConfigurationSection("hotbar");
        if (hotbar == null) {
            hotbar = configuration.createSection("hotbar");
        }
        ConfigurationSection items = hotbar.getConfigurationSection("items");
        if (items == null) {
            items = hotbar.createSection("items");
        }

        // If any slot already uses the practice_block action (even if the admin moved it
        // to a different slot than slot2), leave everything untouched.
        for (String slotKey : items.getKeys(false)) {
            ConfigurationSection existing = items.getConfigurationSection(slotKey);
            if (existing != null && "practice_block".equals(existing.getString("action"))) {
                return;
            }
        }

        if (items.contains("slot2")) {
            // slot2 is occupied by something else - don't clobber a customized layout.
            return;
        }

        ConfigurationSection practiceBlockSlot = items.createSection("slot2");
        practiceBlockSlot.set("name", "&fPractice Block");
        practiceBlockSlot.set("material", "WHITE_TERRACOTTA");
        practiceBlockSlot.set("action", "practice_block");

        try {
            configuration.save(configFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save config.yml after adding missing practice_block hotbar slot: " + ex.getMessage());
        }
    }

    private void createDefaultConfigIfMissing() {
        if (configFile.exists()) {
            return;
        }

        String defaultConfig = "# SkepiFB configuration file\n"
                + "#\n"
                + "# Placeholders below work identically everywhere text is shown to a player -\n"
                + "# scoreboard lines, the statboard, finish titles/subtitles, and the actionbar.\n"
                + "#   %timer%         - current attempt timer (seconds, millisecond precision)\n"
                + "#   %time%          - finish time in finish messages; same as %timer% elsewhere\n"
                + "#   %blocks%        - blocks placed in the current attempt\n"
                + "#   %coins%         - total coins accumulated\n"
                + "#   %receivedcoins% - coins awarded for the run that just finished (finish messages only, 0 elsewhere)\n"
                + "#   %attempts%      - total attempts in the current arena\n"
                + "#   %pb%            - personal best for current arena (N/A if none)\n"
                + "#   %pbdiff%        - difference between the current/finish time and personal best (colored +/-)\n"
                + "#   %speed%         - current movement speed in blocks/second\n"
                + "#   %averagespeed%  - average movement speed for the current/last attempt\n"
                + "#   %xp%            - total XP\n"
                + "#   %level%         - current level (derived from XP)\n"
                + "#   %next_level_xp% - XP required to reach the next level\n"
                + "#   %bossbar_percent% - progress toward next level as a percent, for boss bar fill\n"
                + "#   %player%        - the player's name\n"
                + "#   %mode%          - \"Practice\" or the current arena name\n"
                + "#   %top%           - percentile rank on the current arena's leaderboard (N/A if none)\n"
                + "#   %averagetime%   - average completion time for the current arena (N/A if none)\n"
                + "#   %completions%   - total completions in the current arena (N/A if none)\n"
                + "#   %sessiontop1% .. %sessiontop5% - the top 5 session-best entries for the current arena\n"
                + "#\n"
                + "# block-refill-threshold: when any hotbar slot with action 'block'\n"
                + "# reaches this amount or less, it will automatically be refilled to a full stack.\n"
                + "block-refill-threshold: " + DEFAULT_REFILL_BLOCK_THRESHOLD + "\n"
                + "\n"
                + "# Session top configuration (runtime-only per-arena)\n"
                + "# session-top-empty: Text shown when a slot is empty (supports color codes)\n"
                + "# session-top-format: Format for an entry, use %player% and %time%\n"
                + "session-top-empty: \"&7-.--\"\n"
                + "session-top-format: \"%player% : &e%time%\"\n"
                + "\n"
                + "# Default arena to auto-join on player connect (leave empty to use first arena)\n"
                + "default-arena: \"\"\n"
                + "\n"
                + "# Finish detection configuration\n"
                + "# horizontal-shrink: shrink the trigger area on each side of the pressure plate.\n"
                + "#   0.0 uses the full plate. 0.3 shrinks the detectable area from 0.0-1.0 to 0.3-0.7.\n"
                + "# jump-plates: how many blocks above the plate the player may be while still triggering.\n"
                + "#   Example: a value of 3 allows plates at Y=73 to trigger a player at Y=74,75,76.\n"
                + "finish-detection:\n"
                + "  horizontal-shrink: 0.3\n"
                + "  jump-plates: 3.0\n"
                + "\n"
                + "# Finish title and subtitle displayed when the player completes the course.\n"
                + "# Supports every placeholder listed above.\n"
                + "finish-title:\n"
                + "  title: \"&bTime: &e%time%\"\n"
                + "  subtitle: \"&6%receivedcoins% Coins\"\n"
                + "\n"
                + "# Actionbar displayed while the player is actively running an attempt.\n"
                + "# Supports every placeholder listed above.\n"
                + "actionbar:\n"
                + "  format: \"&bCurrent Speed: &3%speed% m/s\"\n"
                + "\n"
                + "# Replay actionbar displayed while viewing a replay.\n"
                + "# Available placeholders: %tick%, %max_tick%, %seconds%, %time%\n"
                + "replay:\n"
                + "  actionbar:\n"
                + "    format: \"&bReplay %tick%/%max_tick% &8- &e%time%s\"\n"
                + "  hotbar:\n"
                + "    previous:\n"
                + "      material: BLAZE_ROD\n"
                + "      name: \"&bPrevious Tick\"\n"
                + "    next:\n"
                + "      material: BLAZE_ROD\n"
                + "      name: \"&bNext Tick\"\n"
                + "    seek:\n"
                + "      backward:\n"
                + "        material: SPECTRAL_ARROW\n"
                + "        name: \"&bRewind 3s\"\n"
                + "      forward:\n"
                + "        material: SPECTRAL_ARROW\n"
                + "        name: \"&bForward 3s\"\n"
                + "\n"
                + "# Finish rewards used for personal best and normal finishes.\n"
                + "finish-rewards:\n"
                + "  personal-best: 50\n"
                + "  normal-finish: 30\n"
                + "\n"
                + "# Chat messages shown after a finish. Exactly 9 lines.\n"
                + "# Use \"none\" to suppress a line, and \"\" for an empty blank line.\n"
                + "finish-messages:\n"
                + "  personal-best:\n"
                + "    - \"&8------------------\"\n"
                + "    - \"\"\n"
                + "    - \"&d&lCOURSE FINISHED!\"\n"
                + "    - \"&aFinished in: &e%time% &8| %pbdiff%\"\n"
                + "    - \"&aAverage Speed: &e%speed% m/s\"\n"
                + "    - \"&a&lNEW PERSONAL BEST!\"\n"
                + "    - \"\"\n"
                + "    - \"&8------------------\"\n"
                + "    - \"none\"\n"
                + "  normal-finish:\n"
                + "    - \"&8------------------\"\n"
                + "    - \"\"\n"
                + "    - \"&d&lCOURSE FINISHED!\"\n"
                + "    - \"&aFinished in: &e%time% &8| %pbdiff%\"\n"
                + "    - \"&aAverage Speed: &e%speed% m/s\"\n"
                + "    - \"\"\n"
                + "    - \"&8------------------\"\n"
                + "    - \"none\"\n"
                + "    - \"none\"\n"
                + "\n"
                + "# Configurable chat placeholders that players can type in chat.\n"
                + "# Each placeholder key is replaced by the configured value before any other placeholder expansion.\n"
                + "chat-placeholders:\n"
                + "  placeholders:\n"
                + "    \":)\": \"&a☺\"\n"
                + "    \":(\": \"&c☹\"\n"
                + "    \"<3\": \"&c♥\"\n"
                + "    \"o/\": \"&eヽ(o.^)/\"\n"
                + "    \"123\": \"&a1&e2&c3\"\n"
                + "    \"[pb]\": \"&b&l%mode%: &e%pb%\"\n"
                + "    \"[coins]\": \"&6&lCoins: &e%coins%\"\n"
                + "    \"[top]\": \"&a&l%mode% Percentile: &e%top%\"\n"
                + "\n"
                + "# Practice mode configuration\n"
                + "# practice-mode.block-material: the block type used for free-form practice building.\n"
                + "practice-mode:\n"
                + "  block-material: WHITE_TERRACOTTA\n"
                + "\n"
                + "# Hotbar configuration\n"
                + "# Each item has: material, name (with color codes), and action\n"
                + "# Actions: none, block, practice_block, respawn, island_menu, replays_menu, settings_menu, leave, toggle_practice_mode\n"
                + "hotbar:\n"
                + "  items:\n"
                + "    slot0:\n"
                + "      name: \"&eBlock Item\"\n"
                + "      material: SANDSTONE\n"
                + "      action: block\n"
                + "    slot1:\n"
                + "      name: \"&ePickaxe\"\n"
                + "      material: WOODEN_PICKAXE\n"
                + "      action: tool\n"
                + "    slot2:\n"
                + "      name: \"&fPractice Block\"\n"
                + "      material: WHITE_TERRACOTTA\n"
                + "      action: practice_block\n"
                + "    slot3:\n"
                + "      name: \"&cRespawn Bed\"\n"
                + "      material: RED_BED\n"
                + "      action: respawn\n"
                + "    slot4:\n"
                + "      name: \"&eIslands\"\n"
                + "      material: FIREWORK_STAR\n"
                + "      action: island_menu\n"
                + "    slot6:\n"
                + "      name: \"&5Replays\"\n"
                + "      material: BOOK\n"
                + "      action: replays_menu\n"
                + "    slot7:\n"
                + "      name: \"&aSettings\"\n"
                + "      material: EMERALD\n"
                + "      action: settings_menu\n"
                + "    slot8:\n"
                + "      name: \"&9Leave\"\n"
                + "      material: ENDER_EYE\n"
                + "      action: leave\n"
                + "replay:\n"
                + "  restore-per-tick: 100\n"
                + "  hotbar:\n"
                + "    previous:\n"
                + "      material: STICK\n"
                + "      name: \"&bPrevious Tick\"\n"
                + "    toggle:\n"
                + "      running-material: RED_DYE\n"
                + "      running-name: \"&cPause Replay\"\n"
                + "      paused-material: LIME_DYE\n"
                + "      paused-name: \"&aStart Replay\"\n"
                + "    next:\n"
                + "      material: STICK\n"
                + "      name: \"&bNext Tick\"\n"
                + "\n"
                + "# Level-up bossbar configuration (the boss bar shown while playing that fills up as you\n"
                + "# earn XP toward your next level).\n"
                + "#   enabled      - true/false to show or completely disable the bossbar\n"
                + "#   style        - a BossBar style: SOLID, SEGMENTED_6, SEGMENTED_10, SEGMENTED_12, SEGMENTED_20\n"
                + "#   title-format - supports every placeholder listed at the top of this file, plus\n"
                + "#                  %level_color% (the current rank's chat color, from the colors section below)\n"
                + "#   colors       - the bar (and %level_color%) color for each level, 1 through 7. Valid\n"
                + "#                  colors: PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE\n"
                + "bossbar:\n"
                + "  enabled: true\n"
                + "  style: SOLID\n"
                + "  title-format: \"%level_color%&lLevel %level% &8| %level_color%%bossbar_percent%% &e(%xp%/%next_level_xp% XP)\"\n"
                + "  colors:\n"
                + "    level-1: GREEN\n"
                + "    level-2: YELLOW\n"
                + "    level-3: WHITE\n"
                + "    level-4: WHITE\n"
                + "    level-5: YELLOW\n"
                + "    level-6: BLUE\n"
                + "    level-7: PURPLE\n"
                + "\n"
                + "# /fb help output. One line per list entry, & color codes supported.\n"
                + "help:\n"
                + "  message:\n"
                + "    - \"&6SkepiFB Commands\"\n"
                + "    - \"&7Staff Commands\"\n"
                + "    - \"&f/fb help &7- Shows this menu\"\n"
                + "    - \"&f/fb add <arena> <schematic> <islandCount> [spacing] [straight|diagonal] &7- Adds a new arena\"\n"
                + "    - \"&f/fb remove <arena> &7- Removes an arena\"\n"
                + "    - \"&f/fb setfacing <arena> &7- Sets the spawn/respawn facing direction for an arena to your current facing\"\n"
                + "    - \"&f/fb list &7- Lists arenas\"\n"
                + "    - \"&f/fb join <arena> &7- Joins a FastBuilder arena\"\n"
                + "    - \"&f/fb leave &7- Leaves your current arena\"\n"
                + "    - \"&f/fb menu <add|remove|open|list> ... &7- Manages custom menus\"\n"
                + "    - \"&f/fb reload &7- Reloads plugin files\"\n"
                + "    - \"&f/fb replayinfo &7- Shows replay information\"\n"
                + "    - \"&f/fb replays view <player> &7- Views another player's replays\"\n"
                + "    - \"&f/fb coins add/remove/set <player> <amount> &7- Edits player coins\"\n"
                + "    - \"&f/fb xp add/remove/set <player> <amount> &7- Edits player XP and updates rank UI\"\n"
                + "    - \"&f/fb lb add <user> <mode> <time> &7- Adds/updates a leaderboard entry\"\n"
                + "    - \"&f/fb lb remove <user> <mode> <time> &7- Removes a leaderboard entry\"\n"
                + "    - \"&f/fb lb list <mode> &7- Shows a mode's leaderboard\"\n"
                + "    - \"&f/fb lb user <mode> <player> <position> <score> &7- Manually sets a position\"\n"
                + "    - \"&f/fb island add/remove/list ... &7- Manages island cosmetics\"\n"
                + "    - \"&f/fb test setspawn/start/exit &7- Runs test mode commands\"\n"
                + "\n"
                + "# Items a brand-new player automatically owns AND has equipped in each shop, the very\n"
                + "# first time they join (existing players are never touched by this). Use the exact text\n"
                + "# \"default\" to just use whichever item in that shop is already marked \"default: true\" in\n"
                + "# shop.yml - this is what every shop below does unless you change it, so a fresh install\n"
                + "# behaves exactly like before. To give new players something else instead, use\n"
                + "# \"<category_key>:<item_key>\" exactly as they appear in shop.yml, e.g. \"stone:granite\".\n"
                + "default-shop-selections:\n"
                + "  block_shop: \"default\"\n"
                + "  tools_shop: \"default\"\n"
                + "  reset_animation: \"default\"\n"
                + "  firework_color: \"default\"\n"
                + "  practice_shop: \"default\"\n"
                + "  island_shop: \"default\"\n"
                + "\n"
                + "# Statboard configuration (holograms above island spawns)\n"
                + "statboard:\n"
                + "  enabled: true\n"
                + "\n"
                + "  offset:\n"
                + "    right: 2.0\n"
                + "    forward: 1.0\n"
                + "    up: 1.0\n"
                + "\n"
                + "  lines:\n"
                + "    - \"%player%&f's Statboard\"\n"
                + "    - \"&e&lBridging Statistics &7- &e%mode%\"\n"
                + "    - \"&bPersonal Best &7- &e%pb% &7[&bTop &e%top%&7]\"\n"
                + "    - \"&bAverage Time &7- &e%averagetime%\"\n"
                + "    - \"&bCompletions &7- &e%completions%\"\n"
                + "    - \"&bAttempts &7- &e%attempts%\"\n";

        // Statistic reset defaults
        defaultConfig += "\n# Statistic reset configuration\n";
        defaultConfig += "statistic-reset:\n";
        defaultConfig += "  cost: 75\n";
        defaultConfig += "  insufficient-coins: \"&cYou need %cost% coins to reset your stats.\"\n";
        defaultConfig += "  confirm-material: LIME_TERRACOTTA\n";
        defaultConfig += "  cancel-material: RED_TERRACOTTA\n";
        defaultConfig += "  confirm-name: \"&aConfirm Reset\"\n";
        defaultConfig += "  cancel-name: \"&cCancel\"\n";
        defaultConfig += "  confirm-slot: 11\n";
        defaultConfig += "  cancel-slot: 15\n";
        defaultConfig += "\n# Spawn position messages\n";
        defaultConfig += "spawn-position:\n";
        defaultConfig += "  set-success: \"&aTemporary spawn set.\"\n";
        defaultConfig += "  reset-success: \"&aTemporary spawn cleared.\"\n";
        defaultConfig += "  cannot-set-while-running: \"&cCannot set spawn while an attempt is active.\"\n";

        try {
            Files.write(configFile.toPath(), defaultConfig.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default config.yml: " + ex.getMessage());
        }
    }

    private void createDefaultMenuConfigIfMissing() {
        if (menuConfigFile.exists()) {
            return;
        }

        String defaultMenuConfig = "# SkepiFB menu configuration file\n"
                + "#\n"
                + "# Defines every non-shop GUI menu the plugin uses (island browser, mode changer,\n"
                + "# fastbuilder settings, the cosmetics hub, replay controls, etc). Shop GUIs (block\n"
                + "# shop, tools, reset animation, firework color, practice blocks, rankups, island\n"
                + "# shop, tags) live in shop.yml instead.\n"
                + "#\n"
                + "# Each top-level key is one menu, e.g. \"island_menu:\". Its fields:\n"
                + "#   title - inventory title shown at the top of the GUI (supports & color codes)\n"
                + "#   size  - inventory size, must be a multiple of 9 (9/18/27/36/45/54)\n"
                + "#   items - a map of slot number -> item definition\n"
                + "#\n"
                + "# Each item definition supports:\n"
                + "#   material     - the item's Bukkit Material (e.g. ARROW, BARRIER, FIREWORK_STAR)\n"
                + "#   alt-material - optional fallback material used if the primary one is\n"
                + "#                  unavailable on the server's Minecraft version\n"
                + "#   name         - display name (supports & color codes)\n"
                + "#   lore         - list of lore lines (supports & color codes), or [] for none\n"
                + "#   action       - what clicking this item does; recognized actions include:\n"
                + "#                    close_menu              - closes the inventory\n"
                + "#                    previous_page/next_page - paginate the current menu\n"
                + "#                    menu                    - opens another menu (pair with a \"menu:\" key\n"
                + "#                                              naming the target menu, e.g. cosmetics_menu)\n"
                + "#                    mode_changer_menu       - opens the mode changer menu\n"
                + "#                    fastbuilder_settings_menu - opens the FastBuilder settings menu\n"
                + "#                    toggle_practice_mode    - toggles practice mode for the player\n"
                + "#                    cosmetic_none           - clears the player's equipped cosmetic\n"
                + "#                    island_1, island_2, ...  - selects that numbered island\n"
                + "#                    mode                    - switches the player into another arena/mode; pair\n"
                + "#                                              with a \"mode:\" key naming the target arena exactly\n"
                + "#                                              as it appears in arenas.yml (works in ANY menu,\n"
                + "#                                              including mode_changer_menu, fastbuilder_settings_menu\n"
                + "#                                              and cosmetics_menu - every slot in every menu uses the\n"
                + "#                                              same plain slot numbering, there is no special/shifted\n"
                + "#                                              addressing anywhere)\n"
                + "#   slot         - only used by a small number of special items (e.g. paginated\n"
                + "#                  category selectors) to force a fixed position independent of the\n"
                + "#                  surrounding map key; most items just use their map key as the slot\n"
                + "#\n"
                + "# mode_changer_menu, fastbuilder_settings_menu and cosmetics_menu ship as an empty bordered\n"
                + "# template (slots 0-9, 17, 18, 26, 27, 35, 36, 44-53 are the border) - add your own items to\n"
                + "# the free interior slots (10-16, 19-25, 28-34, 37-43) to make them do anything. For example,\n"
                + "# to add a button in mode_changer_menu that switches the player into an arena named \"Ranked\":\n"
                + "#   mode_changer_menu:\n"
                + "#     items:\n"
                + "#       10:\n"
                + "#         material: EMERALD\n"
                + "#         name: \"&aRanked\"\n"
                + "#         action: mode\n"
                + "#         mode: \"Ranked\"\n"
                + "#\n"
                + "island_menu:\n"
                + "  title: \"&eIslands\"\n"
                + "  size: 45\n"
                + "  items:\n"
                + "    4:\n"
                + "      material: FIREWORK_STAR\n"
                + "      name: \"&eIslands\"\n"
                + "      lore: []\n"
                + "    38:\n"
                + "      material: ARROW\n"
                + "      alt-material: STICK\n"
                + "      name: \"&7Previous Page\"\n"
                + "      action: previous_page\n"
                + "    40:\n"
                + "      material: BARRIER\n"
                + "      name: \"&cClose Menu\"\n"
                + "      action: close_menu\n"
                + "    42:\n"
                + "      material: ARROW\n"
                + "      alt-material: STICK\n"
                + "      name: \"&7Next Page\"\n"
                + "      action: next_page\n"
                + "    10:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 1\"\n"
                + "      action: island_1\n"
                + "    11:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 2\"\n"
                + "      action: island_2\n"
                + "    12:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 3\"\n"
                + "      action: island_3\n"
                + "    13:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 4\"\n"
                + "      action: island_4\n"
                + "    14:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 5\"\n"
                + "      action: island_5\n"
                + "    15:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 6\"\n"
                + "      action: island_6\n"
                + "    16:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 7\"\n"
                + "      action: island_7\n"
                + "    19:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 8\"\n"
                + "      action: island_8\n"
                + "    20:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 9\"\n"
                + "      action: island_9\n"
                + "    21:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 10\"\n"
                + "      action: island_10\n"
                + "    22:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 11\"\n"
                + "      action: island_11\n"
                + "    23:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 12\"\n"
                + "      action: island_12\n"
                + "    24:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 13\"\n"
                + "      action: island_13\n"
                + "    25:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 14\"\n"
                + "      action: island_14\n"
                + "    28:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 15\"\n"
                + "      action: island_15\n"
                + "    29:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 16\"\n"
                + "      action: island_16\n"
                + "    30:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 17\"\n"
                + "      action: island_17\n"
                + "    31:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 18\"\n"
                + "      action: island_18\n"
                + "    32:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 19\"\n"
                + "      action: island_19\n"
                + "    33:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 20\"\n"
                + "      action: island_20\n"
                + "    34:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"&aIsland 21\"\n"
                + "      action: island_21\n"
                + "\n"
                + "settings_menu:\n"
                + "  title: \"&aSettings\"\n"
                + "  size: 27\n"
                + "  items:\n"
                + "    0:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    1:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    2:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    3:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    4:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    5:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    6:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    7:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    8:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    9:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    10:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    11:\n"
                + "      material: CLOCK\n"
                + "      name: \"&eMode Switcher\"\n"
                + "      action: mode_changer_menu\n"
                + "    12:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    13:\n"
                + "      material: SANDSTONE_STAIRS\n"
                + "      name: \"&6Fastbuilder Settings\"\n"
                + "      action: fastbuilder_settings_menu\n"
                + "    14:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    15:\n"
                + "      material: ENDER_CHEST\n"
                + "      name: \"&dCosmetics\"\n"
                + "      action: menu\n"
                + "      menu: cosmetics_menu\n"
                + "    16:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    17:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    18:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    19:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    20:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    21:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    22:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    23:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    24:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    25:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    26:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "\n"
                + "mode_changer_menu:\n"
                + "  title: \"&eMode Changer\"\n"
                + "  size: 54\n"
                + "  items:\n"
                + "    0:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    1:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    2:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    3:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    4:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    5:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    6:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    7:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    8:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    9:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    17:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    18:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    26:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    27:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    35:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    36:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    44:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    45:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    46:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    47:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    48:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    49:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    50:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    51:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    52:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    53:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "\n"
                + "fastbuilder_settings_menu:\n"
                + "  title: \"&6Fastbuilder Settings\"\n"
                + "  size: 54\n"
                + "  items:\n"
                + "    0:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    1:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    2:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    3:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    4:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    5:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    6:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    7:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    8:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    9:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    17:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    18:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    26:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    27:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    35:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    36:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    44:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    45:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    46:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    47:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    48:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    49:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    50:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    51:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    52:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    53:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "\n"
                + "cosmetics_menu:\n"
                + "  title: \"&dCosmetics\"\n"
                + "  size: 54\n"
                + "  items:\n"
                + "    0:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    1:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    2:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    3:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    4:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    5:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    6:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    7:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    8:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    9:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    17:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    18:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    26:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    27:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    35:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    36:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    44:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    45:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    46:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    47:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    48:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    49:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    50:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    51:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    52:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    53:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "\n"
                + "replay_menu:\n"
                + "  title: \"&6Replays\"\n"
                + "  size: 54\n"
                + "  items:\n"
                + "    0:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    1:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    2:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    3:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    4:\n"
                + "      material: BOOK\n"
                + "      name: \"&6Replay Header\"\n"
                + "      lore:\n"
                + "        - \"&7Browse saved runs\"\n"
                + "    5:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    6:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    7:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    8:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7\"\n"
                + "    10:\n"
                + "      material: FILLED_MAP\n"
                + "      name: \"&6Last Failed Attempt\"\n"
                + "\n"
                + "block_shop:\n"
                + "  title: \"&dBlock Shop\"\n"
                + "  size: 54\n"
                + "  items: {}\n";

        try {
            Files.write(menuConfigFile.toPath(), defaultMenuConfig.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default menu.yml: " + ex.getMessage());
        }
    }

    private void ensureCosmeticsShopMenuEntry() {
        ConfigurationSection cosmeticsMenu = menuConfiguration.getConfigurationSection("cosmetics_menu");
        if (cosmeticsMenu == null) {
            cosmeticsMenu = menuConfiguration.createSection("cosmetics_menu");
            cosmeticsMenu.set("title", "&dCosmetics");
            cosmeticsMenu.set("size", 54);
        }

        ConfigurationSection itemsSection = cosmeticsMenu.getConfigurationSection("items");
        if (itemsSection == null) {
            itemsSection = cosmeticsMenu.createSection("items");
        }

        boolean modified = false;

        // Migrate any existing cosmetics_menu items that still point at the old,
        // retired shop IDs. This runs unconditionally (unlike the "!contains(slot)"
        // guards below) so servers that already had a cosmetics_menu section from
        // before the rename get their stale "menu"/"action" values corrected instead
        // of being skipped because the slot already exists.
        for (String key : new ArrayList<>(itemsSection.getKeys(false))) {
            ConfigurationSection item = itemsSection.getConfigurationSection(key);
            if (item == null) continue;

            String menuValue = item.getString("menu");
            if ("animations".equalsIgnoreCase(menuValue)) {
                item.set("menu", "reset_animation");
                modified = true;
            } else if ("banner_shop".equalsIgnoreCase(menuValue)) {
                item.set("menu", "firework_color");
                modified = true;
            } else if ("cosmetics_menu".equalsIgnoreCase(menuValue)) {
                // was never a valid target for itself; leave as-is, not a legacy id.
            }

            String actionValue = item.getString("action");
            if (actionValue != null) {
                if (actionValue.equalsIgnoreCase("shop:animations")) {
                    item.set("action", "shop:reset_animation");
                    modified = true;
                } else if (actionValue.equalsIgnoreCase("shop:banner_shop")) {
                    item.set("action", "shop:firework_color");
                    modified = true;
                }
            }
        }

        // Remove legacy slot 10 if present
        if (itemsSection.contains("10")) {
            itemsSection.set("10", null);
            modified = true;
        }

        // Ensure Block Shop now resides at slot 19
        if (!itemsSection.contains("19")) {
            ConfigurationSection slot19 = itemsSection.createSection("19");
            slot19.set("material", "SANDSTONE");
            slot19.set("name", "&6Block Shop");
            slot19.set("action", "shop:block_shop");
            slot19.set("lore", List.of("&7Browse your block cosmetics"));
            modified = true;
        }

        // Add new cosmetics entries pointing to menu keys (destination menus will be created below)
        if (!itemsSection.contains("21")) {
            ConfigurationSection s = itemsSection.createSection("21");
            s.set("material", "IRON_PICKAXE");
            s.set("name", "&bTools Shop");
            s.set("action", "menu");
            s.set("menu", "tools_shop");
            modified = true;
        }
        if (!itemsSection.contains("23")) {
            ConfigurationSection s = itemsSection.createSection("23");
            s.set("material", "FIREWORK_ROCKET");
            s.set("name", "&eReset Animation");
            s.set("action", "menu");
            s.set("menu", "reset_animation");
            modified = true;
        }
        boolean hasFireworkColorButton;
        {
            final ConfigurationSection itemsSectionRef = itemsSection;
            hasFireworkColorButton = itemsSectionRef.getKeys(false).stream().anyMatch(k -> {
                ConfigurationSection sec = itemsSectionRef.getConfigurationSection(k);
                return sec != null && ("firework_color".equalsIgnoreCase(sec.getString("menu"))
                        || "shop:firework_color".equalsIgnoreCase(sec.getString("action")));
            });
        }
        if (!hasFireworkColorButton) {
            // Slot 25 is the conventional spot, but fall back to the first free
            // slot if something else already occupies it on this install.
            int targetSlot = itemsSection.contains("25") ? -1 : 25;
            if (targetSlot == -1) {
                for (int candidate = 0; candidate < 54; candidate++) {
                    if (!itemsSection.contains(String.valueOf(candidate))) {
                        targetSlot = candidate;
                        break;
                    }
                }
            }
            if (targetSlot == -1) targetSlot = 25; // menu is full; overwrite as last resort
            ConfigurationSection s = itemsSection.createSection(String.valueOf(targetSlot));
            s.set("material", "FIREWORK_STAR");
            s.set("name", "&6Firework Color");
            s.set("action", "menu");
            s.set("menu", "firework_color");
            modified = true;
        }
        // Move practice block shop down one row (to index 37)
        if (itemsSection.contains("28")) {
            ConfigurationSection old = itemsSection.getConfigurationSection("28");
            itemsSection.set("28", null);
            modified = true;
            if (!itemsSection.contains("37")) {
                ConfigurationSection s = itemsSection.createSection("37");
                copySection(old, s);
                modified = true;
            }
        } else if (!itemsSection.contains("37")) {
            ConfigurationSection s = itemsSection.createSection("37");
            s.set("material", "LIGHT_BLUE_TERRACOTTA");
            s.set("name", "&bPractice Block Shop");
            s.set("action", "menu");
            s.set("menu", "practice_shop");
            modified = true;
        }

        // Move islands from 30 -> 39
        if (itemsSection.contains("30")) {
            ConfigurationSection old = itemsSection.getConfigurationSection("30");
            itemsSection.set("30", null);
            modified = true;
            if (!itemsSection.contains("39")) {
                ConfigurationSection s = itemsSection.createSection("39");
                copySection(old, s);
                modified = true;
            }
        } else if (!itemsSection.contains("39")) {
            ConfigurationSection s = itemsSection.createSection("39");
            s.set("material", "GRASS_BLOCK");
            s.set("name", "&aIslands");
            s.set("action", "menu");
            s.set("menu", "island_shop");
            modified = true;
        }

        // Move rankups from 32 -> 41
        if (itemsSection.contains("32")) {
            ConfigurationSection old = itemsSection.getConfigurationSection("32");
            itemsSection.set("32", null);
            modified = true;
            if (!itemsSection.contains("41")) {
                ConfigurationSection s = itemsSection.createSection("41");
                copySection(old, s);
                modified = true;
            }
        } else if (!itemsSection.contains("41")) {
            ConfigurationSection s = itemsSection.createSection("41");
            s.set("material", "LEATHER_HELMET");
            s.set("name", "&cRankups");
            s.set("action", "menu");
            s.set("menu", "rankups");
            s.set("color", "RED");
            modified = true;
        }

        // Move tags from 34 -> 43
        if (itemsSection.contains("34")) {
            ConfigurationSection old = itemsSection.getConfigurationSection("34");
            itemsSection.set("34", null);
            modified = true;
            if (!itemsSection.contains("43")) {
                ConfigurationSection s = itemsSection.createSection("43");
                copySection(old, s);
                modified = true;
            }
        } else if (!itemsSection.contains("43")) {
            ConfigurationSection s = itemsSection.createSection("43");
            s.set("material", "NAME_TAG");
            s.set("name", "&dTags");
            s.set("action", "menu");
            s.set("menu", "tag_shop");
            modified = true;
        }

        // Ensure destination menu sections exist (blank, configurable) with proper default titles
        Map<String, Integer> menuSizes = Map.of(
            "tools_shop", 27,
            "reset_animation", 27,
            "firework_color", 27,
            "practice_shop", 54,
            "island_shop", 54,
            "rankups", 27,
            "tag_shop", 27
        );
        Map<String, String> menuTitles = Map.of(
            "tools_shop", "Tools Shop",
            "reset_animation", "Reset Animation",
            "firework_color", "Firework Color",
            "practice_shop", "Practice Block Shop",
            "island_shop", "Island Shop",
            "rankups", "Rankups",
            "tag_shop", "Tags"
        );

        for (String mk : menuSizes.keySet()) {
            ConfigurationSection ms = menuConfiguration.getConfigurationSection(mk);
            if (ms == null) {
                ms = menuConfiguration.createSection(mk);
                ms.set("title", menuTitles.getOrDefault(mk, mk));
                ms.set("size", menuSizes.get(mk));
                ms.createSection("items");
                modified = true;
            } else {
                // Ensure title uses proper capitalization by default only if not already set
                if (!ms.isSet("title")) {
                    ms.set("title", menuTitles.getOrDefault(mk, mk));
                    modified = true;
                }
                if (!ms.isSet("size")) {
                    ms.set("size", menuSizes.get(mk));
                    modified = true;
                }
                if (!ms.isConfigurationSection("items")) {
                    ms.createSection("items");
                    modified = true;
                }
            }
        }

        if (modified) saveMenuConfiguration();
    }

    private void ensurePracticeModeMenuEntry() {
        ConfigurationSection fastbuilderMenu = menuConfiguration.getConfigurationSection("fastbuilder_settings_menu");
        if (fastbuilderMenu == null) {
            fastbuilderMenu = menuConfiguration.createSection("fastbuilder_settings_menu");
            fastbuilderMenu.set("title", "&6Fastbuilder Settings");
            fastbuilderMenu.set("size", 54);
        }

        ConfigurationSection fastbuilderItems = fastbuilderMenu.getConfigurationSection("items");
        if (fastbuilderItems == null) {
            fastbuilderItems = fastbuilderMenu.createSection("items");
        }

        boolean modified = false;

        // Remove old practice slot if present
        if (fastbuilderItems.contains("10")) {
            fastbuilderItems.set("10", null);
            modified = true;
        }
        // Ensure Practice Mode moved to slot 29 (one slot forward)
        if (fastbuilderItems.contains("28")) {
            ConfigurationSection old = fastbuilderItems.getConfigurationSection("28");
            fastbuilderItems.set("28", null);
            modified = true;
            if (!fastbuilderItems.contains("29")) {
                ConfigurationSection s = fastbuilderItems.createSection("29");
                copySection(old, s);
                modified = true;
            }
        } else if (!fastbuilderItems.contains("29")) {
            ConfigurationSection practiceSlot = fastbuilderItems.createSection("29");
            practiceSlot.set("material", "WHITE_TERRACOTTA");
            practiceSlot.set("name", "&fPractice Mode");
            practiceSlot.set("action", "toggle_practice_mode");
            modified = true;
        }

        // Move Statistic Reset from 30 -> 31
        if (fastbuilderItems.contains("30")) {
            ConfigurationSection old = fastbuilderItems.getConfigurationSection("30");
            fastbuilderItems.set("30", null);
            modified = true;
            if (!fastbuilderItems.contains("31")) {
                ConfigurationSection s = fastbuilderItems.createSection("31");
                copySection(old, s);
                modified = true;
            }
        } else if (!fastbuilderItems.contains("31")) {
            ConfigurationSection stat = fastbuilderItems.createSection("31");
            stat.set("material", "IRON_AXE");
            stat.set("name", "&cStatistic Reset");
            stat.set("action", "menu");
            stat.set("menu", "stat_reset");
            modified = true;
        }

        // Move Spawn Position from 32 -> 33
        if (fastbuilderItems.contains("32")) {
            ConfigurationSection old = fastbuilderItems.getConfigurationSection("32");
            fastbuilderItems.set("32", null);
            modified = true;
            if (!fastbuilderItems.contains("33")) {
                ConfigurationSection s = fastbuilderItems.createSection("33");
                copySection(old, s);
                modified = true;
            }
        } else if (!fastbuilderItems.contains("33")) {
            ConfigurationSection spawn = fastbuilderItems.createSection("33");
            spawn.set("material", "PUFFERFISH");
            spawn.set("name", "&eSpawn Position");
            spawn.set("action", "spawn_position");
            spawn.set("lore", List.of("&7Left click to set spawn", "&7Right click to reset spawn"));
            modified = true;
        }

        ConfigurationSection settingsMenu = menuConfiguration.getConfigurationSection("settings_menu");
        if (settingsMenu != null) {
            ConfigurationSection settingsItems = settingsMenu.getConfigurationSection("items");
            if (settingsItems != null) {
                for (String slotKey : new ArrayList<>(settingsItems.getKeys(false))) {
                    ConfigurationSection itemSection = settingsItems.getConfigurationSection(slotKey);
                    if (itemSection != null && "toggle_practice_mode".equals(itemSection.getString("action"))) {
                        settingsItems.set(slotKey, null);
                        modified = true;
                    }
                }
            }
        }

        // Ensure stat_reset menu exists as a configurable blank menu and populate confirm/cancel
        if (modified) {
            ConfigurationSection stat = menuConfiguration.getConfigurationSection("stat_reset");
            if (stat == null) {
                stat = menuConfiguration.createSection("stat_reset");
                stat.set("title", "&cStatistic Reset");
                stat.set("size", 27);
                ConfigurationSection items = stat.createSection("items");
                // default confirm/cancel slots and items
                int confirmSlot = 11;
                int cancelSlot = 15;
                ConfigurationSection confirm = items.createSection(String.valueOf(confirmSlot));
                confirm.set("material", "LIME_TERRACOTTA");
                confirm.set("name", "&aConfirm Reset");
                confirm.set("action", "stat_reset_confirm");

                ConfigurationSection cancel = items.createSection(String.valueOf(cancelSlot));
                cancel.set("material", "RED_TERRACOTTA");
                cancel.set("name", "&cCancel");
                cancel.set("action", "stat_reset_cancel");
            }
        }

        if (modified) {
            saveMenuConfiguration();
        }
    }

    private void ensureLeaveConfirmationMenuEntry() {
        // Leave confirmation is handled by HotbarManager directly via a dedicated
        // inventory. There is no persisted menu entry to create here, but the
        // constructor still invokes this for symmetry with other menu migration logic.
    }

    public boolean addMenu(String menuKey) {
        if (menuKey == null || menuKey.isBlank()) {
            return false;
        }

        String normalizedKey = normalizeMenuKey(menuKey);
        if (menuExists(normalizedKey)) {
            return false;
        }

        ConfigurationSection menuSection = menuConfiguration.createSection(normalizedKey);
        menuSection.set("title", "&cCustom Menu");
        menuSection.set("size", 54);
        ConfigurationSection items = menuSection.createSection("items");

        // Border line for the sample menu
        int[] borderSlots = new int[]{0,1,2,3,4,5,6,7,8,9,17,18,26,27,35,36,44,45,46,47,48,49,50,51,52,53};
        for (int slot : borderSlots) {
            ConfigurationSection borderItem = items.createSection(String.valueOf(slot));
            borderItem.set("material", "GRAY_STAINED_GLASS_PANE");
            borderItem.set("name", "&7");
        }

        ConfigurationSection mainItem = items.createSection("21");
        mainItem.set("material", "RED_WOOL");
        mainItem.set("name", "&cOpen Page 2");
        mainItem.set("action", "menu");
        mainItem.set("menu", normalizedKey + "_page2");

        ConfigurationSection modeItem = items.createSection("23");
        modeItem.set("material", "JUNGLE_PLANKS");
        modeItem.set("name", "&6Jungle Parkour");
        modeItem.set("action", "mode");
        modeItem.set("mode", "junglepk");

        ConfigurationSection page2 = menuConfiguration.createSection(normalizedKey + "_page2");
        page2.set("title", "&cCustom Menu Page 2");
        page2.set("size", 54);
        ConfigurationSection page2Items = page2.createSection("items");

        for (int slot : borderSlots) {
            ConfigurationSection borderItem = page2Items.createSection(String.valueOf(slot));
            borderItem.set("material", "GRAY_STAINED_GLASS_PANE");
            borderItem.set("name", "&7");
        }

        ConfigurationSection backItem = page2Items.createSection("22");
        backItem.set("material", "BLUE_WOOL");
        backItem.set("name", "&bBack");
        backItem.set("action", "menu");
        backItem.set("menu", normalizedKey);

        saveMenuConfiguration();
        return true;
    }

    private void copySection(ConfigurationSection from, ConfigurationSection to) {
        if (from == null || to == null) return;
        for (String key : from.getKeys(false)) {
            Object val = from.get(key);
            if (val instanceof ConfigurationSection) {
                ConfigurationSection child = to.createSection(key);
                copySection((ConfigurationSection) val, child);
            } else {
                to.set(key, val);
            }
        }
    }

    public int getRefillBlockThreshold() {
        // Backwards-compatible: prefer new key 'block-refill-threshold'
        if (configuration.contains("block-refill-threshold")) {
            return configuration.getInt("block-refill-threshold", DEFAULT_REFILL_BLOCK_THRESHOLD);
        }
        return configuration.getInt("refill-block-threshold", DEFAULT_REFILL_BLOCK_THRESHOLD);
    }

    public double getFinishHorizontalShrink() {
        return configuration.getDouble("finish-detection.horizontal-shrink", DEFAULT_FINISH_HORIZONTAL_SHRINK);
    }

    public double getFinishJumpPlates() {
        if (configuration.contains("finish-detection.jump-plates")) {
            return configuration.getDouble("finish-detection.jump-plates", DEFAULT_FINISH_JUMP_PLATES);
        }
        return configuration.getDouble("finish-detection.jump-height", DEFAULT_FINISH_JUMP_PLATES);
    }

    public String getActionbarFormat() {
        return configuration.getString("actionbar.format", "&bCurrent Speed: &3%speed% m/s");
    }

    public String getReplayActionbarFormat() {
        return configuration.getString("replay.actionbar.format", "&bReplay %tick%/%max_tick% &8- &e%time%s");
    }

    public String getFinishTitle() {
        return configuration.getString("finish-title.title", "&bTime: &e%time%");
    }

    public String getFinishSubtitle() {
        return configuration.getString("finish-title.subtitle", "&6%receivedcoins% Coins");
    }

    public int getFinishRewardPersonalBest() {
        return configuration.getInt("finish-rewards.personal-best", DEFAULT_FINISH_REWARD_PERSONAL_BEST);
    }

    public int getFinishRewardNormal() {
        return configuration.getInt("finish-rewards.normal-finish", DEFAULT_FINISH_REWARD_NORMAL);
    }

    public int getReplayRestorePerTick() {
        return configuration.getInt("replay.restore-per-tick", 100);
    }

    /**
     * LuckPerms prefix priority used for equipped leaderboard-position tags (see TagManager). Not
     * hardcoded because the "right" value depends entirely on this server's own rank/group prefix
     * setup: LuckPerms only ever displays ONE prefix - whichever active prefix (across the player's
     * own direct meta and every group they inherit from) has the HIGHEST priority number - it does
     * not combine/stack multiple prefixes on its own. Set this HIGHER than your rank groups'
     * prefix priorities (check with e.g. "/lp group <name> meta info") if you want an equipped tag
     * to visibly take precedence over rank, or LOWER if you want rank to always win instead.
     * Default of 100 is just a reasonable starting point, not a guarantee it beats every group.
     */
    public int getTagPrefixPriority() {
        return configuration.getInt("tags.prefix-priority", 100);
    }

    public org.bukkit.Material getReplayPreviousTickMaterial() {
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(configuration.getString("replay.hotbar.previous.material", "BLAZE_ROD"));
        return material == null ? org.bukkit.Material.BLAZE_ROD : material;
    }

    public String getReplayPreviousTickName() {
        return configuration.getString("replay.hotbar.previous.name", "&bPrevious Tick");
    }

    public org.bukkit.Material getReplayToggleRunningMaterial() {
        return org.bukkit.Material.matchMaterial(configuration.getString("replay.hotbar.toggle.running-material", "RED_DYE"));
    }

    public String getReplayToggleRunningName() {
        return configuration.getString("replay.hotbar.toggle.running-name", "&cPause Replay");
    }

    public org.bukkit.Material getReplayTogglePausedMaterial() {
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(configuration.getString("replay.hotbar.toggle.paused-material", "LIME_DYE"));
        return material == null ? org.bukkit.Material.LIME_DYE : material;
    }

    public String getReplayTogglePausedName() {
        return configuration.getString("replay.hotbar.toggle.paused-name", "&aStart Replay");
    }

    public org.bukkit.Material getReplayNextTickMaterial() {
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(configuration.getString("replay.hotbar.next.material", "BLAZE_ROD"));
        return material == null ? org.bukkit.Material.BLAZE_ROD : material;
    }

    public String getReplayNextTickName() {
        return configuration.getString("replay.hotbar.next.name", "&bNext Tick");
    }

    public org.bukkit.Material getReplaySeekBackwardMaterial() {
        return org.bukkit.Material.matchMaterial(configuration.getString("replay.hotbar.seek.backward.material", "SPECTRAL_ARROW"));
    }

    public String getReplaySeekBackwardName() {
        return configuration.getString("replay.hotbar.seek.backward.name", "&bRewind 3s");
    }

    public org.bukkit.Material getReplaySeekForwardMaterial() {
        return org.bukkit.Material.matchMaterial(configuration.getString("replay.hotbar.seek.forward.material", "SPECTRAL_ARROW"));
    }

    public String getReplaySeekForwardName() {
        return configuration.getString("replay.hotbar.seek.forward.name", "&bForward 3s");
    }

    public org.bukkit.Material getPracticeBlockMaterial() {
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(configuration.getString("practice-mode.block-material", "WHITE_TERRACOTTA"));
        return material == null ? org.bukkit.Material.WHITE_TERRACOTTA : material;
    }

    public java.util.List<String> getFinishMessagesPersonalBest() {
        return getFinishMessageList("finish-messages.personal-best");
    }

    public java.util.List<String> getFinishMessagesNormalFinish() {
        return getFinishMessageList("finish-messages.normal-finish");
    }

    private List<String> getFinishMessageList(String path) {
        List<String> lines = new ArrayList<>(configuration.getStringList(path));
        while (lines.size() < 9) {
            lines.add("none");
        }
        if (lines.size() > 9) {
            lines = lines.subList(0, 9);
        }
        return lines;
    }

    public org.bukkit.configuration.ConfigurationSection getHotbarSection() {
        return configuration.getConfigurationSection("hotbar");
    }

    public ConfigurationSection getIslandMenuSection() {
        return menuConfiguration.getConfigurationSection("island_menu");
    }

    public String getIslandMenuTitle() {
        return menuConfiguration.getString("island_menu.title", "&eIslands");
    }

    public int getIslandMenuSize() {
        return menuConfiguration.getInt("island_menu.size", 45);
    }

    public Map<Integer, ConfigurationSection> getMenuItemsBySlot(String menuKey) {
        ConfigurationSection menuSection = getMenuSection(menuKey);
        if (menuSection == null) {
            return new LinkedHashMap<>();
        }
        ConfigurationSection itemsSection = menuSection.getConfigurationSection("items");
        if (itemsSection == null) {
            return new LinkedHashMap<>();
        }

        Map<Integer, ConfigurationSection> items = new LinkedHashMap<>();
        for (String slotKey : itemsSection.getKeys(false)) {
            try {
                int slot = Integer.parseInt(slotKey);
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(slotKey);
                if (itemSection != null) {
                    items.put(slot, itemSection);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return items;
    }

    public List<Integer> getIslandMenuSlots() {
        List<Integer> slots = new ArrayList<>();
        for (Map.Entry<Integer, ConfigurationSection> entry : getMenuItemsBySlot("island_menu").entrySet()) {
            ConfigurationSection itemSection = entry.getValue();
            if (itemSection == null) {
                continue;
            }
            String action = itemSection.getString("action");
            if (action != null && action.startsWith("island_")) {
                slots.add(entry.getKey());
            }
        }
        slots.sort(Integer::compareTo);
        return slots;
    }

    public ConfigurationSection getSettingsMenuSection() {
        return menuConfiguration.getConfigurationSection("settings_menu");
    }

    private String normalizeMenuKey(String menuKey) {
        if (menuKey == null) {
            return null;
        }
        return menuKey.trim().toLowerCase(Locale.ROOT);
    }

    public ConfigurationSection getMenuSection(String menuKey) {
        return menuConfiguration.getConfigurationSection(normalizeMenuKey(menuKey));
    }

    public ConfigurationSection getMenuItemSection(String menuKey, int slot) {
        return getMenuItemsBySlot(menuKey).get(slot);
    }

    public boolean menuExists(String menuKey) {
        return getMenuSection(menuKey) != null;
    }

    public boolean isBuiltInMenu(String menuKey) {
        switch (normalizeMenuKey(menuKey)) {
            case "island_menu",
                    "settings_menu",
                    "mode_changer_menu",
                    "fastbuilder_settings_menu",
                    "cosmetics_menu",
                    "block_shop",
                    "replay_menu":
                return true;
            default:
                return false;
        }
    }

    public List<String> getMenuKeys() {
        return new ArrayList<>(menuConfiguration.getKeys(false));
    }

    public boolean removeMenu(String menuKey) {
        String normalized = normalizeMenuKey(menuKey);
        if (!menuExists(normalized) || isBuiltInMenu(normalized)) {
            return false;
        }
        menuConfiguration.set(normalized, null);
        saveMenuConfiguration();
        return true;
    }

    public void saveMenuConfiguration() {
        try {
            menuConfiguration.save(menuConfigFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save menu.yml: " + ex.getMessage());
        }
    }

    public String findMenuKeyByTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalizedTitle = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', title)).trim();
        for (String menuKey : menuConfiguration.getKeys(false)) {
            ConfigurationSection menuSection = menuConfiguration.getConfigurationSection(menuKey);
            if (menuSection == null) {
                continue;
            }
            String configuredTitle = menuSection.getString("title", "");
            if (configuredTitle == null || configuredTitle.isBlank()) {
                continue;
            }
            String normalizedConfiguredTitle = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', configuredTitle)).trim();
            if (normalizedTitle.equals(normalizedConfiguredTitle) || normalizedTitle.startsWith(normalizedConfiguredTitle + " - Page ")) {
                return menuKey;
            }
        }
        return null;
    }

    public boolean isFastBuilderMenuTitle(String title) {
        return findMenuKeyByTitle(title) != null;
    }

    public String getMenuAction(String menuKey, int slot) {
        ConfigurationSection itemSection = getMenuItemsBySlot(menuKey).get(slot);
        return itemSection == null ? null : itemSection.getString("action");
    }

    // New helper that returns the action only when both menuKey and slot match;
    // callers should prefer this to avoid slot-only collisions across menus.
    public String getMenuActionExact(String menuKey, int slot) {
        ConfigurationSection itemSection = getMenuItemSection(menuKey, slot);
        return itemSection == null ? null : itemSection.getString("action");
    }

    private List<Integer> parseSlotRange(String range) {
        List<Integer> slots = new ArrayList<>();
        if (range == null || range.isBlank()) {
            return slots;
        }
        String[] parts = range.split("-");
        if (parts.length != 2) {
            return slots;
        }
        try {
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());
            if (end < start) {
                return slots;
            }
            for (int slot = start; slot <= end; slot++) {
                slots.add(slot);
            }
        } catch (NumberFormatException ignored) {
        }
        return slots;
    }

    public void reload() {
        // Same auto-generation guarantee as the constructor: if config.yml or menu.yml were
        // deleted since the server started, /fb reload should recreate them (and re-run the
        // self-healing "ensure" steps for individual built-in menu sections) instead of just
        // loading whatever's left - which used to be nothing, leaving menuConfiguration empty
        // in memory until the next full server restart.
        createDefaultConfigIfMissing();
        createDefaultMenuConfigIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(configFile);
        this.menuConfiguration = YamlConfiguration.loadConfiguration(menuConfigFile);
        this.arenaSettingsConfiguration = loadArenaSettingsConfiguration();
        ensureCosmeticsShopMenuEntry();
        ensurePracticeModeMenuEntry();
        ensureLeaveConfirmationMenuEntry();
        ensureArenaSettingsFile();
        ensurePracticeBlockHotbarEntry();
    }

    public String getArenaStartMode(String arenaName) {
        return resolveArenaSetting(arenaName, "start", DEFAULT_ARENA_START_MODE, Set.of("BLOCK", "MOVE"));
    }

    public String getArenaFinishMode(String arenaName) {
        return resolveArenaSetting(arenaName, "finish", DEFAULT_ARENA_FINISH_MODE, Set.of("PLATE", "BED"));
    }

    private String resolveArenaSetting(String arenaName, String key, String fallback, Set<String> validValues) {
        if (arenaName == null || arenaName.isBlank()) {
            return fallback;
        }
        String normalizedArenaName = arenaName.toLowerCase(Locale.ROOT);
        String rawValue = readArenaSettingValue(normalizedArenaName, key);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return normalizeArenaSetting(rawValue, fallback, validValues);
    }

    private String readArenaSettingValue(String normalizedArenaName, String key) {
        ConfigurationSection arenaSection = arenaSettingsConfiguration.getConfigurationSection(normalizedArenaName);
        if (arenaSection != null) {
            for (String candidateKey : List.of(key, key.toUpperCase(Locale.ROOT), key.toLowerCase(Locale.ROOT))) {
                String value = arenaSection.getString(candidateKey);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return arenaSettingsConfiguration.getString(normalizedArenaName + "." + key);
    }

    private String normalizeArenaSetting(String rawValue, String fallback, Set<String> validValues) {
        if (rawValue == null) {
            return fallback;
        }
        String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
        if (validValues.contains(normalized)) {
            return normalized;
        }
        plugin.getLogger().warning("Invalid arena setting value '" + rawValue + "'. Falling back to '" + fallback + "'.");
        return fallback;
    }

    private void ensureArenaSettingsFile() {
        createDefaultArenaSettingsFileIfMissing();
        if (!arenaSettingsLoaded) {
            plugin.getLogger().warning("Skipping arena_settings.yml initialization because the file failed to load.");
            return;
        }
        this.arenaSettingsConfiguration = loadArenaSettingsConfiguration();
        File arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        if (arenasFile.exists()) {
            YamlConfiguration arenasConfiguration = YamlConfiguration.loadConfiguration(arenasFile);
            for (String arenaName : arenasConfiguration.getKeys(false)) {
                if (arenaName == null || arenaName.isBlank()) {
                    continue;
                }
                String normalizedArenaName = arenaName.toLowerCase(Locale.ROOT);
                ConfigurationSection arenaSection = arenaSettingsConfiguration.getConfigurationSection(normalizedArenaName);
                if (arenaSection == null) {
                    arenaSection = arenaSettingsConfiguration.createSection(normalizedArenaName);
                }
                if (arenaSection.getString("start") == null || arenaSection.getString("start").isBlank()) {
                    arenaSection.set("start", DEFAULT_ARENA_START_MODE);
                }
                if (arenaSection.getString("finish") == null || arenaSection.getString("finish").isBlank()) {
                    arenaSection.set("finish", DEFAULT_ARENA_FINISH_MODE);
                }
            }
        }
    }

    private ConfigurationSection getIslandCosmeticsSection(String arenaName, String mode, boolean createIfMissing) {
        if (!arenaSettingsLoaded) {
            return null;
        }
        String normalizedArena = normalizeConfigKey(arenaName);
        String normalizedMode = normalizeConfigKey(mode);
        String path = normalizedArena + ".island_cosmetics." + normalizedMode;
        ConfigurationSection existing = arenaSettingsConfiguration.getConfigurationSection(path);
        if (existing != null) {
            return existing;
        }
        if (!createIfMissing) {
            return null;
        }

        ConfigurationSection arenaSection = arenaSettingsConfiguration.getConfigurationSection(normalizedArena);
        if (arenaSection == null) {
            arenaSection = arenaSettingsConfiguration.createSection(normalizedArena);
        }
        ConfigurationSection islandCosmeticsSection = arenaSection.getConfigurationSection("island_cosmetics");
        if (islandCosmeticsSection == null) {
            islandCosmeticsSection = arenaSection.createSection("island_cosmetics");
        }
        ConfigurationSection modeSection = islandCosmeticsSection.getConfigurationSection(normalizedMode);
        if (modeSection == null) {
            modeSection = islandCosmeticsSection.createSection(normalizedMode);
        }
        saveArenaSettingsConfiguration();
        return modeSection;
    }

    public ConfigurationSection getIslandCosmeticsSection(String arenaName, String mode) {
        return getIslandCosmeticsSection(arenaName, mode, true);
    }

    private ConfigurationSection getExistingIslandCosmeticsSection(String arenaName, String mode) {
        return getIslandCosmeticsSection(arenaName, mode, false);
    }

    private ConfigurationSection getIslandCosmeticsSectionWithDefaultMode(String arenaName, String mode) {
        String normalizedMode = normalizeConfigKey(mode);
        ConfigurationSection section = getExistingIslandCosmeticsSection(arenaName, normalizedMode);
        if (section != null) {
            return section;
        }
        if (!"default".equals(normalizedMode)) {
            return getExistingIslandCosmeticsSection(arenaName, "default");
        }
        return null;
    }

    public List<String> getIslandCosmeticKeys(String arenaName, String mode) {
        if (!arenaSettingsLoaded) {
            return List.of();
        }
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        List<String> keys = new ArrayList<>();
        if (section == null) {
            return keys;
        }
        for (String key : section.getKeys(false)) {
            keys.add(key);
        }
        return keys;
    }

    public boolean hasIslandCosmetics(String arenaName, String mode) {
        if (!arenaSettingsLoaded) {
            return false;
        }
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        if (section == null) {
            return false;
        }
        return section.getKeys(false).stream().anyMatch(key -> section.getConfigurationSection(key) != null);
    }

    public boolean addIslandCosmetic(String arenaName, String mode, String cosmeticKey, String schematicName, String materialName, String displayName, int price, int slot) {
        String normalizedKey = normalizeConfigKey(cosmeticKey);
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return false;
        }
        ConfigurationSection section = getIslandCosmeticsSection(arenaName, mode);
        if (section.contains(normalizedKey)) {
            return false;
        }
        ConfigurationSection cosmeticSection = section.createSection(normalizedKey);
        cosmeticSection.set("schematic", schematicName);
        cosmeticSection.set("material", materialName != null && !materialName.isBlank() ? materialName : "GRASS_BLOCK");
        cosmeticSection.set("name", displayName != null && !displayName.isBlank() ? displayName : cosmeticKey);
        cosmeticSection.set("price", price);
        cosmeticSection.set("slot", slot);
        cosmeticSection.set("lore", List.of("&7Island cosmetic"));
        saveArenaSettingsConfiguration();
        return true;
    }

    public boolean removeIslandCosmetic(String arenaName, String mode, String cosmeticKey) {
        if (!arenaSettingsLoaded) {
            return false;
        }
        String normalizedKey = normalizeConfigKey(cosmeticKey);
        ConfigurationSection section = getIslandCosmeticsSection(arenaName, mode);
        if (section == null || !section.contains(normalizedKey)) {
            return false;
        }
        section.set(normalizedKey, null);
        saveArenaSettingsConfiguration();
        return true;
    }

    public String getIslandCosmeticSchematic(String arenaName, String mode, String cosmeticKey, String fallbackSchematic) {
        String normalizedKey = normalizeConfigKey(cosmeticKey);
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        if (section == null) {
            return fallbackSchematic;
        }
        ConfigurationSection cosmeticSection = section.getConfigurationSection(normalizedKey);
        if (cosmeticSection == null) {
            return fallbackSchematic;
        }
        String schematic = cosmeticSection.getString("schematic", fallbackSchematic);
        return schematic == null || schematic.isBlank() ? fallbackSchematic : schematic;
    }

    public String getIslandCosmeticName(String arenaName, String mode, String cosmeticKey, String fallbackName) {
        if (cosmeticKey == null || cosmeticKey.isBlank()) {
            return fallbackName;
        }
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        if (section == null) {
            return fallbackName;
        }
        ConfigurationSection cosmeticSection = section.getConfigurationSection(normalizeConfigKey(cosmeticKey));
        if (cosmeticSection == null) {
            return fallbackName;
        }
        String name = cosmeticSection.getString("name", fallbackName);
        return name == null || name.isBlank() ? fallbackName : name;
    }

    public String getIslandCosmeticMaterial(String arenaName, String mode, String cosmeticKey, String fallbackMaterial) {
        if (cosmeticKey == null || cosmeticKey.isBlank()) {
            return fallbackMaterial;
        }
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        if (section == null) {
            return fallbackMaterial;
        }
        ConfigurationSection cosmeticSection = section.getConfigurationSection(normalizeConfigKey(cosmeticKey));
        if (cosmeticSection == null) {
            return fallbackMaterial;
        }
        String material = cosmeticSection.getString("material", fallbackMaterial);
        return material == null || material.isBlank() ? fallbackMaterial : material;
    }

    public int getIslandCosmeticPrice(String arenaName, String mode, String cosmeticKey, int fallbackPrice) {
        if (cosmeticKey == null || cosmeticKey.isBlank()) {
            return fallbackPrice;
        }
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        if (section == null) {
            return fallbackPrice;
        }
        ConfigurationSection cosmeticSection = section.getConfigurationSection(normalizeConfigKey(cosmeticKey));
        if (cosmeticSection == null) {
            return fallbackPrice;
        }
        return cosmeticSection.getInt("price", fallbackPrice);
    }

    public int getIslandCosmeticSlot(String arenaName, String mode, String cosmeticKey, int fallbackSlot) {
        if (!arenaSettingsLoaded || cosmeticKey == null || cosmeticKey.isBlank()) {
            return fallbackSlot;
        }
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        if (section == null) {
            return fallbackSlot;
        }
        ConfigurationSection cosmeticSection = section.getConfigurationSection(normalizeConfigKey(cosmeticKey));
        if (cosmeticSection == null) {
            return fallbackSlot;
        }
        return cosmeticSection.getInt("slot", fallbackSlot);
    }

    public List<String> getIslandCosmeticLore(String arenaName, String mode, String cosmeticKey) {
        if (!arenaSettingsLoaded || cosmeticKey == null || cosmeticKey.isBlank()) {
            return List.of();
        }
        ConfigurationSection section = getIslandCosmeticsSectionWithDefaultMode(arenaName, mode);
        if (section == null) {
            return List.of();
        }
        ConfigurationSection cosmeticSection = section.getConfigurationSection(normalizeConfigKey(cosmeticKey));
        if (cosmeticSection == null) {
            return List.of();
        }
        return cosmeticSection.getStringList("lore");
    }

    private YamlConfiguration loadArenaSettingsConfiguration() {
        if (!arenaSettingsFile.exists()) {
            createDefaultArenaSettingsFileIfMissing();
        }
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(arenaSettingsFile);
            arenaSettingsLoaded = true;
        } catch (IOException | InvalidConfigurationException ex) {
            arenaSettingsLoaded = false;
            plugin.getLogger().severe("Failed to load " + arenaSettingsFile.getAbsolutePath() + ": " + ex.getMessage());
            plugin.getLogger().severe("arena_settings.yml contains invalid YAML and will not be written until repaired.");
            plugin.getLogger().severe("If you want to recover the file, fix the YAML or restore from backup.");
            loaded = new YamlConfiguration();
        }
        return loaded;
    }

    public boolean isArenaSettingsLoaded() {
        return arenaSettingsLoaded;
    }

    private void createDefaultArenaSettingsFileIfMissing() {
        if (arenaSettingsFile.exists()) {
            return;
        }
        String defaultContent = "# SkepiFB per-arena settings file\n"
                + "#\n"
                + "# Optional overrides for individual arenas. An arena not listed here just uses\n"
                + "# the plugin's normal defaults (BLOCK start, PLATE finish, no island cosmetics).\n"
                + "#\n"
                + "# START: BLOCK or MOVE - how an attempt begins (placing the first block, or\n"
                + "#        simply moving off the starting island)\n"
                + "# FINISH: PLATE or BED - how an attempt is detected as finished\n"
                + "#\n"
                + "# island_cosmetics: per-arena island-skin options, grouped by \"mode\" (usually just\n"
                + "# \"default\" unless the arena supports multiple modes). Each cosmetic needs:\n"
                + "#   schematic - the schematic file name (without extension) to paste for this skin\n"
                + "#   material  - icon shown in the island shop GUI\n"
                + "#   name      - display name in the shop (supports & color codes)\n"
                + "#   price     - cost in coins to unlock (0 = free/default)\n"
                + "#   slot      - fixed slot in the island shop GUI\n"
                + "#\n"
                + "# Example:\n"
                + "# myArena:\n"
                + "#   start: BLOCK\n"
                + "#   finish: PLATE\n"
                + "#   island_cosmetics:\n"
                + "#     default:\n"
                + "#       classic:\n"
                + "#         schematic: classic\n"
                + "#         material: GRASS_BLOCK\n"
                + "#         name: '&aClassic'\n"
                + "#         price: 0\n"
                + "#         slot: 0\n";
        try {
            Files.writeString(arenaSettingsFile.toPath(), defaultContent, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default arena_settings.yml: " + ex.getMessage());
        }
    }

    private void saveArenaSettingsConfiguration() {
        if (!arenaSettingsLoaded) {
            plugin.getLogger().severe("Refusing to save arena_settings.yml because the file failed to load due to invalid YAML.");
            return;
        }
        try {
            arenaSettingsConfiguration.save(arenaSettingsFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Unable to save arena_settings.yml: " + ex.getMessage());
        }
    }

    public Map<String, String> getChatPlaceholders() {
        Map<String, String> placeholders = new LinkedHashMap<>();
        ConfigurationSection section = configuration.getConfigurationSection("chat-placeholders.placeholders");
        if (section == null) {
            return placeholders;
        }
        for (String key : section.getKeys(false)) {
            String value = section.getString(key, "");
            if (value != null) {
                placeholders.put(key, value);
            }
        }
        return placeholders;
    }

    public YamlConfiguration getConfiguration() {
        return configuration;
    }

    public boolean isBossbarEnabled() {
        return configuration.getBoolean("bossbar.enabled", true);
    }

    public String getBossbarStyleName() {
        return configuration.getString("bossbar.style", "SOLID");
    }

    public String getBossbarTitleFormat() {
        return configuration.getString("bossbar.title-format",
                "%level_color%&lLevel %level% &8| %level_color%%bossbar_percent%% &e(%xp%/%next_level_xp% XP)");
    }

    public String getBossbarColorName(int level) {
        String defaultColor = switch (level) {
            case 1 -> "GREEN";
            case 2 -> "YELLOW";
            case 5 -> "YELLOW";
            case 6 -> "BLUE";
            case 7 -> "PURPLE";
            default -> "WHITE";
        };
        return configuration.getString("bossbar.colors.level-" + level, defaultColor);
    }

    /**
     * Returns the configured default-shop-selections value for a shop id (e.g. "block_shop"),
     * or "default" if nothing is configured - "default" means "just use shop.yml's own
     * default: true item", i.e. unchanged behavior.
     */
    public String getDefaultShopSelection(String shopId) {
        return configuration.getString("default-shop-selections." + shopId, "default");
    }

    public YamlConfiguration getMenuConfiguration() {
        return menuConfiguration;
    }
}
