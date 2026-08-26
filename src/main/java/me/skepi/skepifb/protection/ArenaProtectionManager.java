package me.skepi.skepifb.protection;

import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaManager;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.SkepiFBPlugin;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import java.util.UUID;

public final class ArenaProtectionManager implements Listener {

    private final PlayerManager playerManager;
    private final ArenaManager arenaManager;
    private final SkepiFBPlugin plugin;

    public ArenaProtectionManager(SkepiFBPlugin plugin, PlayerManager playerManager, ArenaManager arenaManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.arenaManager = arenaManager;
    }

    public void registerListeners() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        lockPlayerStats(player);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        event.setFoodLevel(20);
        event.setCancelled(true);
    }

    @EventHandler
    public void onEntityRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        lockPlayerStats(player);
    }

    private void lockPlayerStats(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
        if (player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }
}
