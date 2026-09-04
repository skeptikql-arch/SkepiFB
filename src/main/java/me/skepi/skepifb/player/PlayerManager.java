package me.skepi.skepifb.player;

import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaBoundary;
import me.skepi.skepifb.arena.ArenaIsland;
import me.skepi.skepifb.arena.ArenaLocation;
import me.skepi.skepifb.arena.ArenaManager;
import me.skepi.skepifb.SkepiFBPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerManager implements Listener {

    private final SkepiFBPlugin plugin;
    private final ArenaManager arenaManager;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    private final Map<UUID, org.bukkit.Location> temporarySpawns = new HashMap<>();
    private final Set<UUID> testModePlayers = new HashSet<>();

    public PlayerManager(SkepiFBPlugin plugin, ArenaManager arenaManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
    }

    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void trackPlayer(UUID playerUuid, String arenaName, int islandIndex) {
        // switching islands/modes clears any temporary spawn and exits test mode for that player
        temporarySpawns.remove(playerUuid);
        testModePlayers.remove(playerUuid);
        sessions.put(playerUuid, new PlayerSession(playerUuid, arenaName, islandIndex));
    }

    public void trackTestPlayer(UUID playerUuid, String arenaName, int islandIndex) {
        if (playerUuid == null || arenaName == null || arenaName.isBlank()) {
            return;
        }
        sessions.put(playerUuid, new PlayerSession(playerUuid, arenaName, islandIndex));
    }

    public boolean isInArena(UUID playerUuid) {
        return sessions.containsKey(playerUuid);
    }

    public String getPlayerArena(UUID playerUuid) {
        PlayerSession session = sessions.get(playerUuid);
        return session != null ? session.getArenaName() : null;
    }

    public int getPlayerIsland(UUID playerUuid) {
        PlayerSession session = sessions.get(playerUuid);
        return session != null ? session.getIslandIndex() : -1;
    }

    public void clearPlayer(UUID playerUuid) {
        sessions.remove(playerUuid);
        temporarySpawns.remove(playerUuid);
        testModePlayers.remove(playerUuid);
    }

    public void leaveArena(Player player) {
        freePlayerSession(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        freePlayerSession(event.getPlayer());
        try {
            ((SkepiFBPlugin) plugin).getSpectateManager().clear(event.getPlayer().getUniqueId());
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;

        try {
            main.getTagManager().ensureDefaultTagOnFirstJoin(player.getUniqueId());
        } catch (Throwable ignored) {
        }

        try {
            main.getShopManager().applyDefaultShopSelectionsOnFirstJoin(player.getUniqueId());
        } catch (Throwable ignored) {
        }

        // Determine default arena from config
        String defaultArena = null;
        try {
            defaultArena = main.getConfigManager().getConfiguration().getString("default-arena", null);
        } catch (Throwable ignored) {
        }

        Arena arena = null;
        if (defaultArena != null) {
            arena = arenaManager.getArena(defaultArena);
        }

        if (arena == null) {
            // Fallback to first available arena
            if (arenaManager.hasArenas()) {
                arena = arenaManager.getArenas().get(0);
            }
        }

        if (arena != null) {
            boolean joined = arenaManager.joinArena(player, arena);
            if (joined) {
                if (main.getShopManager() != null) {
                    main.getShopManager().ensureEquippedTool(player);
                }
                // Setup player: give arena block, hotbar, scoreboard, and ensure session exists
                main.getInventoryManager().giveArenaBlock(player);
                main.getHotbarManager().giveHotbarToPlayer(player);
                main.getScoreboardManager().showScoreboard(player);
                main.getTimerManager().ensureSession(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        freePlayerSession(event.getPlayer());
    }


    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Keep the player assigned to the island on death.
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (isInArena(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean isOutOfBounds(Location current, ArenaLocation spawn, ArenaBoundary boundary) {
        if (current.getWorld() == null || spawn == null) {
            return false;
        }

        double currentX = current.getX();
        double currentY = current.getY();
        double currentZ = current.getZ();
        double spawnX = spawn.getX();
        double spawnY = spawn.getY();
        double spawnZ = spawn.getZ();

        if (boundary.getLeft() >= 0 && currentX < spawnX - boundary.getLeft()) {
            return true;
        }
        if (boundary.getRight() >= 0 && currentX > spawnX + boundary.getRight()) {
            return true;
        }
        if (boundary.getBack() >= 0 && currentZ < spawnZ - boundary.getBack()) {
            return true;
        }
        if (boundary.getForward() >= 0 && currentZ > spawnZ + boundary.getForward()) {
            return true;
        }
        if (boundary.getDown() >= 0 && currentY < spawnY - boundary.getDown()) {
            return true;
        }
        if (boundary.getUp() >= 0 && currentY > spawnY + boundary.getUp()) {
            return true;
        }
        return false;
    }

    private Location locationFromSpawn(ArenaLocation spawn, World world) {
        return new Location(world, spawn.getX(), spawn.getY(), spawn.getZ());
    }

    private void freePlayerSession(Player player) {
        UUID playerUuid = player.getUniqueId();
        boolean isTestMode = testModePlayers.contains(playerUuid);
        if (!isInArena(playerUuid)) {
            testModePlayers.remove(playerUuid);
            return;
        }

        plugin.getTimerManager().resetPlayerSession(playerUuid, player.getWorld());
        try { ((SkepiFBPlugin) plugin).getStatboardManager().removeStatboard(playerUuid); } catch (Throwable ignored) {}
        // Remove player entries from any session top lists for this arena
        try {
            String arenaName = getPlayerArena(playerUuid);
            if (arenaName != null) {
                SkepiFBPlugin main = (SkepiFBPlugin) plugin;
                main.getSessionTopManager().removePlayerFromArena(playerUuid, arenaName);
            }
        } catch (Throwable ignored) {
        }
        plugin.getScoreboardManager().hideScoreboard(player);

        String arenaName = getPlayerArena(playerUuid);
        Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) {
            sessions.remove(playerUuid);
            testModePlayers.remove(playerUuid);
            return;
        }

        ArenaIsland vacatedIsland = arena.findIslandByPlayer(playerUuid).orElse(null);
        if (vacatedIsland != null) {
            vacatedIsland.setOccupiedPlayer(null);
            if (!isTestMode) {
                try {
                    arenaManager.restoreIsland(arena, vacatedIsland);
                } catch (Throwable ignored) {}
            }
            try { plugin.getIslandNpcManager().syncIsland(arena, vacatedIsland); } catch (Throwable ignored) {}
        }
        sessions.remove(playerUuid);
        testModePlayers.remove(playerUuid);
        if (!isTestMode) {
            temporarySpawns.remove(playerUuid);
            arenaManager.saveArenas();
        }
        Location newLocation = determineSafeSpawn(player);
        player.teleport(newLocation);
    }

    /**
     * Stores a per-player temporary spawn point, including the position AND the direction the
     * player was actually facing at the moment they set it - exactly like a vanilla bed respawn
     * point. This used to force yaw/pitch to a hardcoded 0.0f/0.0f instead of keeping the player's
     * real facing, which is what actually caused "I'm always facing directly forward after using
     * the custom spawn item" - every temporary spawn ended up facing the same fixed direction
     * (whatever yaw 0 happens to be) no matter which way the player was looking when they set it.
     */
    public void setTemporarySpawn(UUID playerUuid, org.bukkit.Location location) {
        if (playerUuid == null) return;
        if (location == null) {
            temporarySpawns.remove(playerUuid);
            return;
        }
        temporarySpawns.put(playerUuid, location.clone());
    }

    public boolean hasTemporarySpawn(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        return temporarySpawns.containsKey(playerUuid);
    }

    public void setTestMode(UUID playerUuid, boolean testMode) {
        if (playerUuid == null) {
            return;
        }
        if (testMode) {
            testModePlayers.add(playerUuid);
        } else {
            testModePlayers.remove(playerUuid);
        }
    }

    public boolean isInTestMode(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        return testModePlayers.contains(playerUuid);
    }

    public void leaveTestMode(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInTestMode(playerUuid)) {
            return;
        }

        testModePlayers.remove(playerUuid);
        try {
            plugin.getTimerManager().resetPlayerSession(playerUuid, player.getWorld());
        } catch (Throwable ignored) {
        }
        try {
            plugin.getScoreboardManager().hideScoreboard(player);
        } catch (Throwable ignored) {
        }
    }

    public void clearTemporarySpawn(UUID playerUuid) {
        if (playerUuid == null) return;
        temporarySpawns.remove(playerUuid);
    }

    public org.bukkit.Location getResolvedRespawnLocation(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || player.getWorld() == null) {
            return null;
        }

        // prefer temporary spawn when set
        org.bukkit.Location temp = temporarySpawns.get(playerUuid);
        if (temp != null && temp.getWorld() != null) {
            return temp.clone();
        }

        // otherwise fall back to island spawn
        PlayerSession session = sessions.get(playerUuid);
        if (session == null) {
            return player.getWorld().getSpawnLocation();
        }

        Arena arena = arenaManager.getArena(session.getArenaName());
        if (arena == null) {
            return player.getWorld().getSpawnLocation();
        }

        ArenaIsland island = arena.findIslandByPlayer(playerUuid).orElse(null);
        if (island == null) {
            org.bukkit.Location spawn = player.getWorld().getSpawnLocation();
            return spawn == null ? null : spawn.clone();
        }

        ArenaLocation spawnLocation = island.getSpawnLocation();
        return new org.bukkit.Location(
                player.getWorld(),
                spawnLocation.getX(),
                spawnLocation.getY(),
                spawnLocation.getZ(),
                spawnLocation.getYaw(),
                spawnLocation.getPitch()
        );
    }

    public org.bukkit.Location getTestModeSpawnLocation(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        org.bukkit.Location temp = temporarySpawns.get(playerUuid);
        return temp == null ? null : temp.clone();
    }

    private Location determineSafeSpawn(Player player) {
        World world = player.getWorld();
        Location spawn = world.getSpawnLocation();
        if (spawn != null) {
            return spawn;
        }
        return new Location(world, 0, 100, 0);
    }
}
