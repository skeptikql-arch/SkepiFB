package me.skepi.skepifb.util;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.logging.Level;

/**
 * LuckPerms integration via the real, compile-time LuckPerms API (declared as a `provided`
 * dependency in pom.xml, same pattern as WorldEdit) - NOT reflection.
 *
 * Tag equipping works purely through LuckPerms PREFIX meta nodes, not permissions, groups, or
 * parents - this deliberately never touches a player's inherited groups/parents at all, so it can
 * never conflict with or override whatever parent group a player is separately assigned through
 * some other system. Equipping a tag just adds a prefix; unequipping just removes it.
 */
public final class LuckPermsUtil {

    private LuckPermsUtil() {
    }

    /**
     * Resolves the running LuckPerms instance, or null if LuckPerms is not installed/enabled.
     */
    private static LuckPerms getApi() {
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                return null;
            }
            return LuckPermsProvider.get();
        } catch (IllegalStateException notLoadedYet) {
            // LuckPerms plugin is present but hasn't finished initializing its API provider yet.
            return null;
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.WARNING, "[SkepiFB] Unexpected error resolving LuckPerms API", t);
            return null;
        }
    }

    /**
     * Resolves a LuckPerms User for mutation/reading, loading them from storage if they are not
     * already cached (e.g. an offline player).
     */
    private static User resolveUser(LuckPerms api, UUID playerUuid) throws Exception {
        UserManager userManager = api.getUserManager();
        User user = userManager.getUser(playerUuid);
        if (user == null) {
            user = userManager.loadUser(playerUuid).get();
        }
        return user;
    }

    /**
     * Extracts a color code (e.g. "&b") from the end of a player's LuckPerms prefix, for coloring
     * their name to match their rank. Returns null if LuckPerms is unavailable, the player has no
     * prefix, or no color code could be found in it.
     */
    public static String getRankColor(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        LuckPerms api = getApi();
        if (api == null) {
            return null;
        }
        try {
            User user = resolveUser(api, playerUuid);
            if (user == null) {
                return null;
            }
            CachedMetaData metaData = user.getCachedData().getMetaData();
            String prefix = metaData.getPrefix();
            return extractLastColorCode(prefix);
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.FINE, "[SkepiFB] Failed to read LuckPerms prefix for " + playerUuid, t);
            return null;
        }
    }

    private static String extractLastColorCode(String s) {
        if (s == null) {
            return null;
        }
        int idx = s.lastIndexOf('\u00A7');
        if (idx >= 0 && idx + 1 < s.length()) {
            return "\u00A7" + s.charAt(idx + 1);
        }
        idx = s.lastIndexOf('&');
        if (idx >= 0 && idx + 1 < s.length()) {
            return "\u00A7" + s.charAt(idx + 1);
        }
        return null;
    }

    /**
     * Adds a LuckPerms prefix meta node to a player - just a prefix, nothing else: no group, no
     * parent, no permission is touched. priority determines which prefix "wins" if the player has
     * more than one prefix source (higher priority wins); prefixText is used as LuckPerms' node
     * value verbatim (color codes translated to section-sign form first, since that's what
     * LuckPerms/most prefix-displaying plugins expect).
     */
    public static boolean addPrefix(UUID playerUuid, int priority, String prefixText) {
        if (prefixText == null || prefixText.isBlank()) {
            return false;
        }
        Node node = PrefixNode.builder(org.bukkit.ChatColor.translateAlternateColorCodes('&', prefixText.trim()), priority).build();
        return mutateNode(playerUuid, node, true);
    }

    /**
     * Removes a LuckPerms prefix meta node from a player (must match the exact text+priority that
     * was added). Safe to call even if the player never had this prefix.
     */
    public static boolean removePrefix(UUID playerUuid, int priority, String prefixText) {
        if (prefixText == null || prefixText.isBlank()) {
            return false;
        }
        Node node = PrefixNode.builder(org.bukkit.ChatColor.translateAlternateColorCodes('&', prefixText.trim()), priority).build();
        return mutateNode(playerUuid, node, false);
    }

    private static boolean mutateNode(UUID playerUuid, Node node, boolean add) {
        if (playerUuid == null || node == null) {
            return false;
        }
        LuckPerms api = getApi();
        if (api == null) {
            Bukkit.getLogger().warning("[SkepiFB] Could not " + (add ? "add" : "remove") + " LuckPerms prefix for "
                    + playerUuid + ": LuckPerms is not installed/enabled.");
            return false;
        }
        try {
            UserManager userManager = api.getUserManager();
            User user = resolveUser(api, playerUuid);
            if (user == null) {
                Bukkit.getLogger().warning("[SkepiFB] Could not " + (add ? "add" : "remove") + " LuckPerms prefix: no LuckPerms user found for " + playerUuid + ".");
                return false;
            }
            var data = user.data();
            if (add) {
                data.add(node);
            } else {
                data.remove(node);
            }
            userManager.saveUser(user);
            return true;
        } catch (Throwable t) {
            Bukkit.getLogger().log(Level.WARNING, "[SkepiFB] Failed to " + (add ? "add" : "remove")
                    + " LuckPerms prefix for " + playerUuid, t);
            return false;
        }
    }
}
