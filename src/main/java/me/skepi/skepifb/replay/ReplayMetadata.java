package me.skepi.skepifb.replay;

import java.util.UUID;

public final class ReplayMetadata {
    private final UUID replayId;
    private final String arenaName;
    private final long timestamp;
    private final double durationSeconds;
    private final int blocksPlaced;
    private final UUID playerUuid;
    private final String playerName;
    private final int replayIndex;
    private final boolean wasPersonalBest;

    public ReplayMetadata(UUID replayId, String arenaName, long timestamp, double durationSeconds, int blocksPlaced, UUID playerUuid, String playerName, int replayIndex) {
        this(replayId, arenaName, timestamp, durationSeconds, blocksPlaced, playerUuid, playerName, replayIndex, false);
    }

    public ReplayMetadata(UUID replayId, String arenaName, long timestamp, double durationSeconds, int blocksPlaced, UUID playerUuid, String playerName, int replayIndex, boolean wasPersonalBest) {
        this.replayId = replayId;
        this.arenaName = arenaName;
        this.timestamp = timestamp;
        this.durationSeconds = durationSeconds;
        this.blocksPlaced = blocksPlaced;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.replayIndex = replayIndex;
        this.wasPersonalBest = wasPersonalBest;
    }

    public UUID getReplayId() {
        return replayId;
    }

    public String getArenaName() {
        return arenaName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public int getBlocksPlaced() {
        return blocksPlaced;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getReplayIndex() {
        return replayIndex;
    }

    /**
     * Whether this replay's time was a personal best at the moment it was recorded.
     * This is fixed at creation time and never changes afterwards - it is what lets the replay
     * GUI distinguish "used to be the PB" (unenchanted diamond block) from "is the current PB"
     * (enchanted diamond block, determined separately by comparing against the live best time).
     */
    public boolean isWasPersonalBest() {
        return wasPersonalBest;
    }
}
