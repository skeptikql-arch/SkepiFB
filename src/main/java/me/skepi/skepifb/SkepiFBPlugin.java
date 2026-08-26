package me.skepi.skepifb;

import me.skepi.skepifb.arena.ArenaManager;
import me.skepi.skepifb.commands.FBCommand;
import me.skepi.skepifb.config.ConfigManager;
import me.skepi.skepifb.hotbar.HotbarManager;
import me.skepi.skepifb.inventory.ArenaBlockProvider;
import me.skepi.skepifb.inventory.InventoryManager;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.protection.ArenaProtectionManager;
import me.skepi.skepifb.chat.ChatPlaceholderManager;
import me.skepi.skepifb.scoreboard.ScoreboardManager;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.leaderboard.LeaderboardManager;
import me.skepi.skepifb.tags.TagManager;
import me.skepi.skepifb.schematic.SchematicService;
import me.skepi.skepifb.shop.ShopManager;
import me.skepi.skepifb.stats.PlayerStatsManager;
import me.skepi.skepifb.replay.ReplayManager;
import me.skepi.skepifb.storage.StorageManager;
import me.skepi.skepifb.timer.TimerManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkepiFBPlugin extends JavaPlugin {
    public static final String BUILD_TAG = "2026-08-23d-falling-no-gap-islandshop-crash-blockshop-expand-prefix-priority";

    private ArenaManager arenaManager;
    private PlayerManager playerManager;
    private TimerManager timerManager;
    private ArenaProtectionManager protectionManager;
    private StorageManager storageManager;
    private SchematicService schematicService;
    private ConfigManager configManager;
    private ArenaBlockProvider arenaBlockProvider;
    private InventoryManager inventoryManager;
    private ScoreboardManager scoreboardManager;
    private ChatPlaceholderManager chatPlaceholderManager;
    private PlayerStatsManager statsManager;
    private me.skepi.skepifb.stats.StatboardManager statboardManager;
    private ShopManager shopManager;
    private me.skepi.skepifb.animations.AnimationsManager animationsManager;
    private HotbarManager hotbarManager;
    private ReplayManager replayManager;
    private LeaderboardManager leaderboardManager;
    private TagManager tagManager;
    private me.skepi.skepifb.session.SessionTopManager sessionTopManager;

    @Override
    public void onEnable() {
        // Unmistakable startup marker: if this exact line (with this exact build tag) is NOT the
        // one you see in console when the server starts, the jar in /plugins is not the one that
        // was just built - restart troubleshooting from there instead of assuming any given source
        // fix is wrong. Bump BUILD_TAG below whenever this is used to verify a fresh deploy.
        getLogger().info("========================================================");
        getLogger().info("SkepiFB starting - build tag: " + BUILD_TAG);
        getLogger().info("========================================================");
        this.storageManager = new StorageManager(this);
        this.schematicService = new SchematicService(this);
        this.arenaManager = new ArenaManager(this, storageManager, schematicService);
        this.configManager = new ConfigManager(this);
        this.replayManager = new ReplayManager(this);
        this.arenaBlockProvider = new ArenaBlockProvider(configManager);
        this.inventoryManager = new InventoryManager(this, configManager, arenaBlockProvider);
        this.playerManager = new PlayerManager(this, arenaManager);
        this.statsManager = new PlayerStatsManager(this);
        this.statboardManager = new me.skepi.skepifb.stats.StatboardManager(this);
        this.shopManager = new ShopManager(this, configManager);
        this.hotbarManager = new HotbarManager(this, configManager);
        this.animationsManager = new me.skepi.skepifb.animations.AnimationsManager(this, shopManager);
        this.leaderboardManager = new LeaderboardManager(this);
        this.tagManager = new TagManager(this);
        this.sessionTopManager = new me.skepi.skepifb.session.SessionTopManager(this, configManager);
        this.timerManager = new TimerManager(this, playerManager, arenaManager, inventoryManager, statsManager, hotbarManager);
        this.scoreboardManager = new ScoreboardManager(this, timerManager, playerManager);
        this.chatPlaceholderManager = new ChatPlaceholderManager(this, configManager, scoreboardManager);
        this.protectionManager = new ArenaProtectionManager(this, playerManager, arenaManager);

        arenaManager.loadArenas();
        playerManager.registerListeners();
        timerManager.registerListeners();
        chatPlaceholderManager.registerListeners();
        protectionManager.registerListeners();
        shopManager.registerListeners();
        hotbarManager.registerListeners();
        getCommand("fb").setExecutor(new FBCommand(this, arenaManager, playerManager, schematicService, scoreboardManager, inventoryManager, hotbarManager));
    }

    public me.skepi.skepifb.stats.StatboardManager getStatboardManager() {
        return statboardManager;
    }

    public enum ArenaJoinResult {
        SUCCESS,
        ALREADY_IN_ARENA,
        ARENA_NOT_FOUND,
        NO_ISLANDS_AVAILABLE,
        FAILED
    }

    public ArenaJoinResult joinPlayerToArena(Player player, String arenaName) {
        Arena targetArena = arenaManager.getArena(arenaName);
        if (targetArena == null) {
            return ArenaJoinResult.ARENA_NOT_FOUND;
        }

        // If player is already in an arena, cleanly leave it first using the shared leave logic.
        if (timerManager != null && timerManager.isInReplay(player.getUniqueId())) {
            timerManager.closeReplay(player);
        }

        if (playerManager.isInArena(player.getUniqueId())) {
            playerManager.leaveArena(player);
        }

        if (targetArena.findAvailableIsland().isEmpty()) {
            return ArenaJoinResult.NO_ISLANDS_AVAILABLE;
        }

        boolean joined = arenaManager.joinArena(player, targetArena);
        if (!joined) {
            return ArenaJoinResult.FAILED;
        }

        if (shopManager != null) {
            shopManager.ensureEquippedTool(player);
        }

        inventoryManager.giveArenaBlock(player);
        hotbarManager.giveHotbarToPlayer(player);
        scoreboardManager.showScoreboard(player);
        return ArenaJoinResult.SUCCESS;
    }

    public TimerManager getTimerManager() {
        return timerManager;
    }

    public me.skepi.skepifb.session.SessionTopManager getSessionTopManager() {
        return sessionTopManager;
    }

    public ArenaProtectionManager getProtectionManager() {
        return protectionManager;
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PlayerStatsManager getStatsManager() {
        return statsManager;
    }

    public HotbarManager getHotbarManager() {
        return hotbarManager;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public me.skepi.skepifb.animations.AnimationsManager getAnimationsManager() {
        return animationsManager;
    }

    public ReplayManager getReplayManager() {
        return replayManager;
    }

    public LeaderboardManager getLeaderboardManager() {
        return leaderboardManager;
    }

    public TagManager getTagManager() {
        return tagManager;
    }

    @Override
    public void onDisable() {
        if (statboardManager != null) {
            try { statboardManager.cleanup(); } catch (Throwable ignored) {}
        }
        if (arenaManager != null) {
            arenaManager.saveArenas();
        }
        if (timerManager != null) {
            try { timerManager.cleanupAllReplays(); } catch (Throwable ignored) {}
        }
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public SchematicService getSchematicService() {
        return schematicService;
    }
}
