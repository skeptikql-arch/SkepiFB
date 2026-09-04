package me.skepi.skepifb.npc;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaIsland;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawns a Citizens NPC (reflection-based - Citizens is only a softdepend, never a compile-time
 * dependency, same approach TimerManager already uses for replay-ghost NPCs) next to an island's
 * spawn point for as long as that island is occupied, wearing the occupying player's own skin and
 * displaying a configurable name ("&bFastbuilder" by default). Right-clicking it opens a
 * configurable menu (mode_changer_menu - the internal key for the mode switcher/changer menu - by
 * default). The NPC is removed the moment the island becomes unoccupied again.
 * <p>
 * Unlike the private, per-viewer replay ghosts, this NPC is a permanent, shared fixture: it is
 * never hidden from other players.
 */
public class IslandNpcManager implements Listener {

    private final SkepiFBPlugin plugin;
    private final Map<String, Object> npcsByIslandKey = new HashMap<>();

    // Persistent (Citizens-side, not this plugin's own memory) metadata flag set on every NPC this
    // class spawns - see markNpcAsOwned()/isOwnedBySkepiFb(). This is what lets
    // removeLeftoverNpcs() find and destroy a leftover NPC from a previous, not-cleanly-shut-down
    // session, since Citizens reloads its NPCs (and their persistent metadata) from its own saves
    // file independently of anything this plugin remembers.
    private static final String OWNED_METADATA_KEY = "skepifb-island-npc";

    public IslandNpcManager(SkepiFBPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean debugEnabled() {
        return plugin.getConfigManager().getConfiguration().getBoolean("island-npc.debug", false);
    }

    /**
     * Logs to console ONLY when "island-npc.debug: true" in config.yml - this whole class is
     * reflection-heavy against Citizens' internal API (see the class javadoc), so when something's
     * still wrong on a particular server/Citizens build there's no other way to see which of the
     * many reflected method calls actually succeeded, failed, or threw, or what the SkinTrait's
     * state was at each step. Always prefixed so these lines are easy to grep out of a log.
     */
    private void debug(String message) {
        if (debugEnabled()) {
            plugin.getLogger().info("[IslandNPC debug] " + message);
        }
    }

    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static String islandKey(Arena arena, ArenaIsland island) {
        return arena.getName().toLowerCase(java.util.Locale.ROOT) + ":" + island.getIndex();
    }

    /**
     * Call any time an island's occupancy may have changed - joining, leaving, disconnecting,
     * test-mode join/exit, etc. Spawns the NPC if the island just became occupied and doesn't
     * have one yet, or removes it if the island is now empty. Safe and cheap to call redundantly
     * (e.g. once per relevant code path, "just in case").
     */
    public void syncIsland(Arena arena, ArenaIsland island) {
        if (arena == null || island == null) {
            return;
        }
        String key = islandKey(arena, island);
        UUID occupant = island.getOccupiedPlayer();
        if (occupant == null) {
            removeNpc(key);
            return;
        }
        if (npcsByIslandKey.containsKey(key)) {
            return;
        }
        Player occupantPlayer = Bukkit.getPlayer(occupant);
        if (occupantPlayer == null) {
            return;
        }
        spawnNpc(key, island, occupantPlayer);
    }

    /**
     * Removes every island NPC this manager currently tracks - called on plugin disable so
     * nothing lingers in the world (or in Citizens' own save file) after a reload/shutdown.
     */
    public void removeAll() {
        for (String key : new ArrayList<>(npcsByIslandKey.keySet())) {
            removeNpc(key);
        }
    }

    /**
     * Sets the "skepifb-island-npc" persistent metadata flag (OWNED_METADATA_KEY) on an NPC via
     * Citizens' own NPC#data() store - tried as .setPersistent(String, Object) first (an explicit
     * persistence flag on some Citizens builds), falling back to the plain .set(String, Object)
     * on older/newer builds that lack that overload (NPC#data() is already Citizens' own
     * persistent-metadata store, so a plain .set() still gets saved to Citizens' saves file on
     * disk regardless). Whichever succeeds, the flag survives independently of this plugin's own
     * memory - see removeLeftoverNpcs() below for why that's the whole point.
     */
    private static void markNpcAsOwned(Object npc) {
        try {
            Object dataStore = npc.getClass().getMethod("data").invoke(npc);
            if (dataStore == null) {
                return;
            }
            try {
                dataStore.getClass().getMethod("setPersistent", String.class, Object.class)
                        .invoke(dataStore, OWNED_METADATA_KEY, Boolean.TRUE);
                return;
            } catch (NoSuchMethodException ignored) {
            }
            dataStore.getClass().getMethod("set", String.class, Object.class)
                    .invoke(dataStore, OWNED_METADATA_KEY, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isOwnedBySkepiFb(Object npc) {
        try {
            Object dataStore = npc.getClass().getMethod("data").invoke(npc);
            if (dataStore == null) {
                return false;
            }
            Object value;
            try {
                value = dataStore.getClass().getMethod("get", String.class).invoke(dataStore, OWNED_METADATA_KEY);
            } catch (NoSuchMethodException nsme) {
                value = dataStore.getClass().getMethod("get", String.class, Object.class)
                        .invoke(dataStore, OWNED_METADATA_KEY, null);
            }
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Scans EVERY NPC currently in Citizens' own registry - including ones Citizens itself already
     * reloaded from its saves file before this plugin's onEnable ever ran - and destroys any that
     * carry this plugin's OWNED_METADATA_KEY flag (see markNpcAsOwned()), regardless of whether
     * the CURRENT session's npcsByIslandKey map ever knew about them.
     * <p>
     * This is the actual fix for island NPCs persisting across a restart that didn't cleanly go
     * through onDisable()'s removeAll() above (a crash, a force-kill, a host reboot, or an
     * exception mid-loop): removeAll() can only remove what THIS session tracked in memory, but
     * Citizens persists its NPCs to its own storage independently of this plugin, so a leftover
     * NPC from a previous, un-cleanly-ended session would otherwise just sit there forever with
     * nothing left anywhere that still recognizes it. Called from
     * SkepiFBPlugin#removeLeftoverManagedEntities() at the very start of onEnable(), before any
     * new NPCs are spawned.
     * <p>
     * Safe and cheap to call even when Citizens isn't installed, or has zero NPCs - returns 0
     * without throwing either way.
     *
     * @return how many leftover NPCs were found and destroyed.
     */
    public static int removeLeftoverNpcs() {
        int removed = 0;
        try {
            Class<?> apiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = apiClass.getMethod("getNPCRegistry").invoke(null);
            if (registry instanceof Iterable<?> npcIterable) {
                List<Object> toDestroy = new ArrayList<>();
                for (Object npc : npcIterable) {
                    if (isOwnedBySkepiFb(npc)) {
                        toDestroy.add(npc);
                    }
                }
                for (Object npc : toDestroy) {
                    try {
                        npc.getClass().getMethod("destroy").invoke(npc);
                        removed++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    /**
     * Clears every tracked island NPC and immediately re-spawns one for each island that's
     * currently occupied, picking up any config.yml change (offset/name/enabled/click-menu) right
     * away instead of only on the next occupancy change. Used by /fb reload.
     */
    public void resyncAll() {
        removeAll();
        for (Arena arena : plugin.getArenaManager().getArenas()) {
            for (ArenaIsland island : arena.getIslands()) {
                syncIsland(arena, island);
            }
        }
    }

    /**
     * Applies the occupying player's skin to the NPC's SkinTrait, trying every setter signature
     * across Citizens versions we know of, plus every "mark this persistent" variant, logging
     * (when island-npc.debug is on) exactly which of those calls actually exist/succeed on this
     * server's Citizens build and what the trait's state is afterward.
     * <p>
     * MUST be called BEFORE the NPC is ever renamed away from its creation-time name (see
     * spawnNpc) - not after. The previous approach here called this AFTER renaming, on the theory
     * that setting the skin last would "win" any race with Citizens' own name-triggered skin
     * auto-fetch. In practice the NPC still ended up wearing a skin fetched for the DISPLAY name
     * instead of the occupant's - meaning that auto-fetch resolves (or completes, if async) some
     * time after our own reapplications, clobbering them regardless of order. Locking the skin AND
     * the "persistent" flag in before the NPC is ever renamed is what actually prevents Citizens
     * from triggering that auto-fetch in the first place, instead of racing it after the fact.
     */
    private void applySkin(Object npc, Player occupant) {
        debug("applySkin() starting for occupant=" + occupant.getName() + " (" + occupant.getUniqueId() + ")");
        try {
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Object trait = getOrAddTrait(npc, skinTraitClass);
            if (trait == null) {
                debug("applySkin() aborted: could not get/add a SkinTrait on this NPC at all.");
                return;
            }
            invokeIfPresent(trait, "setSkinPersistent", new Class<?>[]{String.class, UUID.class}, new Object[]{occupant.getName(), occupant.getUniqueId()});
            invokeIfPresent(trait, "setSkinUuid", new Class<?>[]{UUID.class}, new Object[]{occupant.getUniqueId()});
            invokeIfPresent(trait, "setSkinUUID", new Class<?>[]{UUID.class}, new Object[]{occupant.getUniqueId()});
            invokeIfPresent(trait, "setSkinName", new Class<?>[]{String.class, boolean.class}, new Object[]{occupant.getName(), true});
            invokeIfPresent(trait, "setSkinName", new Class<?>[]{String.class}, new Object[]{occupant.getName()});
            // This is the one that matters most: it's what should stop Citizens from silently
            // re-fetching a skin for whatever name the NPC gets renamed to later. Called LAST (after
            // the skin itself is already set above) so persistence is being turned on for the skin
            // we actually want, not whatever the trait held before this method ran.
            invokeIfPresent(trait, "setSkinPersistent", new Class<?>[]{boolean.class}, new Object[]{true});
            debug("applySkin() finished, trait state now: " + describeSkinTraitState(trait));
        } catch (Throwable t) {
            debug("applySkin() threw: " + t);
        }
    }

    /**
     * Reflectively invokes "methodName" on "target" with the given parameter types/args, ONLY if
     * that exact method exists on this Citizens build - and logs (when debugging) whether it was
     * found-and-succeeded, found-but-threw, or simply doesn't exist on this version. Every skin/
     * name setter across this class goes through here instead of a bare try/catch so debug logs
     * can actually distinguish "this Citizens build doesn't have this method" from "it has it, and
     * calling it failed" - the previous version of this code swallowed both identically, which is
     * exactly why the original bug report ("skin is still wrong") had no trail to follow.
     */
    private void invokeIfPresent(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        String signature = methodName + "(" + java.util.Arrays.toString(paramTypes) + ")";
        try {
            target.getClass().getMethod(methodName, paramTypes).invoke(target, args);
            debug("  " + signature + " -> OK");
        } catch (NoSuchMethodException e) {
            debug("  " + signature + " -> not present on this Citizens build, skipped");
        } catch (Throwable t) {
            debug("  " + signature + " -> threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Reads back whatever the SkinTrait's getters report right now, for debug logging - trying
     * every getter name we know of across Citizens versions, degrading gracefully (rather than
     * throwing) wherever a given getter doesn't exist on this build.
     */
    private String describeSkinTraitState(Object trait) {
        StringBuilder sb = new StringBuilder();
        sb.append("skinName=").append(readGetter(trait, "getSkinName"));
        sb.append(", skinUuid=").append(firstNonNull(readGetter(trait, "getSkinUuid"), readGetter(trait, "getSkinUUID")));
        sb.append(", persistent=").append(readGetter(trait, "isSkinPersistent"));
        return sb.toString();
    }

    private Object readGetter(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    /**
     * Fetches a Citizens trait off an NPC, adding it first if the NPC doesn't have it yet
     * (getOrAddTrait) - falling back to a plain getTrait lookup on older Citizens builds that lack
     * getOrAddTrait entirely. Traits like NametagTrait aren't guaranteed to already be present on a
     * freshly created NPC, so a plain getTrait() call can silently return null and make the
     * nametag override below a no-op with no error anywhere.
     */
    private Object getOrAddTrait(Object npc, Class<?> traitClass) {
        try {
            return npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, traitClass);
        } catch (Throwable ignored) {
        }
        try {
            return npc.getClass().getMethod("getTrait", Class.class).invoke(npc, traitClass);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Sets the NPC's actual, registered Citizens name to the configured display name
     * ("island-npc.name", "&bFastbuilder" by default) - via npc.setName(...) itself, not just a
     * NametagTrait/custom-name overlay on top of an unchanged registration. A pure overlay is what
     * an earlier approach tried and it still wasn't reliable: on this server's Citizens build, the
     * fake player entity's OWN registered name kept winning the visible nametag over the overlay in
     * some cases (e.g. the tab list, or a brief flash on spawn), so the nametag stayed on the
     * occupant's username no matter what NametagTrait/setCustomName said.
     * <p>
     * MUST be called AFTER {@link #applySkin(Object, Player)} has already locked in the skin and
     * marked it persistent (see applySkin's own javadoc for why the order flipped from an earlier
     * version of this class) - calling this first is what let Citizens' own name-based skin
     * auto-fetch fire and silently overwrite the occupant's skin with one fetched for the display
     * name instead.
     * <p>
     * Also still sets NametagTrait/setCustomName as a belt-and-suspenders overlay on top of the
     * real rename, and is called more than once per NPC (immediately after spawn, and again a few
     * ticks later) because a player-type NPC's nametag can otherwise briefly flash back to its
     * previous name around spawn time before the rename fully takes effect.
     */
    private void renameNpc(Object npc, String displayName) {
        debug("renameNpc() -> \"" + displayName + "\"");
        invokeIfPresent(npc, "setName", new Class<?>[]{String.class}, new Object[]{displayName});
        try {
            Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
            if (entity instanceof Entity ent) {
                try { ent.getClass().getMethod("setCustomNameVisible", boolean.class).invoke(ent, true); } catch (Throwable ignored) {}
                try { ent.getClass().getMethod("setCustomName", String.class).invoke(ent, displayName); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> nametagTraitClass = Class.forName("net.citizensnpcs.trait.NametagTrait");
            Object trait = getOrAddTrait(npc, nametagTraitClass);
            if (trait != null) {
                try { trait.getClass().getMethod("setNameVisible", boolean.class).invoke(trait, true); } catch (Throwable ignored) {}
                try { trait.getClass().getMethod("setName", String.class).invoke(trait, displayName); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    private void removeNpc(String key) {
        Object npc = npcsByIslandKey.remove(key);
        if (npc == null) {
            return;
        }
        try {
            npc.getClass().getMethod("destroy").invoke(npc);
        } catch (Throwable ignored) {
        }
    }

    private void spawnNpc(String key, ArenaIsland island, Player occupant) {
        try {
            if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
                return;
            }
            FileConfiguration config = plugin.getConfigManager().getConfiguration();
            if (!config.getBoolean("island-npc.enabled", true)) {
                return;
            }
            double offsetX = config.getDouble("island-npc.offset.x", -1.0);
            double offsetY = config.getDouble("island-npc.offset.y", 0.0);
            double offsetZ = config.getDouble("island-npc.offset.z", -1.0);
            String rawName = config.getString("island-npc.name", "&bFastbuilder");
            String displayName = ChatColor.translateAlternateColorCodes('&', rawName == null || rawName.isBlank() ? "&bFastbuilder" : rawName);

            Location spawnLoc = new Location(
                    occupant.getWorld(),
                    island.getSpawnLocation().getX() + offsetX,
                    island.getSpawnLocation().getY() + offsetY,
                    island.getSpawnLocation().getZ() + offsetZ,
                    island.getSpawnLocation().getYaw(),
                    island.getSpawnLocation().getPitch());

            Class<?> apiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = apiClass.getMethod("getNPCRegistry").invoke(null);
            Class<?> entityTypeClass = Class.forName("org.bukkit.entity.EntityType");
            Object playerType = Enum.valueOf((Class) entityTypeClass, "PLAYER");
            // Created with the occupant's own username as the initial registered name - this is
            // NOT the name that ends up visible (renameNpc(...) below changes it to the configured
            // display name), it's just what createNPC needs some value for.
            Object npc = registry.getClass().getMethod("createNPC", entityTypeClass, String.class)
                    .invoke(registry, playerType, occupant.getName());
            debug("Created NPC for island key=" + key + ", occupant=" + occupant.getName() + " (" + occupant.getUniqueId() + ")");
            markNpcAsOwned(npc);

            // Skin FIRST, name SECOND - deliberately in this order now (see applySkin/renameNpc
            // javadocs for the full explanation). Locking the skin in - and marking it persistent -
            // before the NPC's registered name ever changes is what stops Citizens' own name-based
            // skin auto-fetch from firing at all, rather than trying to out-race it afterward.
            applySkin(npc, occupant);

            try {
                npc.getClass().getMethod("spawn", Location.class).invoke(npc, spawnLoc);
            } catch (NoSuchMethodException ignored) {
            }

            renameNpc(npc, displayName);
            // Safety net: re-apply both a few times over the following seconds in case this
            // Citizens build's auto-fetch (if the persistent flag above didn't fully suppress it)
            // is slow/async and would otherwise land after our initial application. Each reapply
            // re-locks the skin, so even if something does briefly clobber it, it self-corrects.
            for (long delayTicks : new long[]{5L, 20L, 60L, 100L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    applySkin(npc, occupant);
                    renameNpc(npc, displayName);
                    if (debugEnabled()) {
                        try {
                            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
                            Object trait = getOrAddTrait(npc, skinTraitClass);
                            if (trait != null) {
                                debug("  [+" + delayTicks + "t check] " + describeSkinTraitState(trait));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }, delayTicks);
            }
            // Always-on (not gated by island-npc.debug) final sanity check, a few seconds after
            // spawn: if the skin trait doesn't report the occupant's own UUID by then, something on
            // this server's Citizens build is still overriding it after all of the above - log a
            // warning either way so this doesn't fail completely silently even with debug off.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
                    Object trait = getOrAddTrait(npc, skinTraitClass);
                    Object skinUuid = trait == null ? null : firstNonNull(readGetter(trait, "getSkinUuid"), readGetter(trait, "getSkinUUID"));
                    Object skinName = trait == null ? null : readGetter(trait, "getSkinName");
                    boolean uuidMatches = occupant.getUniqueId().equals(skinUuid);
                    boolean nameMatches = occupant.getName().equalsIgnoreCase(String.valueOf(skinName));
                    if (!uuidMatches && !nameMatches) {
                        plugin.getLogger().warning("[IslandNPC] The island NPC for " + occupant.getName()
                                + " may still be wearing the wrong skin (SkinTrait currently reports skinName="
                                + skinName + ", skinUuid=" + skinUuid + " instead of " + occupant.getName() + "/"
                                + occupant.getUniqueId() + "). Set island-npc.debug: true in config.yml and rejoin "
                                + "the island to get a full step-by-step log of why.");
                    } else {
                        debug("Final check (+100t) passed: skin trait matches occupant.");
                    }
                } catch (Throwable t) {
                    debug("Final skin check threw: " + t);
                }
            }, 100L);

            try {
                Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
                if (entity instanceof Entity ent) {
                    try {
                        Object navigator = npc.getClass().getMethod("getNavigator").invoke(npc);
                        if (navigator != null) {
                            try { navigator.getClass().getMethod("cancelNavigation").invoke(navigator); } catch (Throwable ignored) {}
                            try { navigator.getClass().getMethod("setPaused", boolean.class).invoke(navigator, true); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {
                    }
                    try { ent.getClass().getMethod("setGravity", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setAI", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setCollidable", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    // Deliberately NOT hiding this NPC from anyone (unlike the private per-viewer
                    // replay ghosts) - it's meant to be a permanent, shared fixture everyone near
                    // the island can see and click.
                }
            } catch (Throwable ignored) {
            }

            npcsByIslandKey.put(key, npc);
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (npcsByIslandKey.isEmpty()) {
            return;
        }
        Entity clicked = event.getRightClicked();
        for (Object npc : npcsByIslandKey.values()) {
            try {
                Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
                if (clicked.equals(entity)) {
                    event.setCancelled(true);
                    openConfiguredMenu(event.getPlayer());
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void openConfiguredMenu(Player player) {
        String menuKey = plugin.getConfigManager().getConfiguration().getString("island-npc.click-menu", "mode_changer_menu");
        if (menuKey == null || menuKey.isBlank()) {
            menuKey = "mode_changer_menu";
        }
        try {
            plugin.getHotbarManager().openBlankSubmenu(player, menuKey);
        } catch (Throwable ignored) {
        }
    }
}
