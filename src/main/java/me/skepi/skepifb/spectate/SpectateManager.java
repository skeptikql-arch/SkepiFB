package me.skepi.skepifb.spectate;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Backs /spectate, /spec, and /fb spectate: lets a player spectate another online player by
 * switching to spectator mode and teleporting to them, then restores their previous location and
 * puts them back into survival mode when they exit ("/spectate exit" / "/spec exit").
 */
public class SpectateManager {

    private final Map<UUID, Location> previousLocations = new HashMap<>();
    private final Map<UUID, GameMode> previousGameModes = new HashMap<>();

    public boolean isSpectating(UUID playerUuid) {
        return previousGameModes.containsKey(playerUuid);
    }

    /**
     * Starts (or re-targets, if already spectating someone) spectating "target". The spectator's
     * ORIGINAL location/gamemode - from before they first started spectating - is only ever saved
     * once, so hopping between several spectate targets in a row still returns them to where they
     * actually started once they exit, not wherever they most recently happened to be spectating
     * from.
     */
    public void startSpectating(Player spectator, Player target) {
        UUID spectatorUuid = spectator.getUniqueId();
        if (!isSpectating(spectatorUuid)) {
            previousLocations.put(spectatorUuid, spectator.getLocation());
            previousGameModes.put(spectatorUuid, spectator.getGameMode());
        }
        spectator.setGameMode(GameMode.SPECTATOR);
        spectator.teleport(target.getLocation());
    }

    /**
     * Ends spectating: teleports back to wherever the player was standing before they started
     * spectating, and always sets their gamemode back to survival - not whatever mode they were in
     * before - since /spectate exit is meant to hand a player back into normal play. Returns false
     * (and does nothing) if the player wasn't spectating anyone.
     */
    public boolean stopSpectating(Player player) {
        UUID playerUuid = player.getUniqueId();
        if (!isSpectating(playerUuid)) {
            return false;
        }
        Location previousLocation = previousLocations.remove(playerUuid);
        previousGameModes.remove(playerUuid);
        player.setGameMode(GameMode.SURVIVAL);
        if (previousLocation != null && previousLocation.getWorld() != null) {
            player.teleport(previousLocation);
        }
        return true;
    }

    /**
     * Called on quit/disconnect so a player who logs out while spectating doesn't stay flagged as
     * spectating forever in memory. Their gamemode/location on next join is left entirely up to
     * whatever the server/other plugins normally decide - this only clears our own bookkeeping.
     */
    public void clear(UUID playerUuid) {
        previousLocations.remove(playerUuid);
        previousGameModes.remove(playerUuid);
    }
}
