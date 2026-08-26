package me.skepi.skepifb.player;

import java.util.UUID;

public class PlayerSession {

    private final UUID playerUuid;
    private final String arenaName;
    private final int islandIndex;

    public PlayerSession(UUID playerUuid, String arenaName, int islandIndex) {
        this.playerUuid = playerUuid;
        this.arenaName = arenaName;
        this.islandIndex = islandIndex;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getArenaName() {
        return arenaName;
    }

    public int getIslandIndex() {
        return islandIndex;
    }
}
