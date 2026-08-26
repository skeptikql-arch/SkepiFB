package me.skepi.skepifb.tags;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.leaderboard.LeaderboardManager;
import me.skepi.skepifb.util.LuckPermsUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks which leaderboard-position tag (if any) each player currently has equipped, persists that
 * choice across restarts/rejoins, and keeps the underlying LuckPerms prefix in sync with it (see
 * setEquippedTag - this only ever touches a LuckPerms PREFIX meta node, never a permission, group,
 * or parent).
 *
 * A "tag key" is either the literal string "none" (no tag equipped) or "<mode>.<position>" (e.g.
 * "dune.3"), matching the same key format used by LeaderboardManager's positionTags section, so the
 * two stay easy to cross-reference.
 */
public class TagManager {

    private static final String NONE_KEY = "none";

    private final SkepiFBPlugin plugin;
    private final File tagsFile;
    private FileConfiguration configuration;

    public TagManager(SkepiFBPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.tagsFile = new File(dataFolder, "player-tags.yml");
        if (!tagsFile.exists()) {
            try {
                tagsFile.createNewFile();
            } catch (IOException ex) {
                plugin.getLogger().warning("Unable to create player-tags.yml: " + ex.getMessage());
            }
        }
        this.configuration = YamlConfiguration.loadConfiguration(tagsFile);
    }

    private String path(UUID playerUuid) {
        return "players." + playerUuid;
    }

    /**
     * Returns the player's currently-equipped tag key ("none" if they have never picked one, or if
     * their saved choice no longer exists/qualifies).
     */
    public String getEquippedTag(UUID playerUuid) {
        if (playerUuid == null) {
            return NONE_KEY;
        }
        String stored = configuration.getString(path(playerUuid), NONE_KEY);
        return stored == null || stored.isBlank() ? NONE_KEY : stored;
    }

    public boolean isNoTagEquipped(UUID playerUuid) {
        return NONE_KEY.equalsIgnoreCase(getEquippedTag(playerUuid));
    }

    /**
     * Call once on a player's very first join (no existing entry at all) so "No Tag" is explicitly
     * saved as their equipped choice from the start, rather than relying on the getEquippedTag(...)
     * default every time - an explicit first-join record also lets the tag shop show "No Tag" as
     * enchanted/selected immediately rather than only inferring it from an absent value.
     */
    public void ensureDefaultTagOnFirstJoin(UUID playerUuid) {
        if (playerUuid == null || configuration.contains(path(playerUuid))) {
            return;
        }
        configuration.set(path(playerUuid), NONE_KEY);
        save();
    }

    /**
     * Priority used for every SkepiFB tag prefix, read fresh from config.yml's tags.prefix-priority
     * each time (so an admin can change it with /fb reload without a restart). See
     * ConfigManager#getTagPrefixPriority for what this number actually controls and why there is no
     * single universally-correct default.
     */
    private int tagPrefixPriority() {
        return plugin.getConfigManager().getTagPrefixPriority();
    }

    /**
     * Equips the given tag key for the player: removes every position-tag LuckPerms prefix this
     * system manages (so only one is ever active at a time), adds the newly-selected one's prefix
     * (if not "none"), and persists the choice so it survives restarts/rejoins. This only ever adds
     * or removes a LuckPerms PREFIX meta node - it never touches permissions, groups, or parents,
     * so it can never conflict with or override a player's group/parent assignments from anywhere
     * else.
     */
    public void setEquippedTag(UUID playerUuid, String tagKey) {
        if (playerUuid == null) {
            return;
        }
        String normalizedKey = (tagKey == null || tagKey.isBlank()) ? NONE_KEY : tagKey.trim();

        LeaderboardManager leaderboardManager = plugin.getLeaderboardManager();
        for (String mode : leaderboardManager.getModesWithPositionTags()) {
            for (int position = 1; position <= LeaderboardManager.MAX_POSITIONS; position++) {
                String display = leaderboardManager.getPositionTagDisplay(mode, position);
                if (display != null && !display.isBlank()) {
                    LuckPermsUtil.removePrefix(playerUuid, tagPrefixPriority(), display);
                }
            }
        }

        if (!NONE_KEY.equalsIgnoreCase(normalizedKey)) {
            String[] parts = splitTagKey(normalizedKey);
            if (parts != null) {
                String display = leaderboardManager.getPositionTagDisplay(parts[0], Integer.parseInt(parts[1]));
                if (display != null && !display.isBlank()) {
                    LuckPermsUtil.addPrefix(playerUuid, tagPrefixPriority(), display);
                } else {
                    // The tag no longer has a display configured (removed by an admin) - fall back
                    // to "no tag" rather than persisting a choice that applies nothing.
                    normalizedKey = NONE_KEY;
                }
            } else {
                normalizedKey = NONE_KEY;
            }
        }

        configuration.set(path(playerUuid), normalizedKey);
        save();
    }

    /**
     * Every position-tag key the player currently qualifies for, based on their live leaderboard
     * placements (e.g. if they are #3 on Dune and #9 on Nether, and both have position tags
     * configured, this returns ["dune.3", "nether.9"]). Order follows LeaderboardManager's mode
     * iteration order.
     */
    public List<String> getQualifyingTagKeys(UUID playerUuid) {
        List<String> keys = new ArrayList<>();
        if (playerUuid == null) {
            return keys;
        }
        LeaderboardManager leaderboardManager = plugin.getLeaderboardManager();
        for (String mode : leaderboardManager.getModesWithPositionTags()) {
            int position = leaderboardManager.getPlayerPosition(mode, playerUuid);
            if (position <= 0) {
                continue;
            }
            String display = leaderboardManager.getPositionTagDisplay(mode, position);
            if (display == null || display.isBlank()) {
                continue;
            }
            keys.add(mode + "." + position);
        }
        return keys;
    }

    /**
     * Resolves a tag key ("mode.position") to its configured display name, or a sensible generated
     * fallback ("#<position> <Mode>") if no display override was set.
     */
    public String getTagDisplayName(String tagKey) {
        String[] parts = splitTagKey(tagKey);
        if (parts == null) {
            return tagKey;
        }
        String mode = parts[0];
        int position = Integer.parseInt(parts[1]);
        String display = plugin.getLeaderboardManager().getPositionTagDisplay(mode, position);
        if (display != null && !display.isBlank()) {
            return display;
        }
        return "&b#" + position + " " + capitalize(mode);
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Splits a "mode.position" tag key into {mode, position} (as strings), or null if the key is
     * "none", malformed, or the position isn't a valid integer.
     */
    private String[] splitTagKey(String tagKey) {
        if (tagKey == null || tagKey.isBlank() || NONE_KEY.equalsIgnoreCase(tagKey)) {
            return null;
        }
        int lastDot = tagKey.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == tagKey.length() - 1) {
            return null;
        }
        String mode = tagKey.substring(0, lastDot);
        String positionText = tagKey.substring(lastDot + 1);
        try {
            int position = Integer.parseInt(positionText);
            if (position < 1 || position > LeaderboardManager.MAX_POSITIONS) {
                return null;
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return new String[]{mode, positionText};
    }

    private void save() {
        try {
            configuration.save(tagsFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save player-tags.yml: " + ex.getMessage());
        }
    }
}
