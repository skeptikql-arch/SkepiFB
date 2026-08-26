package me.skepi.skepifb.arena;

import java.util.UUID;

public class ArenaIsland {

    private final int index;
    private ArenaLocation spawnLocation;
    private UUID occupiedPlayer;

    public ArenaIsland(int index, ArenaLocation spawnLocation, UUID occupiedPlayer) {
        this.index = index;
        this.spawnLocation = spawnLocation;
        this.occupiedPlayer = occupiedPlayer;
    }

    public int getIndex() {
        return index;
    }

    public ArenaLocation getSpawnLocation() {
        return spawnLocation;
    }

    public void setSpawnLocation(ArenaLocation spawnLocation) {
        this.spawnLocation = spawnLocation;
    }

    public UUID getOccupiedPlayer() {
        return occupiedPlayer;
    }

    public void setOccupiedPlayer(UUID occupiedPlayer) {
        this.occupiedPlayer = occupiedPlayer;
    }
}
