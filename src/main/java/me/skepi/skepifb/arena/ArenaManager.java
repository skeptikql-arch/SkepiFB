package me.skepi.skepifb.arena;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.schematic.SchematicService;
import me.skepi.skepifb.storage.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ArenaManager {

    private final SkepiFBPlugin plugin;
    private final StorageManager storageManager;
    private final SchematicService schematicService;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(SkepiFBPlugin plugin, StorageManager storageManager, SchematicService schematicService) {
        this.plugin = plugin;
        this.storageManager = storageManager;
        this.schematicService = schematicService;
    }

    public void loadArenas() {
        storageManager.loadArenas(arenas);
    }

    public void reloadArenas() {
        loadArenas();
    }

    public void saveArenas() {
        storageManager.saveArenas(arenas);
    }

    public boolean arenaExists(String arenaName) {
        return arenas.containsKey(arenaName.toLowerCase());
    }

    public Arena getArena(String arenaName) {
        return arenas.get(arenaName.toLowerCase());
    }

    /**
     * Sets the facing direction every island in this arena spawns/respawns with, and persists it
     * to arenas.yml. Called by /fb setfacing with the admin's current yaw/pitch, standing at the
     * intended spot and facing the intended direction - previously arenas.yml only ever held the
     * hardcoded 0/0 baked in at arena creation, with no way to set anything else.
     */
    public boolean setArenaSpawnFacing(String arenaName, float yaw, float pitch) {
        Arena arena = getArena(arenaName);
        if (arena == null) {
            return false;
        }
        arena.updateSpawnFacing(yaw, pitch);
        saveArenas();
        return true;
    }

    public boolean hasArenas() {
        return !arenas.isEmpty();
    }

    public List<Arena> getArenas() {
        return Collections.unmodifiableList(new ArrayList<>(arenas.values()));
    }

    public Arena createArena(String name, String schematic, int islandCount, int spacing, Layout layout) {
        int arenaIndex = findNextArenaIndex();
        int originX = (arenaIndex + 1) * 5000;
        int originZ = 0;
        int originY = 100;
        Arena arena = new Arena(name, schematic, islandCount, spacing, layout, originX, originY, originZ, ArenaBoundary.defaultBoundary(spacing), 0.0f, 0.0f);
        arenas.put(name.toLowerCase(), arena);
        saveArenas();

        pasteArenaIslands(arena);
        return arena;
    }

    public void removeArena(String name) {
        arenas.remove(name.toLowerCase());
        saveArenas();
    }

    private int findNextArenaIndex() {
        int index = 0;
        for (Arena arena : arenas.values()) {
            index = Math.max(index, getArenaIndex(arena));
        }
        return index + 1;
    }

    private int getArenaIndex(Arena arena) {
        return arena.getOriginX() / 5000 - 1;
    }

    private void pasteArenaIslands(Arena arena) {
        if (!schematicService.isWorldEditAvailable()) {
            plugin.getLogger().warning("WorldEdit not present, skipping schematic paste for arena " + arena.getName());
            return;
        }

        World world = Bukkit.getWorlds().get(0);
        if (world == null) {
            plugin.getLogger().warning("No world available for schematic pasting.");
            return;
        }

        for (ArenaIsland island : arena.getIslands()) {
            Location pasteLocation = new Location(world, island.getSpawnLocation().getX(), island.getSpawnLocation().getY(), island.getSpawnLocation().getZ());
            schematicService.pasteSchematic(arena.getSchematic(), pasteLocation);
        }
    }

    public boolean pasteIsland(Arena arena, ArenaIsland island) {
        return pasteIsland(arena, island, null);
    }

    public boolean pasteIsland(Arena arena, ArenaIsland island, UUID playerUuid) {
        if (!schematicService.isWorldEditAvailable()) {
            plugin.getLogger().warning("WorldEdit not present, skipping schematic paste for arena " + (arena != null ? arena.getName() : "unknown"));
            return false;
        }
        if (arena == null || island == null) {
            return false;
        }

        World world = Bukkit.getWorlds().get(0);
        if (world == null) {
            plugin.getLogger().warning("No world available for schematic pasting.");
            return false;
        }

        Location pasteLocation = new Location(world, island.getSpawnLocation().getX(), island.getSpawnLocation().getY(), island.getSpawnLocation().getZ());
        String schematicName = resolveIslandSchematic(arena, playerUuid);
        if (schematicName == null || schematicName.isBlank()) {
            schematicName = arena.getSchematic();
        }
        return schematicService.pasteSchematic(schematicName, pasteLocation);
    }

    public boolean restoreIsland(Arena arena, ArenaIsland island) {
        return restoreIsland(arena, island, null);
    }

    public boolean restoreIsland(Arena arena, ArenaIsland island, UUID playerUuid) {
        if (arena == null || island == null) {
            return false;
        }
        // Restore the island to its base arena schematic, ignoring any player-specific cosmetic selection.
        return pasteIsland(arena, island);
    }

    private String resolveIslandSchematic(Arena arena, UUID playerUuid) {
        if (arena == null) {
            return null;
        }
        String arenaName = arena.getName();
        String mode = resolveIslandMode(arenaName);
        String selectedCosmetic = "default";
        if (playerUuid != null && plugin.getShopManager() != null) {
            String equippedValue = plugin.getShopManager().getEquippedShopItem(playerUuid, "island_shop", mode).orElse(null);
            if (equippedValue != null && !equippedValue.isBlank()) {
                String[] parts = equippedValue.split(":");
                if (parts.length >= 2 && "islands".equalsIgnoreCase(parts[0])) {
                    selectedCosmetic = parts[1];
                } else if (equippedValue.equalsIgnoreCase("default") || equippedValue.equalsIgnoreCase("islands:default")) {
                    selectedCosmetic = "default";
                }
            }
        }
        if (selectedCosmetic == null || selectedCosmetic.isBlank() || "default".equalsIgnoreCase(selectedCosmetic)) {
            return arena.getSchematic();
        }
        String schematic = plugin.getConfigManager().getIslandCosmeticSchematic(arenaName, mode, selectedCosmetic, arena.getSchematic());
        return schematic == null || schematic.isBlank() ? arena.getSchematic() : schematic;
    }

    private String resolveIslandMode(String arenaName) {
        if (arenaName == null || arenaName.isBlank()) {
            return "default";
        }
        String startMode = plugin.getConfigManager().getArenaStartMode(arenaName);
        String finishMode = plugin.getConfigManager().getArenaFinishMode(arenaName);
        if (startMode != null && !startMode.isBlank()) {
            return startMode.toLowerCase(Locale.ROOT);
        }
        if (finishMode != null && !finishMode.isBlank()) {
            return finishMode.toLowerCase(Locale.ROOT);
        }
        return "default";
    }

    public boolean joinArena(Player player, Arena arena) {
        if (arena.findIslandByPlayer(player.getUniqueId()).isPresent()) {
            return false;
        }

        return arena.findAvailableIsland().map(island -> {
            Location spawnLocation = new Location(Bukkit.getWorlds().get(0), island.getSpawnLocation().getX(), island.getSpawnLocation().getY(), island.getSpawnLocation().getZ(), island.getSpawnLocation().getYaw(), island.getSpawnLocation().getPitch());
            boolean pasted = pasteIsland(arena, island, player.getUniqueId());
            if (!pasted) {
                return false;
            }

            island.setOccupiedPlayer(player.getUniqueId());
            boolean teleported = player.teleport(spawnLocation);
            if (!teleported) {
                island.setOccupiedPlayer(null);
                return false;
            }
            plugin.getPlayerManager().trackPlayer(player.getUniqueId(), arena.getName(), island.getIndex());
            try { plugin.getStatboardManager().createStatboard(player.getUniqueId(), arena, island); } catch (Throwable ignored) {}
            saveArenas();
            return true;
        }).orElse(false);
    }

    public boolean joinArenaForTest(Player player, Arena arena) {
        if (player == null || arena == null) {
            return false;
        }
        if (arena.findIslandByPlayer(player.getUniqueId()).isPresent()) {
            return false;
        }
        return arena.findAvailableIsland().map(island -> {
            island.setOccupiedPlayer(player.getUniqueId());
            plugin.getPlayerManager().trackTestPlayer(player.getUniqueId(), arena.getName(), island.getIndex());
            try { plugin.getStatboardManager().createStatboard(player.getUniqueId(), arena, island); } catch (Throwable ignored) {}
            return true;
        }).orElse(false);
    }

    public void leaveArena(Player player) {
        PlayerManager playerManager = plugin.getPlayerManager();
        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            return;
        }

        Arena arena = getArena(arenaName);
        if (arena != null) {
            arena.findIslandByPlayer(player.getUniqueId()).ifPresent(island -> {
                island.setOccupiedPlayer(null);
                restoreIsland(arena, island, player.getUniqueId());
            });
            saveArenas();
        }

        playerManager.clearPlayer(player.getUniqueId());
        Location spawnLocation = determineSafeSpawn(player);
        player.teleport(spawnLocation);
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
