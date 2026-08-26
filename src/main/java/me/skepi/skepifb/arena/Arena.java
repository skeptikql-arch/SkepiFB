package me.skepi.skepifb.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class Arena {

    private final String name;
    private final String schematic;
    private final int islandCount;
    private final int spacing;
    private final Layout layout;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final ArenaBoundary boundary;
    private float spawnYaw;
    private float spawnPitch;
    private final List<ArenaIsland> islands = new ArrayList<>();

    public Arena(String name, String schematic, int islandCount, int spacing, Layout layout, int originX, int originY, int originZ) {
        this(name, schematic, islandCount, spacing, layout, originX, originY, originZ, ArenaBoundary.defaultBoundary(spacing));
    }

    public Arena(String name, String schematic, int islandCount, int spacing, Layout layout, int originX, int originY, int originZ, ArenaBoundary boundary) {
        this(name, schematic, islandCount, spacing, layout, originX, originY, originZ, boundary, 0.0f, 0.0f);
    }

    public Arena(String name, String schematic, int islandCount, int spacing, Layout layout, int originX, int originY, int originZ, ArenaBoundary boundary, float spawnYaw, float spawnPitch) {
        this.name = name;
        this.schematic = schematic;
        this.islandCount = islandCount;
        this.spacing = spacing;
        this.layout = layout;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.boundary = boundary;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;

        for (int index = 1; index <= islandCount; index++) {
            islands.add(new ArenaIsland(index, calculateSpawn(index), null));
        }
    }

    private ArenaLocation calculateSpawn(int index) {
        int offsetX = (index - 1) * spacing;
        int offsetZ = layout == Layout.DIAGONAL ? (index - 1) * spacing : 0;
        return new ArenaLocation(originX - offsetX + 0.5, originY, originZ + offsetZ + 0.5, spawnYaw, spawnPitch);
    }

    public float getSpawnYaw() {
        return spawnYaw;
    }

    public float getSpawnPitch() {
        return spawnPitch;
    }

    /**
     * Updates the facing direction every island in this arena spawns/respawns with. There was
     * previously no way to set this to anything but 0/0 (hardcoded at arena creation) - arenas.yml
     * could technically store a real value and everything downstream (join, reset, replay-viewer
     * teleport) already correctly read spawnYaw/spawnPitch from it, but nothing ever WROTE
     * anything other than 0 into it. This is what /fb setfacing calls, standing at the intended
     * spot and facing the intended direction, to actually give arenas.yml a real value.
     */
    public void updateSpawnFacing(float yaw, float pitch) {
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
        for (ArenaIsland island : islands) {
            ArenaLocation old = island.getSpawnLocation();
            if (old == null) {
                continue;
            }
            island.setSpawnLocation(new ArenaLocation(old.getX(), old.getY(), old.getZ(), yaw, pitch));
        }
    }

    public String getName() {
        return name;
    }

    public String getSchematic() {
        return schematic;
    }

    public int getIslandCount() {
        return islandCount;
    }

    public int getSpacing() {
        return spacing;
    }

    public Layout getLayout() {
        return layout;
    }

    public int getOriginX() {
        return originX;
    }

    public int getOriginY() {
        return originY;
    }

    public int getOriginZ() {
        return originZ;
    }

    public ArenaBoundary getBoundary() {
        return boundary;
    }

    public List<ArenaIsland> getIslands() {
        return islands;
    }

    public Optional<ArenaIsland> findIslandByPlayer(UUID playerUuid) {
        return islands.stream().filter(island -> playerUuid.equals(island.getOccupiedPlayer())).findFirst();
    }

    public Optional<ArenaIsland> findAvailableIsland() {
        return islands.stream().filter(island -> island.getOccupiedPlayer() == null).findFirst();
    }
}
