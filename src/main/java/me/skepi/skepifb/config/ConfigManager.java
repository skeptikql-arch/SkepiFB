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
    private static final String DEFAULT_ARENA_DIRECTION = "STRAIGHT";
    private static final double DEFAULT_ARENA_MAX_TIME = 0.000;

    private final JavaPlugin plugin;
    private final File configFile;
    private final File menuConfigFile;
    private final File menusFolder;
    private final File islandSwitcherMenuFile;
    private final File replaysMenuFile;
    private final File settingsMenuFile;
    private final File fastbuilderSettingsFile;
    private final File cosmeticMenuFile;
    private final File modeSwitcherMenuFile;
    private final File practiceTemplateMenuFile;
    private final File spawnTemplateMenuFile;
    private final File customMenusFile;
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
        // Legacy single-file location, kept only so a pre-existing menu.yml can be migrated into
        // the split files below (see migrateLegacyMenuFileIfPresent()). The plugin never writes to
        // this path again once that migration has happened.
        this.menuConfigFile = new File(dataFolder, "menu.yml");
        this.menusFolder = new File(dataFolder, "menus");
        if (!menusFolder.exists()) {
            menusFolder.mkdirs();
        }
        this.islandSwitcherMenuFile = new File(menusFolder, "island_switcher_menu.yml");
        this.replaysMenuFile = new File(menusFolder, "replays_menu.yml");
        this.settingsMenuFile = new File(menusFolder, "settings_menu.yml");
        this.fastbuilderSettingsFile = new File(menusFolder, "fastbuilder_settings.yml");
        this.cosmeticMenuFile = new File(menusFolder, "cosmetic_menu.yml");
        this.modeSwitcherMenuFile = new File(menusFolder, "mode_switcher_menu.yml");
        this.practiceTemplateMenuFile = new File(menusFolder, "practice_template_menu.yml");
        this.spawnTemplateMenuFile = new File(menusFolder, "spawn_template_menu.yml");
        this.customMenusFile = new File(menusFolder, "custom_menus.yml");
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
        //
        // menu.yml itself has since been split into one file per built-in menu, plus
        // custom_menus.yml for everything else, all under a menus/ folder - see
        // createDefaultMenuFilesIfMissing() and loadMenuConfigurations(). menuConfiguration below
        // remains a single in-memory MERGED view of all of those files combined, purely so every
        // other class that already reads through ConfigManager's menu-lookup methods (getMenuSection,
        // getMenuItemsBySlot, getIslandMenuSection, etc.) keeps working completely unchanged - only
        // the on-disk storage is split, nothing about how the rest of the plugin reads menus is any
        // different.
        createDefaultMenuFilesIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(configFile);
        this.menuConfiguration = loadMergedMenuConfiguration();
        this.arenaSettingsConfiguration = loadArenaSettingsConfiguration();
        ensureCosmeticsShopMenuEntry();
        ensurePracticeModeMenuEntry();
        ensureLeaveConfirmationMenuEntry();
        ensureArenaSettingsFile();
        ensurePracticeBlockHotbarEntry();
        ensurePracticeCheckpointHotbarEntry();
        ensureIslandNpcConfigSection();
        ensureIslandNpcDebugToggle();
        ensureReplayHologramConfigSection();
        ensureAutoAddModeToggle();
        ensurePeriodicMessagesConfigSection();
    }

    // Self-healing migration for installs whose config.yml predates the island NPC feature -
    // createDefaultConfigIfMissing() only ever writes the template once, on first-ever startup,
    // so an existing server never receives new top-level keys added later on their own. Adds the
    // "island-npc:" section with the same defaults a fresh install ships with, only if it's
    // entirely absent; never touches it if the admin already has one, even a partial one.
    private void ensureIslandNpcConfigSection() {
        if (configuration.contains("island-npc")) {
            return;
        }
        ConfigurationSection section = configuration.createSection("island-npc");
        section.set("enabled", true);
        section.set("name", "&bFastbuilder");
        section.set("click-menu", "mode_changer_menu");
        section.set("debug", false);
        ConfigurationSection offset = section.createSection("offset");
        offset.set("x", -1.0);
        offset.set("y", 0.0);
        offset.set("z", -1.0);
        try {
            configuration.save(configFile);
            plugin.getLogger().info("Added the missing \"island-npc:\" section to config.yml (island NPCs are a "
                    + "new feature - see config.yml for the offset/name/click-menu options).");
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save config.yml after adding the missing island-npc section: " + ex.getMessage());
        }
    }

    // Same self-healing pattern as ensureIslandNpcConfigSection() above, for the newer
    // "island-npc.debug" toggle - existing installs already have an "island-npc" section (so the
    // whole-section check above skips them) but won't have this specific key yet.
    private void ensureIslandNpcDebugToggle() {
        if (configuration.contains("island-npc.debug")) {
            return;
        }
        configuration.set("island-npc.debug", false);
        try {
            configuration.save(configFile);
            plugin.getLogger().info("Added the missing \"island-npc.debug: false\" option to config.yml - set it to "
                    + "true to log detailed skin/rename troubleshooting info to console for the island NPC feature.");
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save config.yml after adding the missing island-npc.debug option: " + ex.getMessage());
        }
    }

    // Same self-healing pattern as ensureIslandNpcConfigSection() above, for the newer
    // replay-hologram.lines section (previously a hardcoded, non-functional label list in
    // TimerManager - existing installs won't have this key at all).
    private static final java.util.List<String> DEFAULT_REPLAY_HOLOGRAM_LINES = java.util.List.of(
            "&b&lX: &f%xcoordinate%",
            "&b&lY: &f%ycoordinate%",
            "&b&lZ: &f%zcoordinate%",
            "&d&lYaw: &f%yaw% &7| &d&lPitch: &f%pitch%",
            "&a&lPing: &f%ping%ms",
            "&e&lCPS: &f%leftcps% &7/ &f%rightcps%",
            "&6&lJump Ticks: &f%jumpticks%",
            "none",
            "none",
            "none"
    );

    private void ensureReplayHologramConfigSection() {
        if (configuration.contains("replay-hologram")) {
            return;
        }
        ConfigurationSection section = configuration.createSection("replay-hologram");
        section.set("lines", DEFAULT_REPLAY_HOLOGRAM_LINES);
        try {
            configuration.save(configFile);
            plugin.getLogger().info("Added the missing \"replay-hologram:\" section to config.yml (customizable "
                    + "replay hologram lines are a new feature - see config.yml for the \"lines\" list, up to 10 "
                    + "entries, \"none\" to leave a slot empty).");
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save config.yml after adding the missing replay-hologram section: " + ex.getMessage());
        }
    }

    // Same self-healing pattern as ensureIslandNpcConfigSection() above, for the newer
    // periodic-messages.messages section (3 configurable slots randomly broadcast to all online
    // players every "interval-seconds" seconds, alongside a 4th fixed/non-configurable SkepiFB
    // credit message - see PeriodicMessageManager). Existing installs won't have this key at all.
    private static final java.util.List<String> DEFAULT_PERIODIC_MESSAGES = java.util.List.of(
            "&aTip: &fType /fb help to see every command.",
            "&aTip: &fRight-click the NPC on your island to switch modes.",
            "&aTip: &fType /stats to view your personal best times."
    );

    // Default broadcast interval, in seconds, between periodic chat messages. 600 = 10 minutes.
    private static final int DEFAULT_PERIODIC_MESSAGE_INTERVAL_SECONDS = 600;

    private void ensurePeriodicMessagesConfigSection() {
        boolean changed = false;
        ConfigurationSection section = configuration.getConfigurationSection("periodic-messages");
        if (section == null) {
            section = configuration.createSection("periodic-messages");
            section.set("messages", DEFAULT_PERIODIC_MESSAGES);
            changed = true;
        }
        // Separate check so existing installs that already have "periodic-messages.messages" (but
        // predate the interval-seconds option) get it added too, instead of being skipped
        // entirely by the section-already-exists check above.
        if (!section.contains("interval-seconds")) {
            section.set("interval-seconds", DEFAULT_PERIODIC_MESSAGE_INTERVAL_SECONDS);
            changed = true;
        }
        if (!changed) {
            return;
        }
        try {
            configuration.save(configFile);
            plugin.getLogger().info("Added the missing \"periodic-messages:\" section (and/or its "
                    + "\"interval-seconds\" option) to config.yml - 3 configurable messages randomly "
                    + "broadcast to all online players every \"interval-seconds\" seconds (600 by default) - "
                    + "see config.yml).");
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save config.yml after adding the missing periodic-messages section: " + ex.getMessage());
        }
    }

    // Same self-healing pattern as ensureIslandNpcConfigSection() above, for the newer
    // auto-add-new-modes-to-mode-switcher toggle.
    private void ensureAutoAddModeToggle() {
        if (configuration.contains("auto-add-new-modes-to-mode-switcher")) {
            return;
        }
        configuration.set("auto-add-new-modes-to-mode-switcher", true);
        try {
            configuration.save(configFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save config.yml after adding the missing "
                    + "auto-add-new-modes-to-mode-switcher toggle: " + ex.getMessage());
        }
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

    // Same self-healing pattern as ensurePracticeBlockHotbarEntry() above, for the practice
    // checkpoint item ("practice_checkpoint" action) - shown to a player any time practice mode is
    // on (see HotbarManager#buildConfiguredHotbarItem); right-click saves a checkpoint
    // (position/facing/timer state), left-click returns to it. Existing installs never received
    // slot5 automatically, same reasoning as above.
    private void ensurePracticeCheckpointHotbarEntry() {
        ConfigurationSection hotbar = configuration.getConfigurationSection("hotbar");
        if (hotbar == null) {
            hotbar = configuration.createSection("hotbar");
        }
        ConfigurationSection items = hotbar.getConfigurationSection("items");
        if (items == null) {
            items = hotbar.createSection("items");
        }

        for (String slotKey : items.getKeys(false)) {
            ConfigurationSection existing = items.getConfigurationSection(slotKey);
            if (existing != null && "practice_checkpoint".equals(existing.getString("action"))) {
                return;
            }
        }

        if (items.contains("slot5")) {
            // slot5 is occupied by something else - don't clobber a customized layout.
            return;
        }

        ConfigurationSection checkpointSlot = items.createSection("slot5");
        checkpointSlot.set("name", "&bCheckpoint");
        checkpointSlot.set("material", "CYAN_DYE");
        checkpointSlot.set("action", "practice_checkpoint");

        try {
            configuration.save(configFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to save config.yml after adding missing practice_checkpoint hotbar slot: " + ex.getMessage());
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
                + "# Replay actionbar displayed while viewing a replay, plus everything else replay-related\n"
                + "# (playback speed and the hotbar controls used while watching one).\n"
                + "# Available placeholders: %tick%, %max_tick%, %seconds%, %time%\n"
                + "replay:\n"
                + "  restore-per-tick: 100\n"
                + "  actionbar:\n"
                + "    format: \"&bReplay %tick%/%max_tick% &8- &e%time%s\"\n"
                + "  hotbar:\n"
                + "    previous:\n"
                + "      material: BLAZE_ROD\n"
                + "      name: \"&bPrevious Tick\"\n"
                + "    next:\n"
                + "      material: BLAZE_ROD\n"
                + "      name: \"&bNext Tick\"\n"
                + "    toggle:\n"
                + "      running-material: RED_DYE\n"
                + "      running-name: \"&cPause Replay\"\n"
                + "      paused-material: LIME_DYE\n"
                + "      paused-name: \"&aStart Replay\"\n"
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
                + "# Actions: none, block, practice_block, practice_checkpoint, respawn, island_menu, replays_menu, settings_menu, leave, toggle_practice_mode\n"
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
                + "    slot5:\n"
                + "      name: \"&bCheckpoint\"\n"
                + "      material: CYAN_DYE\n"
                + "      action: practice_checkpoint\n"
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
                + "# Message shown when a player is denied a command or action by permissions.yml.\n"
                + "no-permission-message: \"&cYou do not have permission to do that.\"\n"
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
                + "  # Which sections appear in the hologram and in what order, top to bottom. A section that\n"
                + "  # has nothing to show for a given player (e.g. \"leaderboard\" for someone with no\n"
                + "  # placements) is skipped entirely - no title, no gap left behind for it either. More\n"
                + "  # section types may be added here in the future; unrecognized names are just ignored.\n"
                + "  # Currently available: \"leaderboard\", \"stats\".\n"
                + "  #\n"
                + "  # Accepts either a normal YAML list:\n"
                + "  #   order:\n"
                + "  #     - \"leaderboard\"\n"
                + "  #     - \"stats\"\n"
                + "  # or a single comma-separated line, whichever is easier to edit:\n"
                + "  #   order: \"leaderboard, stats\"\n"
                + "  order:\n"
                + "    - \"leaderboard\"\n"
                + "    - \"stats\"\n"
                + "\n"
                + "  # Number of blank lines inserted between two sections that are both actually showing.\n"
                + "  # Raise this if the boundary between sections (e.g. leaderboard vs. stats) isn't visually\n"
                + "  # clear enough.\n"
                + "  section-spacing: 2\n"
                + "\n"
                + "  # Leaderboard section - an extra block of lines only shown while the player currently\n"
                + "  # holds at least one position (1-10) on ANY mode's leaderboard (leaderboard.yml, /fb lb),\n"
                + "  # not just their current arena/mode.\n"
                + "  leaderboard:\n"
                + "    enabled: true\n"
                + "    title: \"&d&lGLOBAL LEADERBOARD PLAYER\"\n"
                + "    # One line per leaderboard placement the player holds, in this format. Placeholders:\n"
                + "    #   {PLACE} - the position number (e.g. \"4\", no # prefix - the # is already in the\n"
                + "    #             default format string below)\n"
                + "    #   {MODE}  - the mode's display name (e.g. \"Snow\")\n"
                + "    #   {TIME}  - the player's time on that placement, to 3 decimal places (e.g. \"6.900\")\n"
                + "    # Example: a 6.9s time on Snow mode at position #4 renders as:\n"
                + "    #   \"&b#4 &eon Snow Mode &7- &b6.900\"\n"
                + "    line-format: \"&b#{PLACE} &eon {MODE} Mode &7- &b{TIME}\"\n"
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

        defaultConfig += "\n# =========================================================================\n";
        defaultConfig += "# ISLAND NPC - a Citizens NPC (requires the Citizens plugin) that spawns next to an\n";
        defaultConfig += "# island's spawn point while that island is occupied, wearing the occupying player's own\n";
        defaultConfig += "# skin, and is removed the moment the island becomes empty again. Right-clicking it opens\n";
        defaultConfig += "# \"click-menu\" below (mode_changer_menu by default - that's the internal key for what /fb\n";
        defaultConfig += "# help calls the mode switcher/changer menu - but you can point it at any menu key from\n";
        defaultConfig += "# menu.yml/custom_menus.yml, e.g. \"island_menu\" or a custom menu's key).\n";
        defaultConfig += "# =========================================================================\n";
        defaultConfig += "island-npc:\n";
        defaultConfig += "  enabled: true\n";
        defaultConfig += "  name: \"&bFastbuilder\"\n";
        defaultConfig += "  click-menu: \"mode_changer_menu\"\n";
        defaultConfig += "  # Logs detailed step-by-step info to console about the NPC's rename/skin process (what\n";
        defaultConfig += "  # reflection calls succeeded/failed, and the skin trait's state at each step) - turn this on\n";
        defaultConfig += "  # if the NPC's nametag or skin is ever wrong, then check console/logs after it spawns.\n";
        defaultConfig += "  debug: false\n";
        defaultConfig += "  # Offset from the island's spawn point, in blocks.\n";
        defaultConfig += "  offset:\n";
        defaultConfig += "    x: -1.0\n";
        defaultConfig += "    y: 0.0\n";
        defaultConfig += "    z: -1.0\n";

        defaultConfig += "\n# =========================================================================\n";
        defaultConfig += "# REPLAY HOLOGRAM - the stack of text lines floating above a replay ghost's head, visible\n";
        defaultConfig += "# only to the player watching that replay. Up to 10 lines, listed here TOP to BOTTOM exactly\n";
        defaultConfig += "# as they'll appear in-game (the first line below is the highest/topmost one, the last is the\n";
        defaultConfig += "# lowest, closest to - but always kept clear of - the ghost's own username nametag).\n";
        defaultConfig += "#\n";
        defaultConfig += "# Type the line exactly as \"none\" (no quotes needed) to leave that slot completely empty -\n";
        defaultConfig += "# no armor stand is even spawned for it, so it costs nothing and shows nothing.\n";
        defaultConfig += "#\n";
        defaultConfig += "# Available placeholders (these are REPLAY-ONLY - they only work on these lines, not in\n";
        defaultConfig += "# chat/scoreboard/statboard): %xcoordinate%, %ycoordinate%, %zcoordinate%, %yaw%, %pitch%,\n";
        defaultConfig += "# %ping%, %leftcps%, %rightcps%, %jumpticks%. Each shows the value that was actually RECORDED\n";
        defaultConfig += "# at that instant during the original run being replayed (not the viewer's own live stats).\n";
        defaultConfig += "# %jumpticks% is the number of ticks the player spent on the ground before their most recent\n";
        defaultConfig += "# jump during the run - it freezes the instant they leave the ground and resets to 0 the\n";
        defaultConfig += "# instant they land again.\n";
        defaultConfig += "# =========================================================================\n";
        defaultConfig += "replay-hologram:\n";
        defaultConfig += "  lines:\n";
        defaultConfig += "    - \"&b&lX: &f%xcoordinate%\"\n";
        defaultConfig += "    - \"&b&lY: &f%ycoordinate%\"\n";
        defaultConfig += "    - \"&b&lZ: &f%zcoordinate%\"\n";
        defaultConfig += "    - \"&d&lYaw: &f%yaw% &7| &d&lPitch: &f%pitch%\"\n";
        defaultConfig += "    - \"&a&lPing: &f%ping%ms\"\n";
        defaultConfig += "    - \"&e&lCPS: &f%leftcps% &7/ &f%rightcps%\"\n";
        defaultConfig += "    - \"&6&lJump Ticks: &f%jumpticks%\"\n";
        defaultConfig += "    - \"none\"\n";
        defaultConfig += "    - \"none\"\n";
        defaultConfig += "    - \"none\"\n";

        defaultConfig += "\n# =========================================================================\n";
        defaultConfig += "# When a brand-new arena/mode is created with /fb add, automatically place a button for\n";
        defaultConfig += "# it in mode_changer_menu.yml, in whichever slot is the first one not already configured\n";
        defaultConfig += "# (border decorations included) - up to that menu's size. If every slot is already taken,\n";
        defaultConfig += "# nothing is added (the menu is never resized or overwritten) - add one manually instead,\n";
        defaultConfig += "# same as any other button: material/name/lore, action: mode, mode: <arena name>. This\n";
        defaultConfig += "# only ever runs for a mode at the moment it's created - it never touches arenas that\n";
        defaultConfig += "# already existed, so turning this off only stops FUTURE new modes from being auto-added.\n";
        defaultConfig += "# =========================================================================\n";
        defaultConfig += "auto-add-new-modes-to-mode-switcher: true\n";

        defaultConfig += "\n# =========================================================================\n";
        defaultConfig += "# PERIODIC CHAT MESSAGES - every \"interval-seconds\" seconds, one message is randomly chosen\n";
        defaultConfig += "# and broadcast to every online player. Exactly 3 slots below. Color codes (&) are\n";
        defaultConfig += "# supported. Use \"none\" to disable a slot (it's simply skipped when picking a random\n";
        defaultConfig += "# message).\n";
        defaultConfig += "#\n";
        defaultConfig += "# interval-seconds: how often (in seconds) a message is broadcast. 600 = 10 minutes by\n";
        defaultConfig += "# default. Lower it (e.g. to 1) to quickly test your messages, then set it back.\n";
        defaultConfig += "#\n";
        defaultConfig += "# A 4th message - crediting SkepiFB itself - always takes part in the same random rotation\n";
        defaultConfig += "# alongside these 3, but it is fixed and NOT listed here or anywhere else in config.yml.\n";
        defaultConfig += "# =========================================================================\n";
        defaultConfig += "periodic-messages:\n";
        defaultConfig += "  interval-seconds: 600\n";
        defaultConfig += "  messages:\n";
        defaultConfig += "    - \"&aTip: &fType /fb help to see every command.\"\n";
        defaultConfig += "    - \"&aTip: &fRight-click the NPC on your island to switch modes.\"\n";
        defaultConfig += "    - \"&aTip: &fType /stats to view your personal best times.\"\n";

        try {
            Files.write(configFile.toPath(), defaultConfig.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default config.yml: " + ex.getMessage());
        }
    }

    /**
     * Routes a top-level menu key to the file (under menus/) it's stored in. Every built-in menu
     * has its own dedicated file; anything else (custom menus created via /fb menu add, and the
     * "stat_reset" mini-menu used by the settings menu) lives together in custom_menus.yml/
     * settings_menu.yml as noted below.
     */
    private File menuFileForKey(String key) {
        if (key == null) {
            return customMenusFile;
        }
        return switch (key) {
            case "island_menu" -> islandSwitcherMenuFile;
            case "replay_menu" -> replaysMenuFile;
            case "settings_menu", "stat_reset" -> settingsMenuFile;
            case "fastbuilder_settings_menu" -> fastbuilderSettingsFile;
            case "cosmetics_menu" -> cosmeticMenuFile;
            case "mode_changer_menu" -> modeSwitcherMenuFile;
            case "practice_template_menu" -> practiceTemplateMenuFile;
            case "spawn_template_menu" -> spawnTemplateMenuFile;
            default -> customMenusFile;
        };
    }

    /**
     * Creates each of the 7 menu files (island_switcher_menu.yml, replays_menu.yml,
     * settings_menu.yml, fastbuilder_settings.yml, cosmetic_menu.yml, mode_switcher_menu.yml,
     * custom_menus.yml, all under menus/) that doesn't already exist. A legacy single menu.yml
     * from before this split (if present) is migrated first - its keys are distributed into the
     * matching new files - so nobody's existing customizations are lost; menu.yml itself is then
     * renamed to menu.yml.migrated as a backup rather than deleted. Any key not covered by the
     * legacy file, or if there was no legacy file at all, falls back to this plugin's normal
     * bundled defaults for that key.
     */
    private void createDefaultMenuFilesIfMissing() {
        File[] allFiles = {islandSwitcherMenuFile, replaysMenuFile, settingsMenuFile,
                fastbuilderSettingsFile, cosmeticMenuFile, modeSwitcherMenuFile,
                practiceTemplateMenuFile, spawnTemplateMenuFile, customMenusFile};
        boolean anyMissing = false;
        for (File f : allFiles) {
            if (!f.exists()) {
                anyMissing = true;
                break;
            }
        }
        if (!anyMissing) {
            return;
        }

        YamlConfiguration legacyConfig = null;
        if (menuConfigFile.exists()) {
            legacyConfig = YamlConfiguration.loadConfiguration(menuConfigFile);
        }

        String defaultMenuConfig = buildDefaultMenuConfigText();
        YamlConfiguration defaultConfig = new YamlConfiguration();
        try {
            defaultConfig.loadFromString(defaultMenuConfig);
        } catch (Exception ex) {
            plugin.getLogger().warning("Unable to parse built-in menu defaults: " + ex.getMessage());
        }

        for (File destination : allFiles) {
            if (destination.exists()) {
                continue;
            }
            YamlConfiguration destConfig = new YamlConfiguration();
            java.util.Set<String> keysForThisFile = new java.util.LinkedHashSet<>();
            for (String key : defaultConfig.getKeys(false)) {
                if (menuFileForKey(key).equals(destination)) {
                    keysForThisFile.add(key);
                }
            }
            if (legacyConfig != null) {
                for (String key : legacyConfig.getKeys(false)) {
                    if (menuFileForKey(key).equals(destination)) {
                        keysForThisFile.add(key);
                    }
                }
            }
            for (String key : keysForThisFile) {
                Object source = (legacyConfig != null && legacyConfig.contains(key))
                        ? legacyConfig.get(key)
                        : defaultConfig.get(key);
                destConfig.set(key, source);
            }
            try {
                destConfig.save(destination);
            } catch (IOException ex) {
                plugin.getLogger().warning("Unable to create " + destination.getName() + ": " + ex.getMessage());
            }
        }

        if (legacyConfig != null) {
            try {
                File backup = new File(menuConfigFile.getParentFile(), "menu.yml.migrated");
                Files.move(menuConfigFile.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Migrated menu.yml into the menus/ folder (split into one file per menu). "
                        + "The old menu.yml was renamed to menu.yml.migrated as a backup.");
            } catch (IOException ex) {
                plugin.getLogger().warning("Migrated menu.yml content into menus/, but couldn't rename the old file: " + ex.getMessage());
            }
        }
    }

    /**
     * Loads all 7 menu files and merges them into a single in-memory YamlConfiguration, exactly
     * as if they were still one big menu.yml. Every other menu-lookup method in this class (and
     * everything in HotbarManager that goes through them) reads this merged view and needs no
     * awareness that the underlying storage is split across files.
     */
    private YamlConfiguration loadMergedMenuConfiguration() {
        YamlConfiguration merged = new YamlConfiguration();
        File[] allFiles = {islandSwitcherMenuFile, replaysMenuFile, settingsMenuFile,
                fastbuilderSettingsFile, cosmeticMenuFile, modeSwitcherMenuFile,
                practiceTemplateMenuFile, spawnTemplateMenuFile, customMenusFile};
        for (File file : allFiles) {
            if (!file.exists()) {
                continue;
            }
            YamlConfiguration source = YamlConfiguration.loadConfiguration(file);
            for (String key : source.getKeys(false)) {
                merged.set(key, source.get(key));
            }
        }
        return merged;
    }

    /**
     * Splits the in-memory merged menu configuration back out across the 7 menu files, writing
     * only each file's own keys to it. Called wherever the old code used to save the single
     * menu.yml (menuConfiguration.save(menuConfigFile)) - see saveMenuConfiguration().
     */
    private void saveSplitMenuConfigurations() {
        File[] allFiles = {islandSwitcherMenuFile, replaysMenuFile, settingsMenuFile,
                fastbuilderSettingsFile, cosmeticMenuFile, modeSwitcherMenuFile,
                practiceTemplateMenuFile, spawnTemplateMenuFile, customMenusFile};
        for (File destination : allFiles) {
            YamlConfiguration destConfig = new YamlConfiguration();
            for (String key : menuConfiguration.getKeys(false)) {
                if (menuFileForKey(key).equals(destination)) {
                    destConfig.set(key, menuConfiguration.get(key));
                }
            }
            try {
                destConfig.save(destination);
            } catch (IOException ex) {
                plugin.getLogger().warning("Unable to save " + destination.getName() + ": " + ex.getMessage());
            }
        }
    }

    private String buildDefaultMenuConfigText() {
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
                + "# =========================================================================\n"
                + "# practice_template_menu / spawn_template_menu\n"
                + "#\n"
                + "# Opened instead of directly toggling practice mode / setting your spawn (see the\n"
                + "# \"practice_template_menu\" and \"spawn_template_menu\" actions on the Practice Mode /\n"
                + "# Spawn Position buttons in fastbuilder_settings.yml). Each holds up to 5 saved\n"
                + "# templates PER PLAYER, PER FASTBUILDER MODE (arena) - templates saved while playing\n"
                + "# one mode never show up while playing a different one - and persist across restarts.\n"
                + "#\n"
                + "# \"template-slots\" lists which inventory slots (in order, slot 1's item first) render\n"
                + "# the 5 template buttons - the plugin fills these dynamically every time the menu is\n"
                + "# opened (empty slots use \"template-empty\", saved ones use \"template-filled\"), so\n"
                + "# whatever material/name/lore you put directly under those slot numbers in \"items\"\n"
                + "# below is only ever shown for a split second before being overwritten - configure\n"
                + "# \"template-empty\"/\"template-filled\" instead, not \"items\" entries at those slots.\n"
                + "#\n"
                + "# template-empty/template-filled support placeholders in name/lore:\n"
                + "#   %slot%   - the template's slot number (1-5)\n"
                + "#   %blocks% - (practice_template_menu only) how many blocks are saved in that slot\n"
                + "#   %x% %y% %z% %yaw% %pitch% - (spawn_template_menu only) the saved position, relative\n"
                + "#                                to island spawn\n"
                + "#\n"
                + "# Every template slot's action is already wired up for you as a GLOBAL action - it also\n"
                + "# works if pasted into ANY OTHER menu in this file, not just these two:\n"
                + "#   action: practice_template\n"
                + "#   practicetemplate: 1     # 1-5, loads/deletes (shift-click) that saved template\n"
                + "#   action: spawn_template\n"
                + "#   spawntemplate: 1        # 1-5, loads/deletes (shift-click) that saved spawn\n"
                + "# Saving to the first free slot (max 5) is action: practice_template_save /\n"
                + "# spawn_template_save - also global, usable in any menu.\n"
                + "# =========================================================================\n"
                + "practice_template_menu:\n"
                + "  title: \"&aPractice Templates\"\n"
                + "  size: 27\n"
                + "  max-templates: 5\n"
                + "  template-slots: [11, 12, 13, 14, 15]\n"
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
                + "      name: \"&aPractice Templates\"\n"
                + "      lore:\n"
                + "        - \"&7Save your current practice block\"\n"
                + "        - \"&7layout, then load it back any time.\"\n"
                + "        - \"&7Up to 5 templates per mode.\"\n"
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
                + "    11:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 1\"\n"
                + "      action: practice_template\n"
                + "      practicetemplate: 1\n"
                + "    12:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 2\"\n"
                + "      action: practice_template\n"
                + "      practicetemplate: 2\n"
                + "    13:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 3\"\n"
                + "      action: practice_template\n"
                + "      practicetemplate: 3\n"
                + "    14:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 4\"\n"
                + "      action: practice_template\n"
                + "      practicetemplate: 4\n"
                + "    15:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 5\"\n"
                + "      action: practice_template\n"
                + "      practicetemplate: 5\n"
                + "    21:\n"
                + "      material: LIME_DYE\n"
                + "      name: \"&aToggle Practice Mode\"\n"
                + "      lore:\n"
                + "        - \"&7Click to turn practice mode\"\n"
                + "        - \"&7on or off.\"\n"
                + "      action: toggle_practice_mode\n"
                + "    23:\n"
                + "      material: WRITABLE_BOOK\n"
                + "      name: \"&bSave Current Layout\"\n"
                + "      lore:\n"
                + "        - \"&7Save your currently placed\"\n"
                + "        - \"&7practice blocks as a new template.\"\n"
                + "        - \"\"\n"
                + "        - \"&eClick to save\"\n"
                + "      action: practice_template_save\n"
                + "    26:\n"
                + "      material: BARRIER\n"
                + "      name: \"&cClose\"\n"
                + "      action: close_menu\n"
                + "  template-empty:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7Empty Slot %slot%\"\n"
                + "    lore:\n"
                + "      - \"&8No template saved here yet\"\n"
                + "      - \"&8Use \\\"Save Current Layout\\\" to fill it\"\n"
                + "  template-filled:\n"
                + "    material: LIME_STAINED_GLASS_PANE\n"
                + "    name: \"&aTemplate %slot%\"\n"
                + "    lore:\n"
                + "      - \"&7Blocks: &f%blocks%\"\n"
                + "      - \"\"\n"
                + "      - \"&eClick to load\"\n"
                + "      - \"&cShift-click to delete\"\n"
                + "\n"
                + "spawn_template_menu:\n"
                + "  title: \"&eSpawn Templates\"\n"
                + "  size: 27\n"
                + "  max-templates: 5\n"
                + "  template-slots: [11, 12, 13, 14, 15]\n"
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
                + "      material: PUFFERFISH\n"
                + "      name: \"&eSpawn Templates\"\n"
                + "      lore:\n"
                + "        - \"&7Save your current position as\"\n"
                + "        - \"&7a custom spawn, then load it\"\n"
                + "        - \"&7back any time. Up to 5 per mode.\"\n"
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
                + "    11:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 1\"\n"
                + "      action: spawn_template\n"
                + "      spawntemplate: 1\n"
                + "    12:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 2\"\n"
                + "      action: spawn_template\n"
                + "      spawntemplate: 2\n"
                + "    13:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 3\"\n"
                + "      action: spawn_template\n"
                + "      spawntemplate: 3\n"
                + "    14:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 4\"\n"
                + "      action: spawn_template\n"
                + "      spawntemplate: 4\n"
                + "    15:\n"
                + "      material: GRAY_STAINED_GLASS_PANE\n"
                + "      name: \"&7Empty Slot 5\"\n"
                + "      action: spawn_template\n"
                + "      spawntemplate: 5\n"
                + "    21:\n"
                + "      material: PUFFERFISH\n"
                + "      name: \"&eSet Temporary Spawn\"\n"
                + "      lore:\n"
                + "        - \"&7Left click to set your spawn\"\n"
                + "        - \"&7to your current position\"\n"
                + "        - \"&7Right click to reset it\"\n"
                + "      action: spawn_position\n"
                + "    23:\n"
                + "      material: WRITABLE_BOOK\n"
                + "      name: \"&bSave Current Position\"\n"
                + "      lore:\n"
                + "        - \"&7Save your current position\"\n"
                + "        - \"&7as a new spawn template.\"\n"
                + "        - \"\"\n"
                + "        - \"&eClick to save\"\n"
                + "      action: spawn_template_save\n"
                + "    26:\n"
                + "      material: BARRIER\n"
                + "      name: \"&cClose\"\n"
                + "      action: close_menu\n"
                + "  template-empty:\n"
                + "    material: GRAY_STAINED_GLASS_PANE\n"
                + "    name: \"&7Empty Slot %slot%\"\n"
                + "    lore:\n"
                + "      - \"&8No template saved here yet\"\n"
                + "      - \"&8Use \\\"Save Current Position\\\" to fill it\"\n"
                + "  template-filled:\n"
                + "    material: PUFFERFISH\n"
                + "    name: \"&eTemplate %slot%\"\n"
                + "    lore:\n"
                + "      - \"&7X: &f%x%\"\n"
                + "      - \"&7Y: &f%y%\"\n"
                + "      - \"&7Z: &f%z%\"\n"
                + "      - \"&7Yaw: &f%yaw% &7Pitch: &f%pitch%\"\n"
                + "      - \"\"\n"
                + "      - \"&eClick to load\"\n"
                + "      - \"&cShift-click to delete\"\n"
                + "\n"
                + "block_shop:\n"
                + "  title: \"&dBlock Shop\"\n"
                + "  size: 54\n"
                + "  items: {}\n";
        return defaultMenuConfig;
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
            practiceSlot.set("action", "practice_template_menu");
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
            spawn.set("action", "spawn_template_menu");
            spawn.set("lore", List.of("&7Click to manage your spawn", "&7position and saved templates"));
            modified = true;
        }

        // Migration for existing installs: the "Practice Mode"/"Spawn Position" buttons in
        // fastbuilder_settings_menu used to act directly (toggle_practice_mode / spawn_position),
        // but now open the new practice_template_menu/spawn_template_menu instead (which still
        // contain the original toggle/set-spawn buttons inside them, plus save/load/delete
        // template slots). Rewrite any slot in fastbuilder_settings_menu still using the old
        // direct actions so upgraded installs get the new menu-based flow automatically, without
        // touching those same action strings anywhere else (e.g. inside the new template menus
        // themselves, which intentionally reuse them for their internal toggle/set-spawn buttons).
        for (String slotKey : new ArrayList<>(fastbuilderItems.getKeys(false))) {
            ConfigurationSection itemSection = fastbuilderItems.getConfigurationSection(slotKey);
            if (itemSection == null) {
                continue;
            }
            String existingAction = itemSection.getString("action");
            if ("toggle_practice_mode".equals(existingAction)) {
                itemSection.set("action", "practice_template_menu");
                modified = true;
            } else if ("spawn_position".equals(existingAction)) {
                itemSection.set("action", "spawn_template_menu");
                modified = true;
            }
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
        saveSplitMenuConfigurations();
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
        // Same auto-generation guarantee as the constructor: if config.yml or any menu file were
        // deleted since the server started, /fb reload should recreate them (and re-run the
        // self-healing "ensure" steps for individual built-in menu sections) instead of just
        // loading whatever's left - which used to be nothing, leaving menuConfiguration empty
        // in memory until the next full server restart.
        createDefaultConfigIfMissing();
        createDefaultMenuFilesIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(configFile);
        this.menuConfiguration = loadMergedMenuConfiguration();
        this.arenaSettingsConfiguration = loadArenaSettingsConfiguration();
        ensureCosmeticsShopMenuEntry();
        ensurePracticeModeMenuEntry();
        ensureLeaveConfirmationMenuEntry();
        ensureArenaSettingsFile();
        ensurePracticeBlockHotbarEntry();
        ensurePracticeCheckpointHotbarEntry();
        ensureIslandNpcConfigSection();
        ensureIslandNpcDebugToggle();
        ensureReplayHologramConfigSection();
        ensureAutoAddModeToggle();
        ensurePeriodicMessagesConfigSection();
    }

    /**
     * If enabled ("auto-add-new-modes-to-mode-switcher" in config.yml, true by default), adds a
     * "mode" button for a brand-new arena into mode_changer_menu.yml's first genuinely free
     * (entirely unconfigured - border decorations count as taken) slot, up to that menu's
     * configured size. Does nothing, silently but logged, if every slot is already taken - the
     * menu is never resized or overwritten to make room. Only ever call this once, right when an
     * arena is actually created (see FBCommand's /fb add) - never during arena loading at
     * startup, or every existing arena would get added on every single restart.
     */
    public void autoAddModeToSwitcherMenuIfEnabled(String arenaName) {
        if (arenaName == null || arenaName.isBlank()) {
            return;
        }
        if (!configuration.getBoolean("auto-add-new-modes-to-mode-switcher", true)) {
            return;
        }
        ConfigurationSection menuSection = menuConfiguration.getConfigurationSection("mode_changer_menu");
        if (menuSection == null) {
            return;
        }
        int size = menuSection.getInt("size", 54);
        ConfigurationSection items = menuSection.getConfigurationSection("items");
        if (items == null) {
            items = menuSection.createSection("items");
        }
        int freeSlot = -1;
        for (int slot = 0; slot < size; slot++) {
            if (!items.contains(String.valueOf(slot))) {
                freeSlot = slot;
                break;
            }
        }
        if (freeSlot < 0) {
            plugin.getLogger().info("Not auto-adding \"" + arenaName + "\" to mode_changer_menu - every slot is "
                    + "already taken. Free one up and add it manually if you'd like it there: material/name, "
                    + "action: mode, mode: " + arenaName + ".");
            return;
        }
        ConfigurationSection newItem = items.createSection(String.valueOf(freeSlot));
        newItem.set("material", "GRASS_BLOCK");
        newItem.set("name", "&e" + arenaName);
        newItem.set("lore", java.util.List.of("&7Click to play " + arenaName));
        newItem.set("action", "mode");
        newItem.set("mode", arenaName);
        saveSplitMenuConfigurations();
        plugin.getLogger().info("Added \"" + arenaName + "\" to mode_changer_menu at slot " + freeSlot
                + " (auto-add-new-modes-to-mode-switcher is enabled in config.yml).");
    }

    public String getArenaStartMode(String arenaName) {
        return resolveArenaSetting(arenaName, "start", DEFAULT_ARENA_START_MODE, Set.of("BLOCK", "MOVE"));
    }

    public String getArenaFinishMode(String arenaName) {
        return resolveArenaSetting(arenaName, "finish", DEFAULT_ARENA_FINISH_MODE, Set.of("PLATE", "BED"));
    }

    /**
     * Returns "STRAIGHT" or "DIAGONAL" for this arena/mode - whether the build boundary around
     * each island is measured along the normal X/Z axes (straight) or rotated 45 degrees to the
     * right (diagonal/"inclined"), same self-healing "start"/"finish" pattern above. Seeded, the
     * first time arena_settings.yml is generated for a given arena, from that arena's own
     * "layout" (arenas.yml) - an arena created with a diagonal island layout defaults to a
     * diagonal boundary direction too, though the two can be changed independently afterward by
     * editing arena_settings.yml directly.
     */
    public String getArenaDirection(String arenaName) {
        return resolveArenaSetting(arenaName, "direction", DEFAULT_ARENA_DIRECTION, Set.of("STRAIGHT", "DIAGONAL"));
    }

    /**
     * The suspicious-time flag threshold (seconds) for this arena/mode - a completed, non-practice
     * run whose RAW finish time (not personal best) is <= this value gets flagged to staff. 0.000
     * (the default when unset/invalid) disables flagging for that mode entirely.
     */
    public double getArenaMaxTime(String arenaName) {
        if (arenaName == null || arenaName.isBlank()) {
            return DEFAULT_ARENA_MAX_TIME;
        }
        String normalizedArenaName = arenaName.toLowerCase(Locale.ROOT);
        String raw = readArenaSettingValue(normalizedArenaName, "max-time");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ARENA_MAX_TIME;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("Invalid max-time value '" + raw + "' for arena '" + arenaName + "'. Falling back to 0.000 (disabled).");
            return DEFAULT_ARENA_MAX_TIME;
        }
    }

    public boolean isArenaDirectionDiagonal(String arenaName) {
        return "DIAGONAL".equals(getArenaDirection(arenaName));
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

    /**
     * Public trigger for the same self-healing arena_settings.yml pass the constructor/reload()
     * already run - called right after /fb add creates a brand-new arena (see FBCommand#handleAdd)
     * so that arena's "start"/"finish"/"direction" entries (the latter seeded from whatever layout
     * was just passed to /fb add) show up in arena_settings.yml immediately, instead of only after
     * the next /fb reload or server restart.
     */
    public void ensureArenaSettingsEntryExists() {
        ensureArenaSettingsFile();
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
            boolean changed = false;
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
                    changed = true;
                }
                if (arenaSection.getString("finish") == null || arenaSection.getString("finish").isBlank()) {
                    arenaSection.set("finish", DEFAULT_ARENA_FINISH_MODE);
                    changed = true;
                }
                if (arenaSection.getString("direction") == null || arenaSection.getString("direction").isBlank()) {
                    // Seed from this arena's own "layout" in arenas.yml (already stored there for
                    // every arena - see Arena#getLayout/StorageManager) so an arena created with
                    // /fb add ... diagonal starts out with a diagonal boundary too, instead of
                    // silently defaulting to straight and needing a second manual edit.
                    String arenaLayout = arenasConfiguration.getString(arenaName + ".layout", "straight");
                    boolean diagonalLayout = arenaLayout != null && arenaLayout.trim().equalsIgnoreCase("diagonal");
                    arenaSection.set("direction", diagonalLayout ? "DIAGONAL" : DEFAULT_ARENA_DIRECTION);
                    changed = true;
                }
                if (!arenaSection.contains("max-time")) {
                    arenaSection.set("max-time", DEFAULT_ARENA_MAX_TIME);
                    changed = true;
                }
            }
            if (changed) {
                // Unlike the rest of this "ensure" method (which previously only ever patched the
                // in-memory arenaSettingsConfiguration, silently never writing "start"/"finish"
                // back to arena_settings.yml on disk until some unrelated save happened to fire
                // later), an actually-missing "direction:" line needs to show up in the file for
                // server owners to find and edit it - so persist whenever anything above changed.
                saveArenaSettingsConfiguration();
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
                + "# the plugin's normal defaults (BLOCK start, PLATE finish, STRAIGHT direction, no\n"
                + "# island cosmetics).\n"
                + "#\n"
                + "# START: BLOCK or MOVE - how an attempt begins (placing the first block, or\n"
                + "#        simply moving off the starting island)\n"
                + "# FINISH: PLATE or BED - how an attempt is detected as finished\n"
                + "# DIRECTION: STRAIGHT or DIAGONAL - whether this mode's build boundary is measured\n"
                + "#            along the normal X/Z axes (STRAIGHT) or rotated 45 degrees to the right\n"
                + "#            (DIAGONAL/\"inclined\") - a boundary of, say, 5 blocks to the right takes\n"
                + "#            5 actual diagonal blocks to reach in DIAGONAL mode, not 5 blocks along a\n"
                + "#            single axis. Defaults to this arena's own /fb add layout (straight/\n"
                + "#            diagonal) the first time this file is generated, but can be changed\n"
                + "#            independently afterward.\n"
                + "#\n"
                + "# max-time: a time (in seconds, e.g. 2.300) low enough that finishing at or under it is\n"
                + "#           basically impossible legitimately - any completed (non-practice) run whose\n"
                + "#           finish time is <= this value gets flagged to online staff (anyone with the\n"
                + "#           \"skepifb.admin.alerts\" permission) as possible cheating. This checks the RAW\n"
                + "#           finish time itself, NOT the player's personal best. Defaults to 0.000, which\n"
                + "#           disables flagging entirely for that mode (0.000 or faster can't actually\n"
                + "#           happen), so this is opt-in per mode - set it once you know roughly what a\n"
                + "#           legitimately fast time looks like for that mode.\n"
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
                + "#   direction: STRAIGHT\n"
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

    /**
     * Returns "replay-hologram.lines" top-to-bottom exactly as authored in config.yml (up to
     * whatever length the admin gave it - trimming to 10 and filtering out "none"/blank entries is
     * TimerManager's job at render time, not this getter's). Falls back to
     * DEFAULT_REPLAY_HOLOGRAM_LINES if the key is missing or empty, which should only ever happen
     * in the brief window before ensureReplayHologramConfigSection() has run.
     */
    public List<String> getReplayHologramLines() {
        List<String> lines = configuration.getStringList("replay-hologram.lines");
        if (lines == null || lines.isEmpty()) {
            return DEFAULT_REPLAY_HOLOGRAM_LINES;
        }
        return lines;
    }

    /**
     * Returns the 3 configurable "periodic-messages.messages" entries exactly as authored in
     * config.yml. Filtering out "none"/blank entries is PeriodicMessageManager's job at broadcast
     * time, not this getter's - same convention as getReplayHologramLines() above. Falls back to
     * DEFAULT_PERIODIC_MESSAGES if the key is missing or empty, which should only ever happen in
     * the brief window before ensurePeriodicMessagesConfigSection() has run.
     */
    public List<String> getPeriodicMessages() {
        List<String> messages = configuration.getStringList("periodic-messages.messages");
        if (messages == null || messages.isEmpty()) {
            return DEFAULT_PERIODIC_MESSAGES;
        }
        return messages;
    }

    /**
     * Returns "periodic-messages.interval-seconds" - how often, in seconds, a random periodic
     * chat message is broadcast. Defaults to 600 (10 minutes) if the key is missing or invalid.
     * Set this to 1 in config.yml (then /fb reload) to quickly test messages.
     */
    public int getPeriodicMessageIntervalSeconds() {
        int seconds = configuration.getInt("periodic-messages.interval-seconds", DEFAULT_PERIODIC_MESSAGE_INTERVAL_SECONDS);
        return seconds > 0 ? seconds : DEFAULT_PERIODIC_MESSAGE_INTERVAL_SECONDS;
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
