package me.skepi.skepifb.placeholder;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.replay.ReplayFrame;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nine placeholders for the REPLAY HOLOGRAM ONLY - %xcoordinate% %ycoordinate% %zcoordinate% %yaw%
 * %pitch% %ping% %leftcps% %rightcps% %jumpticks%, configurable in "replay-hologram.lines". These
 * are NOT general-purpose/global placeholders and are not wired into chat, the scoreboard, or the
 * statboard - they only resolve here, against a replay.
 * <p>
 * Critically, a replay is a played-back RECORDING, not a live view - so a line like "%ping%" on the
 * hologram has to show what the player's ping actually WAS at that exact moment during their run,
 * not whatever the viewer's own ping happens to be right now while watching. That means the 9
 * values are captured once per frame while the run is happening (see the two capture methods below,
 * called from AttemptSession#recordMovementFrame/updateCurrentFrame via TimerManager), stored on the
 * ReplayFrame itself (see ReplayFrame#setReplayStats, persisted to the replay .yml by
 * ReplayManager), and only ever substituted back out of that stored frame data during playback (see
 * {@link #applyRecorded}) - never re-read live from whoever happens to be watching.
 * <p>
 * %xcoordinate%/%ycoordinate%/%zcoordinate%/%yaw%/%pitch% were already recorded per-frame before
 * this class existed (ReplayFrame's position/rotation fields) and are read from there directly.
 * %ping%/%leftcps%/%rightcps% are new fields added to ReplayFrame specifically to support this.
 * %jumpticks% is sourced the same way: AttemptSession#updateJumpTicks counts, once per real tick
 * while an attempt is running, how many consecutive ticks the player has spent on the ground since
 * they last landed; the moment they leave the ground that count freezes (stops incrementing) until
 * they touch down again, at which point it resets to 0 and starts counting up again. Whatever that
 * live value is at the moment each frame is captured gets stamped onto the frame (see
 * AttemptSession#recordMovementFrame/updateCurrentFrame) the same way ping/CPS are.
 */
public class ReplayPlaceholderManager implements Listener {

    private static final long CPS_WINDOW_MILLIS = 1000L;

    private final SkepiFBPlugin plugin;
    private final Map<UUID, Deque<Long>> leftClickTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> rightClickTimestamps = new ConcurrentHashMap<>();

    public ReplayPlaceholderManager(SkepiFBPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Reads a player's CURRENT ping. Only ever called while an attempt is actively being recorded
     * (to stamp a frame with what ping was AT that moment) - never during playback.
     */
    public static int getCurrentPing(Player player) {
        if (player == null) {
            return 0;
        }
        try {
            // Officially part of the Paper API this plugin compiles against (Player#getPing()),
            // but called reflectively anyway so a Spigot-only server (no Paper-specific method)
            // degrades to 0 instead of throwing a NoSuchMethodError at class-load time.
            Object ping = player.getClass().getMethod("getPing").invoke(player);
            if (ping instanceof Integer) {
                return (Integer) ping;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Object ping = spigot.getClass().getMethod("getPing").invoke(spigot);
            if (ping instanceof Integer) {
                return (Integer) ping;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Reads a player's CURRENT left-click CPS (rolling 1-second window). Only ever called while an
     * attempt is actively being recorded - never during playback.
     */
    public int getCurrentLeftCps(UUID uuid) {
        return countRecentClicks(leftClickTimestamps, uuid);
    }

    /**
     * Reads a player's CURRENT right-click CPS (rolling 1-second window). Only ever called while an
     * attempt is actively being recorded - never during playback.
     */
    public int getCurrentRightCps(UUID uuid) {
        return countRecentClicks(rightClickTimestamps, uuid);
    }

    /**
     * Replaces the 9 replay placeholders in "line" using values RECORDED on "frame" - this is the
     * only placeholder-substitution entry point that should ever be called during replay playback.
     * Safe to call with a line containing none of the placeholders, or a null frame/line (returns
     * the input unchanged rather than throwing).
     */
    public String applyRecorded(ReplayFrame frame, String line) {
        if (line == null || line.isEmpty() || frame == null) {
            return line;
        }
        String result = line;

        if (result.contains("%xcoordinate%")) {
            result = result.replace("%xcoordinate%", formatCoordinate(frame.getX()));
        }
        if (result.contains("%ycoordinate%")) {
            result = result.replace("%ycoordinate%", formatCoordinate(frame.getY()));
        }
        if (result.contains("%zcoordinate%")) {
            result = result.replace("%zcoordinate%", formatCoordinate(frame.getZ()));
        }
        if (result.contains("%yaw%")) {
            result = result.replace("%yaw%", format(normalizeYaw(frame.getYaw())));
        }
        if (result.contains("%pitch%")) {
            result = result.replace("%pitch%", format(frame.getPitch()));
        }
        if (result.contains("%ping%")) {
            result = result.replace("%ping%", String.valueOf(frame.getPing()));
        }
        if (result.contains("%leftcps%")) {
            result = result.replace("%leftcps%", String.valueOf(frame.getLeftCps()));
        }
        if (result.contains("%rightcps%")) {
            result = result.replace("%rightcps%", String.valueOf(frame.getRightCps()));
        }
        if (result.contains("%jumpticks%")) {
            result = result.replace("%jumpticks%", String.valueOf(frame.getJumpTicks()));
        }

        return result;
    }

    private static double normalizeYaw(float rawYaw) {
        float yaw = rawYaw % 360.0f;
        if (yaw < 0) {
            yaw += 360.0f;
        }
        return yaw;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // %xcoordinate%/%ycoordinate%/%zcoordinate% specifically use 3 decimal places (yaw/pitch/etc.
    // above stay at 1 via format()).
    private static String formatCoordinate(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static int countRecentClicks(Map<UUID, Deque<Long>> store, UUID uuid) {
        Deque<Long> timestamps = store.get(uuid);
        if (timestamps == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - CPS_WINDOW_MILLIS;
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            return timestamps.size();
        }
    }

    private static void recordClick(Map<UUID, Deque<Long>> store, UUID uuid) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = store.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        long cutoff = now - CPS_WINDOW_MILLIS;
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            timestamps.addLast(now);
        }
    }

    // Every left click swings the arm (mining, attacking, or just clicking at empty air), so this
    // fires once per left click regardless of what's being clicked on/at - the standard approach
    // CPS-counter plugins use for the left-click half of the count. Recorded unconditionally for
    // every online player (cheap - just a timestamp in a deque) so it's always warmed up and
    // accurate the instant an attempt starts, rather than only tracking players already known to be
    // mid-attempt.
    @EventHandler(ignoreCancelled = false)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        recordClick(leftClickTimestamps, event.getPlayer().getUniqueId());
    }

    // Only counts main-hand interactions so a single physical right click (which Bukkit can fire
    // PlayerInteractEvent for once per hand) isn't double-counted as two clicks.
    @EventHandler(ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
                recordClick(rightClickTimestamps, event.getPlayer().getUniqueId());
                break;
            default:
                break;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        leftClickTimestamps.remove(uuid);
        rightClickTimestamps.remove(uuid);
    }
}
