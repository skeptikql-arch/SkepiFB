package me.skepi.skepifb.timer;

import me.skepi.skepifb.replay.ReplayBlockEvent;
import me.skepi.skepifb.replay.ReplayFrame;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AttemptSession {

    private final UUID playerUuid;
    private final BlockTracker blockTracker;
    private final List<ReplayFrame> frames = new ArrayList<>();
    private ReplayFrame currentFrame;
    private java.util.UUID attemptId;
    private int movementPacketCount;
    private int finalMovementPacketCount;
    private int replayFrameCount;
    private int tickCount;
    private int blockPlacementCount;
    private boolean running;
    private boolean activeAttempt;
    private boolean firstBlockPlacementTracked;
    private boolean finished;
    private Location lastSpeedLocation;
    private Location lastAverageSpeedLocation;
    private double currentSpeed;
    private double averageSpeed;
    private int speedSampleCount;
    private int nextPlaytimeXpRewardTick;
    // %jumpticks% bookkeeping - see updateJumpTicks() for the counting rule.
    private int groundTicksSinceLanding;
    private int lastJumpTicks;
    private boolean airborne;

    public AttemptSession(UUID playerUuid) {
        this.playerUuid = playerUuid;
        this.blockTracker = new BlockTracker();
        this.movementPacketCount = 0;
        this.finalMovementPacketCount = 0;
        this.replayFrameCount = 0;
        this.blockPlacementCount = 0;
        this.running = false;
        this.activeAttempt = false;
        this.firstBlockPlacementTracked = false;
        this.finished = false;
        this.lastSpeedLocation = null;
        this.lastAverageSpeedLocation = null;
        this.currentSpeed = 0.0;
        this.averageSpeed = 0.0;
        this.speedSampleCount = 0;
        this.attemptId = null;
        this.nextPlaytimeXpRewardTick = 6000;
        this.groundTicksSinceLanding = 0;
        this.lastJumpTicks = 0;
        this.airborne = false;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean hasActiveAttempt() {
        return activeAttempt;
    }

    public boolean isFirstBlockPlacementOfAttempt() {
        return !firstBlockPlacementTracked;
    }

    public void markFirstBlockPlacementTracked() {
        firstBlockPlacementTracked = true;
    }

    public void startAttempt() {
        startAttempt(false);
    }

    public void startAttempt(boolean preserveTrackedBlocks) {
        movementPacketCount = 0;
        finalMovementPacketCount = 0;
        replayFrameCount = 0;
        blockPlacementCount = 0;
        running = true;
        activeAttempt = true;
        firstBlockPlacementTracked = false;
        finished = false;
        if (!preserveTrackedBlocks) {
            blockTracker.clear();
        }
        frames.clear();
        currentFrame = new ReplayFrame(0);
        resetSpeedTracking();
        resetJumpTicks();
        frames.add(currentFrame);
        attemptId = java.util.UUID.randomUUID();
        if (preserveTrackedBlocks) {
            blockTracker.updateTrackedBlocksSessionId(attemptId);
        }
    }

    public boolean hasFinished() {
        return finished;
    }

    public void markFinished() {
        finished = true;
    }

    public void clearFinished() {
        finished = false;
    }

    public java.util.List<org.bukkit.Location> getTrackedBlockLocations(org.bukkit.World world) {
        return blockTracker.snapshotTrackedLocations(world);
    }

    public void removeTrackedBlocksInWorld(org.bukkit.World world) {
        blockTracker.removeTrackedBlocks(world);
    }

    /**
     * Advances the real-time tick counter only. This is deliberately NOT tied to replay frame
     * creation (see recordMovementFrame below) - it exists purely for real-time bookkeeping that
     * should keep advancing at a fixed rate regardless of the player's movement-packet rate, e.g.
     * playtime XP rewards and average-speed sampling cadence.
     */
    public void advanceTick() {
        if (!running) {
            return;
        }
        tickCount++;
    }

    /**
     * THE ACTUAL BUG behind replay recording being inconsistent with the scored timer: replay
     * frames used to be created here in lockstep with advanceTick() - i.e. once per real server
     * tick, 20 times a second, no matter what. But the scored timer (see getElapsedSeconds below)
     * is entirely movement-packet-based: it only advances when the client actually sends a
     * movement update, so a player running with a reduced packet rate (e.g. a throttled/slowed
     * client) sees their scored time advance more slowly than the real clock. Recording replay
     * frames on a fixed real-tick schedule instead of on that same packet cadence meant a slowed
     * run got a replay with a normal (real-time) frame count and duration - completely
     * inconsistent with how short/fast the scored time turned out, and prone to stretches of
     * stale duplicate-position frames between sparse packets. Moving frame creation here, so a
     * new replay frame is recorded exactly once per movement packet (the same trigger, and the
     * same count, as the scored timer), means replay length now always matches scored time
     * exactly, and playing it back at the normal fixed real-tick rate reconstructs the original
     * motion smoothly with no stutter, regardless of how fast or slow packets were arriving while
     * it was recorded.
     */
    public void recordMovementFrame(org.bukkit.Location location, float yaw, float pitch, boolean sneaking, boolean sprinting, String heldMaterial, int ping, int leftCps, int rightCps) {
        if (!running) {
            return;
        }
        movementPacketCount++;
        replayFrameCount++;
        currentFrame = new ReplayFrame(replayFrameCount);
        frames.add(currentFrame);
        if (location != null) {
            currentFrame.setPosition(location.getX(), location.getY(), location.getZ());
        }
        currentFrame.setRotation(yaw, pitch);
        currentFrame.setSneaking(sneaking);
        currentFrame.setSprinting(sprinting);
        currentFrame.setHeldMaterial(heldMaterial);
        currentFrame.setReplayStats(ping, leftCps, rightCps);
        currentFrame.setJumpTicks(getJumpTicks());
    }

    
    public void trackBlockPlace() {
        if (!running) {
            return;
        }
        blockPlacementCount++;
    }

    public void updateCurrentFrame(org.bukkit.Location location, float yaw, float pitch, boolean sneaking, boolean sprinting, String heldMaterial, int ping, int leftCps, int rightCps) {
        if (!running || currentFrame == null || location == null) {
            return;
        }
        currentFrame.setPosition(location.getX(), location.getY(), location.getZ());
        currentFrame.setRotation(yaw, pitch);
        currentFrame.setSneaking(sneaking);
        currentFrame.setSprinting(sprinting);
        currentFrame.setHeldMaterial(heldMaterial);
        currentFrame.setReplayStats(ping, leftCps, rightCps);
        currentFrame.setJumpTicks(getJumpTicks());
    }

    public void addArmSwing(String armSwing) {
        if (!running || currentFrame == null) {
            return;
        }
        currentFrame.addArmSwing(armSwing);
    }

    public void addBlockPlacement(ReplayBlockEvent event) {
        if (!running || currentFrame == null || event == null) {
            return;
        }
        currentFrame.addPlacement(event);
    }

    public void addBlockBreak(ReplayBlockEvent event) {
        if (!running || currentFrame == null || event == null) {
            return;
        }
        currentFrame.addBreak(event);
    }

    public void trackBlock(Block block) {
        if (block == null) {
            return;
        }
        blockTracker.track(block.getX(), block.getY(), block.getZ(), block.getWorld().getName(), playerUuid, attemptId);
    }

    public int getBlockPlacementCount() {
        return blockPlacementCount;
    }

    public void stopAttempt() {
        if (!running) {
            return;
        }
        running = false;
        finalMovementPacketCount = movementPacketCount;
        activeAttempt = false;
    }

    public void finishAttempt() {
        stopAttempt();
        finished = true;
    }

    public void endAttempt() {
        stopAttempt();
        finished = false;
        blockPlacementCount = 0;
        blockTracker.clear();
    }

    public void resetAttempt(World world) {
        running = false;
        activeAttempt = false;
        finished = false;
        movementPacketCount = 0;
        finalMovementPacketCount = 0;
        replayFrameCount = 0;
        tickCount = 0;
        blockPlacementCount = 0;
        // tracked blocks are removed by the caller via the animations manager
        frames.clear();
        currentFrame = null;
        attemptId = null;
        resetSpeedTracking();
        resetPlaytimeXpRewardTick();
        resetJumpTicks();
    }

    public void resetJumpTicks() {
        groundTicksSinceLanding = 0;
        lastJumpTicks = 0;
        airborne = false;
    }

    /**
     * Called once per real server tick (from TimerManager#checkFinishForRunningPlayers) with the
     * player's current on-ground state, only while an attempt is running. Implements the
     * %jumpticks% counting rule: while grounded, count up the number of consecutive ticks spent
     * on the ground; the instant the player leaves the ground (a jump), freeze that count and
     * stop incrementing it; keep reporting the frozen count for the whole time they're airborne;
     * reset back to a fresh count of 0 the instant they touch the ground again.
     */
    public void updateJumpTicks(boolean onGround) {
        if (!running) {
            return;
        }
        if (onGround) {
            if (airborne) {
                airborne = false;
                groundTicksSinceLanding = 0;
            } else {
                groundTicksSinceLanding++;
            }
        } else if (!airborne) {
            lastJumpTicks = groundTicksSinceLanding;
            airborne = true;
        }
    }

    public int getJumpTicks() {
        return airborne ? lastJumpTicks : groundTicksSinceLanding;
    }

    public void resetSpeedTracking() {
        this.lastSpeedLocation = null;
        this.lastAverageSpeedLocation = null;
        this.currentSpeed = 0.0;
        this.averageSpeed = 0.0;
        this.speedSampleCount = 0;
    }

    public int getNextPlaytimeXpRewardTick() {
        return nextPlaytimeXpRewardTick;
    }

    public void advanceNextPlaytimeXpRewardTick() {
        this.nextPlaytimeXpRewardTick += 6000;
    }

    public void resetPlaytimeXpRewardTick() {
        this.nextPlaytimeXpRewardTick = 6000;
    }

    public void updateLiveSpeed(Location location) {
        if (!running || location == null) {
            return;
        }
        if (lastSpeedLocation == null) {
            lastSpeedLocation = location.clone();
            currentSpeed = 0.0;
            return;
        }
        double distance = lastSpeedLocation.distance(location);
        double elapsedSeconds = 0.05;
        if (elapsedSeconds > 0.0) {
            currentSpeed = distance / elapsedSeconds;
        }
        lastSpeedLocation = location.clone();
    }

    public void sampleAverageSpeed(Location location) {
        if (!running || location == null) {
            return;
        }
        if (lastAverageSpeedLocation == null) {
            lastAverageSpeedLocation = location.clone();
            return;
        }
        double distance = lastAverageSpeedLocation.distance(location);
        double elapsedSeconds = 5 * 0.05;
        if (elapsedSeconds > 0.0) {
            double speed = distance / elapsedSeconds;
            if (speedSampleCount == 0) {
                averageSpeed = speed;
            } else {
                averageSpeed = ((averageSpeed * speedSampleCount) + speed) / (speedSampleCount + 1);
            }
            speedSampleCount++;
        }
        lastAverageSpeedLocation = location.clone();
    }

    public int getTickCount() {
        return tickCount;
    }

    public double getCurrentSpeed() {
        return currentSpeed;
    }

    public double getAverageSpeed() {
        return averageSpeed;
    }

    public String getCurrentSpeedText() {
        return lastSpeedLocation == null ? "0.00" : String.format(Locale.ROOT, "%.2f", currentSpeed);
    }

    public String getAverageSpeedText() {
        return speedSampleCount <= 0 ? "0.00" : String.format(Locale.ROOT, "%.2f", averageSpeed);
    }

    public double getTimerSeconds() {
        return (running ? movementPacketCount : finalMovementPacketCount) * 0.05;
    }

    public int getMovementPacketCount() {
        return movementPacketCount;
    }

    public int getFinalMovementPacketCount() {
        return finalMovementPacketCount;
    }

    /**
     * Rewinds just the elapsed-timer bookkeeping to a previously-saved point (used by smart
     * practice mode checkpoints) - deliberately touches nothing else (replay frames, block
     * tracking, attempt id, etc.) so it can't desync anything beyond the scored elapsed time
     * itself.
     */
    public void restoreCheckpointTimer(int movementPacketCount, boolean running) {
        this.movementPacketCount = movementPacketCount;
        this.finalMovementPacketCount = movementPacketCount;
        this.running = running;
    }

    public List<ReplayFrame> getFrames() {
        return Collections.unmodifiableList(frames);
    }

    public String getTimerText() {
        return String.format(Locale.ROOT, "%.3f", getTimerSeconds());
    }

    public void clearTrackedBlocks() {
        blockTracker.clear();
    }

    public boolean hasTrackedBlocks() {
        return blockTracker.hasTrackedBlocks();
    }

    public void removeTrackedBlocks(org.bukkit.World world) {
        blockTracker.removeTrackedBlocks(world);
    }

    public java.util.UUID getAttemptId() {
        return attemptId;
    }

    public boolean isOwnerOfBlock(Block block, java.util.UUID playerUuid) {
        if (block == null) return false;
        return blockTracker.isOwnedBy(block.getWorld(), block.getX(), block.getY(), block.getZ(), playerUuid, attemptId);
    }

    public void removeTrackedBlock(Block block) {
        if (block == null) return;
        blockTracker.removeSingleTrackedBlock(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }
}
