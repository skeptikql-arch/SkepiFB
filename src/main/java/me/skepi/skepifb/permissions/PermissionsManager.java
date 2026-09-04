package me.skepi.skepifb.permissions;

import me.skepi.skepifb.SkepiFBPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Loads and exposes permissions.yml - a single, centralized file describing every optional
 * permission gate in the plugin: which node (if any) each /fb subcommand and /stats requires,
 * which node each menu/hotbar action requires, which node (if any) individual shop items require
 * in addition to their normal coin price, and the tiered replay feature system (replay count cap,
 * and whether the last-attempt/favorite/personal-best shortcuts and the sorter are available).
 *
 * Every lookup here is optional-by-default: an unlisted or blank permission value means "allowed
 * for everyone", so a fresh/unedited permissions.yml changes nothing about default behavior.
 */
public class PermissionsManager {

    private final SkepiFBPlugin plugin;
    private final File permissionsFile;
    private YamlConfiguration configuration;

    public PermissionsManager(SkepiFBPlugin plugin) {
        this.plugin = plugin;
        this.permissionsFile = new File(plugin.getDataFolder(), "permissions.yml");
        createDefaultIfMissing();
        this.configuration = loadConfigurationSafely();
    }

    /**
     * Reloads permissions.yml from disk. Only regenerates the default file if it is genuinely
     * missing (mirrors the same non-destructive pattern used by ConfigManager/ShopManager's
     * reload() - never overwrites an existing, live-edited permissions.yml) - UNLESS the existing
     * file is actually corrupt (invalid YAML), in which case loadConfigurationSafely() below backs
     * it up and regenerates it. Never overwrites a file that merely fails to parse without keeping
     * a copy first.
     */
    public void reload() {
        createDefaultIfMissing();
        this.configuration = loadConfigurationSafely();
    }

    /**
     * Loads permissions.yml, self-healing if the file on disk is corrupt (invalid YAML - e.g. a
     * stray edit that broke a line like "shop-items {}" missing its colon). This used to be a
     * plain YamlConfiguration.loadConfiguration(permissionsFile) call with no try/catch anywhere
     * in this class: a corrupt file would either crash plugin startup entirely (constructor) or,
     * worse, throw out of reload() and get silently swallowed by FBCommand's "catch (Throwable
     * ignored)" around the reload() call - leaving `configuration` on its PREVIOUS in-memory value
     * forever, with no error surfaced to the console after the first one. Every optional-permission
     * lookup in this file (hasCommandPermission/hasActionPermission/canPurchaseShopItem/
     * resolveReplayTier) treats a missing/unreadable value as "open to everyone" or the hardcoded
     * default-replay-tier defaults, so a permanently-stuck-on-broken-file server would see exactly
     * this symptom: personal-best replay, favorite replay, and the replay sorter permanently locked
     * (they default to false) even for players who were actually granted a replay-tiers node,
     * because the whole file - including everything below the first syntax error - never
     * successfully re-parsed after the file broke, no matter how many times /fb reload ran.
     * <p>
     * Now: any parse failure is caught here, logged clearly (so an admin editing the file gets
     * immediate, actionable feedback instead of silence), the broken file is renamed to
     * permissions.yml.broken-<timestamp> as a backup (nothing is ever discarded), and a fresh valid
     * default is generated and loaded in its place so the plugin keeps working correctly instead of
     * quietly running on stale settings.
     */
    private YamlConfiguration loadConfigurationSafely() {
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(permissionsFile);
            if (migrateLegacyReplayTiersIfNeeded(loaded)) {
                try {
                    loaded.save(permissionsFile);
                } catch (IOException saveEx) {
                    plugin.getLogger().warning("Converted replay-tiers to the new format in memory, but could "
                            + "not save permissions.yml (" + saveEx.getMessage() + "); it will be re-converted "
                            + "again on next reload/restart.");
                }
            }
            return loaded;
        } catch (Exception ex) {
            plugin.getLogger().warning("permissions.yml could not be parsed (" + ex.getMessage() + "). "
                    + "Until this is fixed, every optional permission gate in it - including replay-tiers "
                    + "(personal-best replay, favorite replay, and the replay sorter default to LOCKED) - "
                    + "falls back to hardcoded defaults instead of your configured nodes. "
                    + "Backing up the broken file and regenerating a fresh default now.");
            backupAndRegenerate();
            try {
                YamlConfiguration recovered = new YamlConfiguration();
                recovered.load(permissionsFile);
                return recovered;
            } catch (Exception fatal) {
                plugin.getLogger().severe("Could not load even a freshly-regenerated permissions.yml: "
                        + fatal.getMessage() + ". Falling back to an empty in-memory configuration "
                        + "(everything will behave as fully open/default until this is resolved).");
                return new YamlConfiguration();
            }
        }
    }

    private void backupAndRegenerate() {
        try {
            File backup = new File(permissionsFile.getParentFile(),
                    "permissions.yml.broken-" + System.currentTimeMillis());
            Files.move(permissionsFile.toPath(), backup.toPath());
            plugin.getLogger().warning("Your old permissions.yml was backed up to " + backup.getName()
                    + " - your custom nodes are still in there, copy them back in once the syntax is fixed.");
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not back up the broken permissions.yml (" + ex.getMessage()
                    + "); it will be overwritten with a fresh default.");
        }
        createDefaultIfMissing();
    }

    // =========================================================================================
    // Commands
    // =========================================================================================

    /**
     * Checks whether the given sender may run a command gated under the given key (see the
     * "commands:" section of permissions.yml, e.g. "fb.reload", "stats"). Console/command blocks
     * always pass - permission gating only applies to real players. A blank or missing node in
     * permissions.yml means the command is open to everyone.
     */
    public boolean hasCommandPermission(CommandSender sender, String key) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        String node = configuration.getString("commands." + key, "");
        if (node == null || node.isBlank()) {
            return true;
        }
        return player.hasPermission(node);
    }

    // =========================================================================================
    // Menu / hotbar actions
    // =========================================================================================

    /**
     * Checks whether the player may trigger a given menu/hotbar action (see the "actions:"
     * section of permissions.yml, e.g. "respawn", "island_menu", "mode"). A blank or missing node
     * means the action is open to everyone. Used identically by both the physical hotbar dispatch
     * and the generic menu-click dispatch, so an action gated here is gated everywhere it appears.
     */
    public boolean hasActionPermission(Player player, String actionKey) {
        if (player == null || actionKey == null) {
            return true;
        }
        String node = configuration.getString("actions." + actionKey, "");
        if (node == null || node.isBlank()) {
            return true;
        }
        return player.hasPermission(node);
    }

    // =========================================================================================
    // Shop items / cosmetics
    // =========================================================================================

    /**
     * Checks whether the player may purchase/equip a specific shop item, in addition to its
     * normal coin price (see the "shop-items:" section of permissions.yml, keyed
     * "<shop_id>.<category_key>.<item_key>"). A blank or missing node means anyone can buy it
     * (subject to its price) - this is purely an optional extra gate, not a replacement for price.
     */
    public boolean canPurchaseShopItem(Player player, String shopId, String categoryKey, String itemKey) {
        if (player == null) {
            return true;
        }
        String path = "shop-items." + safeKey(shopId) + "." + safeKey(categoryKey) + "." + safeKey(itemKey);
        String node = configuration.getString(path, "");
        if (node == null || node.isBlank()) {
            return true;
        }
        return player.hasPermission(node);
    }

    private String safeKey(String value) {
        return value == null ? "" : value;
    }

    // =========================================================================================
    // Replay feature tiers
    // =========================================================================================

    /**
     * Resolves the effective replay feature settings for a player: starts from
     * "default-replay-tier", then layers in every entry under "replay-tiers" whose "permission"
     * field the player holds, taking the MOST GENEROUS value across every tier that applies
     * (higher replay count wins, and any boolean feature is enabled if any tier the player holds
     * enables it). This means stacking multiple rank permissions only ever helps a player, never
     * hurts them.
     * <p>
     * "replay-tiers" is a LIST of entries (each with a "permission:" field), not a map keyed by
     * the permission node itself. That used to be the bug behind "pb replay/favorite replay/
     * replay sorter always locked even with the permission": a real permission node like
     * "skepifb.vip" or "permission.pro.rank" contains dots, and Bukkit's YAML loader treats a DOT
     * IN A MAP KEY as a path separator - it silently splits a key like "permission.pro.rank:" into
     * three levels of NESTED sections (permission -> pro -> rank) while parsing the file, the same
     * way createSection("a.b.c") would. getKeys(false) on the old "replay-tiers:" map then only
     * ever returned the first fragment before the first dot (e.g. "permission", or "skepifb" for a
     * node like "skepifb.vip") - never the real, full node - so player.hasPermission(...) was
     * always being asked about a garbled, truncated node nobody actually has, no matter how
     * correctly the file itself was written or how correctly the real permission was granted. A
     * list of {permission: "...", ...} entries sidesteps this entirely: the node is a plain STRING
     * VALUE, never a YAML key, so it's never subject to dot-splitting.
     */
    public ReplayTierSettings resolveReplayTier(Player player) {
        int replayCount = configuration.getInt("default-replay-tier.replay-count", 5);
        boolean lastAttempt = configuration.getBoolean("default-replay-tier.last-attempt", true);
        boolean favoriteReplay = configuration.getBoolean("default-replay-tier.favorite-replay", false);
        boolean personalBestReplay = configuration.getBoolean("default-replay-tier.personal-best-replay", false);
        boolean replaySorter = configuration.getBoolean("default-replay-tier.replay-sorter", false);

        if (player != null) {
            for (java.util.Map<?, ?> tier : configuration.getMapList("replay-tiers")) {
                Object permissionValue = tier.get("permission");
                String node = permissionValue == null ? null : permissionValue.toString();
                if (node == null || node.isBlank() || !player.hasPermission(node)) {
                    continue;
                }
                replayCount = Math.max(replayCount, asInt(tier.get("replay-count"), replayCount));
                lastAttempt = lastAttempt || asBoolean(tier.get("last-attempt"));
                favoriteReplay = favoriteReplay || asBoolean(tier.get("favorite-replay"));
                personalBestReplay = personalBestReplay || asBoolean(tier.get("personal-best-replay"));
                replaySorter = replaySorter || asBoolean(tier.get("replay-sorter"));
            }
        }
        return new ReplayTierSettings(replayCount, lastAttempt, favoriteReplay, personalBestReplay, replaySorter);
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean bool && bool;
    }

    public static final class ReplayTierSettings {
        public final int replayCount;
        public final boolean lastAttempt;
        public final boolean favoriteReplay;
        public final boolean personalBestReplay;
        public final boolean replaySorter;

        public ReplayTierSettings(int replayCount, boolean lastAttempt, boolean favoriteReplay,
                                   boolean personalBestReplay, boolean replaySorter) {
            this.replayCount = Math.max(1, replayCount);
            this.lastAttempt = lastAttempt;
            this.favoriteReplay = favoriteReplay;
            this.personalBestReplay = personalBestReplay;
            this.replaySorter = replaySorter;
        }
    }

    // =========================================================================================
    // File generation
    // =========================================================================================

    private void createDefaultIfMissing() {
        if (permissionsFile.exists()) {
            return;
        }
        try {
            File parent = permissionsFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        } catch (Throwable ignored) {
        }
        String content = "# SkepiFB permissions configuration file\n"
                + "#\n"
                + "# Every permission node referenced below is checked with the server's normal permission\n"
                + "# system (Bukkit permissions / LuckPerms / etc) - this file does not grant anything by\n"
                + "# itself, it only decides WHICH node (if any) is required for each thing it covers. A\n"
                + "# blank value, or leaving a key out entirely, means that thing stays open to everyone,\n"
                + "# exactly like before this file existed - nothing here changes default behavior until\n"
                + "# you actually fill in a node and grant it to a group/player.\n"
                + "#\n"
                + "# =========================================================================\n"
                + "# COMMANDS - one entry per /fb subcommand, plus \"stats\" for /stats.\n"
                + "# =========================================================================\n"
                + "commands:\n"
                + "  fb.help: \"\"\n"
                + "  fb.add: \"skepifb.admin.arena\"\n"
                + "  fb.remove: \"skepifb.admin.arena\"\n"
                + "  fb.setfacing: \"skepifb.admin.arena\"\n"
                + "  fb.list: \"\"\n"
                + "  fb.join: \"\"\n"
                + "  fb.leave: \"\"\n"
                + "  fb.menu: \"skepifb.admin.menu\"\n"
                + "  fb.reload: \"skepifb.admin.reload\"\n"
                + "  fb.replayinfo: \"\"\n"
                + "  fb.replays: \"skepifb.admin.replays\"\n"
                + "  fb.coins: \"skepifb.admin.coins\"\n"
                + "  fb.xp: \"skepifb.admin.xp\"\n"
                + "  fb.lb: \"skepifb.admin.leaderboard\"\n"
                + "  fb.island: \"skepifb.admin.island\"\n"
                + "  fb.test: \"skepifb.admin.test\"\n"
                + "  stats: \"\"\n"
                + "  spectate: \"\"\n"
                + "  leaderboard: \"\"\n"
                + "\n"
                + "# =========================================================================\n"
                + "# ACTIONS - gates a menu/hotbar item's \"action:\" value, wherever it's used (hotbar\n"
                + "# slots, built-in menus, custom_menus.yml). A player without the required node simply\n"
                + "# has no effect when clicking/using that item - it fails silently, like the item isn't\n"
                + "# wired to anything.\n"
                + "# =========================================================================\n"
                + "actions:\n"
                + "  respawn: \"\"\n"
                + "  island_menu: \"\"\n"
                + "  replays_menu: \"\"\n"
                + "  settings_menu: \"\"\n"
                + "  leave: \"\"\n"
                + "  mode: \"\"\n"
                + "  toggle_practice_mode: \"\"\n"
                + "  stats: \"\"\n"
                + "  leaderboard_toggle: \"\"\n"
                + "\n"
                + "# =========================================================================\n"
                + "# SHOP ITEMS / COSMETICS - optional extra gate on top of a shop item's normal coin\n"
                + "# price. Key format: <shop_id>.<category_key>.<item_key>, exactly as they appear in\n"
                + "# shop.yml. Only list items you actually want to restrict - everything else stays\n"
                + "# purchasable by anyone who can afford it. Example (commented out):\n"
                + "#   shop-items:\n"
                + "#     block_shop:\n"
                + "#       variety:\n"
                + "#         ancient_debris: \"skepifb.vip\"\n"
                + "# =========================================================================\n"
                + "shop-items: {}\n"
                + "\n"
                + "# =========================================================================\n"
                + "# REPLAY FEATURE TIERS\n"
                + "# default-replay-tier applies to every player with none of the permissions listed under\n"
                + "# replay-tiers below. replay-tiers is a LIST - each entry names one permission node (as a\n"
                + "# \"permission:\" field, NOT as the YAML key - a real permission node like \"skepifb.vip\"\n"
                + "# contains dots, and dots in an actual YAML KEY get silently split into nested sections by\n"
                + "# the config loader, which is what made this always-locked before) plus the settings that\n"
                + "# node unlocks. Anyone holding it gets that tier's settings, and if a player holds more than\n"
                + "# one tier's permission, they get the MOST GENEROUS value from each setting across every\n"
                + "# tier they hold (so stacking rank permissions only ever helps, never hurts).\n"
                + "#   replay-count         - max replays kept per arena; oldest are deleted first once\n"
                + "#                          over the cap (the player's current favorite and personal-best\n"
                + "#                          replay, if any, are never auto-deleted)\n"
                + "#   last-attempt         - enables the \"jump to most recent attempt\" replay shortcut\n"
                + "#   favorite-replay      - enables favoriting a replay and the favorite shortcut slot\n"
                + "#   personal-best-replay - enables the \"jump to your PB replay\" shortcut slot\n"
                + "#   replay-sorter        - enables the sort-order button (Most Recent/Best/Worst/Oldest)\n"
                + "# Add as many entries as you like, one per rank/permission:\n"
                + "#   replay-tiers:\n"
                + "#     - permission: \"skepifb.vip\"\n"
                + "#       replay-count: 10\n"
                + "#       last-attempt: true\n"
                + "#       favorite-replay: true\n"
                + "#       personal-best-replay: false\n"
                + "#       replay-sorter: false\n"
                + "# =========================================================================\n"
                + "default-replay-tier:\n"
                + "  replay-count: 5\n"
                + "  last-attempt: true\n"
                + "  favorite-replay: false\n"
                + "  personal-best-replay: false\n"
                + "  replay-sorter: false\n"
                + "\n"
                + "replay-tiers:\n"
                + "  - permission: \"permission.pro.rank\"\n"
                + "    replay-count: 20\n"
                + "    last-attempt: true\n"
                + "    favorite-replay: false\n"
                + "    personal-best-replay: true\n"
                + "    replay-sorter: true\n";
        try {
            Files.write(permissionsFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default permissions.yml: " + ex.getMessage());
        }
    }

    // =========================================================================================
    // Legacy replay-tiers migration (map-of-nested-sections -> list of {permission: ...})
    // =========================================================================================

    private static final java.util.Set<String> TIER_FIELD_KEYS = java.util.Set.of(
            "replay-count", "last-attempt", "favorite-replay", "personal-best-replay", "replay-sorter");

    /**
     * If "replay-tiers" on disk is still the OLD, broken format (a map, auto-nested by the dots in
     * each permission node - see the big comment on resolveReplayTier() above) rather than the new
     * list-of-{permission: ...} format, this reconstructs the original dotted permission node for
     * every leaf it finds by walking back down the nested sections the loader created, converts
     * everything to the new list format, and saves the file - so an existing install with
     * previously-non-functional replay-tiers self-heals into a working one without anyone having
     * to hand-rewrite their file. A leaf is any section that directly contains at least one of the
     * five known tier fields; everything above that is just reconstructed dotted-node path,
     * regardless of how many segments the original permission node had.
     */
    private boolean migrateLegacyReplayTiersIfNeeded(YamlConfiguration config) {
        Object raw = config.get("replay-tiers");
        if (!(raw instanceof ConfigurationSection legacySection)) {
            // Already a list (or missing/empty) - nothing to migrate.
            return false;
        }
        java.util.List<java.util.Map<String, Object>> migrated = new java.util.ArrayList<>();
        collectLegacyTierLeaves(legacySection, "", migrated);
        config.set("replay-tiers", migrated);
        plugin.getLogger().info("permissions.yml's replay-tiers was still in the old format, where a dotted "
                + "permission node used as a YAML key gets silently mangled by the config loader (this is why "
                + "personal-best/favorite/sorter replay features could stay locked even with the permission "
                + "granted). Converted " + migrated.size() + " tier(s) to the new, working list format and saved the file.");
        return true;
    }

    private void collectLegacyTierLeaves(ConfigurationSection section, String prefix, java.util.List<java.util.Map<String, Object>> out) {
        if (section == null) {
            return;
        }
        boolean isLeaf = false;
        for (String field : TIER_FIELD_KEYS) {
            if (section.contains(field)) {
                isLeaf = true;
                break;
            }
        }
        if (isLeaf) {
            java.util.Map<String, Object> tier = new java.util.LinkedHashMap<>();
            tier.put("permission", prefix);
            for (String field : TIER_FIELD_KEYS) {
                if (section.contains(field)) {
                    tier.put(field, section.get(field));
                }
            }
            out.add(tier);
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                collectLegacyTierLeaves(child, prefix.isEmpty() ? key : prefix + "." + key, out);
            }
        }
    }
}
