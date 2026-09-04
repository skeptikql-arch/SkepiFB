package me.skepi.skepifb;

import me.skepi.skepifb.arena.ArenaManager;
import me.skepi.skepifb.commands.FBCommand;
import me.skepi.skepifb.config.ConfigManager;
import me.skepi.skepifb.hotbar.HotbarManager;
import me.skepi.skepifb.inventory.ArenaBlockProvider;
import me.skepi.skepifb.inventory.InventoryManager;
import me.skepi.skepifb.placeholder.ReplayPlaceholderManager;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.protection.ArenaProtectionManager;
import me.skepi.skepifb.chat.ChatPlaceholderManager;
import me.skepi.skepifb.chat.PeriodicMessageManager;
import me.skepi.skepifb.scoreboard.ScoreboardManager;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.leaderboard.LeaderboardManager;
import me.skepi.skepifb.permissions.PermissionsManager;
import me.skepi.skepifb.statsmenu.StatsMenuManager;
import me.skepi.skepifb.tags.TagManager;
import me.skepi.skepifb.schematic.SchematicService;
import me.skepi.skepifb.shop.ShopManager;
import me.skepi.skepifb.stats.PlayerStatsManager;
import me.skepi.skepifb.replay.ReplayManager;
import me.skepi.skepifb.storage.StorageManager;
import me.skepi.skepifb.timer.TimerManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkepiFBPlugin extends JavaPlugin {
    public static final String BUILD_TAG = "26.2-practice-spawn-template-menus-relative-fix";

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
    private PeriodicMessageManager periodicMessageManager;
    private PlayerStatsManager statsManager;
    private me.skepi.skepifb.stats.StatboardManager statboardManager;
    private ShopManager shopManager;
    private me.skepi.skepifb.animations.AnimationsManager animationsManager;
    private HotbarManager hotbarManager;
    private ReplayManager replayManager;
    private LeaderboardManager leaderboardManager;
    private me.skepi.skepifb.leaderboard.LeaderboardMenuManager leaderboardMenuManager;
    private TagManager tagManager;
    private PermissionsManager permissionsManager;
    private StatsMenuManager statsMenuManager;
    private me.skepi.skepifb.spectate.SpectateManager spectateManager;
    private me.skepi.skepifb.npc.IslandNpcManager islandNpcManager;
    private me.skepi.skepifb.session.SessionTopManager sessionTopManager;
    private ReplayPlaceholderManager replayPlaceholderManager;
    private me.skepi.skepifb.templates.PracticeTemplateManager practiceTemplateManager;
    private me.skepi.skepifb.templates.SpawnTemplateManager spawnTemplateManager;

    @Override
    public void onEnable() {
        // Removes any statboard/replay-hologram armor stands AND any island NPCs left behind by a
        // previous run of this plugin, BEFORE anything new gets created - see
        // removeLeftoverManagedEntities()'s own javadoc for why this has to happen unconditionally
        // on every startup rather than relying on onDisable alone.
        removeLeftoverManagedEntities();

        // Created before the timer/replay machinery, and its listeners registered immediately, so
        // click (CPS) tracking is already warm the instant any attempt starts recording - it only
        // ever feeds the replay hologram, not chat/scoreboard/statboard (see its class javadoc).
        this.replayPlaceholderManager = new ReplayPlaceholderManager(this);
        this.replayPlaceholderManager.registerListeners();
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
        this.practiceTemplateManager = new me.skepi.skepifb.templates.PracticeTemplateManager(this);
        this.spawnTemplateManager = new me.skepi.skepifb.templates.SpawnTemplateManager(this);
        this.animationsManager = new me.skepi.skepifb.animations.AnimationsManager(this, shopManager);
        this.leaderboardManager = new LeaderboardManager(this);
        this.leaderboardMenuManager = new me.skepi.skepifb.leaderboard.LeaderboardMenuManager(this);
        this.tagManager = new TagManager(this);
        this.permissionsManager = new PermissionsManager(this);
        this.statsMenuManager = new StatsMenuManager(this);
        this.spectateManager = new me.skepi.skepifb.spectate.SpectateManager();
        this.islandNpcManager = new me.skepi.skepifb.npc.IslandNpcManager(this);
        this.islandNpcManager.registerListeners();
        this.sessionTopManager = new me.skepi.skepifb.session.SessionTopManager(this, configManager);
        this.timerManager = new TimerManager(this, playerManager, arenaManager, inventoryManager, statsManager, hotbarManager);
        this.scoreboardManager = new ScoreboardManager(this, timerManager, playerManager);
        this.chatPlaceholderManager = new ChatPlaceholderManager(this, configManager, scoreboardManager);
        this.periodicMessageManager = new PeriodicMessageManager(this, configManager);
        this.protectionManager = new ArenaProtectionManager(this, playerManager, arenaManager);

        arenaManager.loadArenas();
        // Must run after loadArenas() above, not from StatsMenuManager's own constructor (which
        // runs before arenas exist yet) - materializes a real, editable "action: stats" entry in
        // menus/stats_menu.yml for every arena that doesn't already have one.
        statsMenuManager.syncArenaEntries();
        playerManager.registerListeners();
        timerManager.registerListeners();
        chatPlaceholderManager.registerListeners();
        protectionManager.registerListeners();
        shopManager.registerListeners();
        hotbarManager.registerListeners();
        leaderboardMenuManager.registerListeners();
        periodicMessageManager.start();
        getCommand("fb").setExecutor(new FBCommand(this, arenaManager, playerManager, schematicService, scoreboardManager, inventoryManager, hotbarManager));
        getCommand("stats").setExecutor(new me.skepi.skepifb.statsmenu.StatsCommand(this));
        me.skepi.skepifb.commands.SpectateCommand spectateCommand = new me.skepi.skepifb.commands.SpectateCommand(this);
        getCommand("spectate").setExecutor(spectateCommand);
        getCommand("spec").setExecutor(spectateCommand);
        me.skepi.skepifb.leaderboard.LeaderboardMenuCommand leaderboardMenuCommand = new me.skepi.skepifb.leaderboard.LeaderboardMenuCommand(this);
        getCommand("lb").setExecutor(leaderboardMenuCommand);
        getCommand("lb").setTabCompleter(leaderboardMenuCommand);
        getCommand("leaderboard").setExecutor(leaderboardMenuCommand);
        getCommand("leaderboard").setTabCompleter(leaderboardMenuCommand);
    }

    public me.skepi.skepifb.stats.StatboardManager getStatboardManager() {
        return statboardManager;
    }

    public ReplayPlaceholderManager getReplayPlaceholderManager() {
        return replayPlaceholderManager;
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

    public me.skepi.skepifb.templates.PracticeTemplateManager getPracticeTemplateManager() {
        return practiceTemplateManager;
    }

    public me.skepi.skepifb.templates.SpawnTemplateManager getSpawnTemplateManager() {
        return spawnTemplateManager;
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

    public me.skepi.skepifb.leaderboard.LeaderboardMenuManager getLeaderboardMenuManager() {
        return leaderboardMenuManager;
    }

    public TagManager getTagManager() {
        return tagManager;
    }

    public PermissionsManager getPermissionsManager() {
        return permissionsManager;
    }

    public StatsMenuManager getStatsMenuManager() {
        return statsMenuManager;
    }

    public me.skepi.skepifb.spectate.SpectateManager getSpectateManager() {
        return spectateManager;
    }

    public me.skepi.skepifb.npc.IslandNpcManager getIslandNpcManager() {
        return islandNpcManager;
    }

    public PeriodicMessageManager getPeriodicMessageManager() {
        return periodicMessageManager;
    }

    @Override
    public void onDisable() {
        if (periodicMessageManager != null) {
            try { periodicMessageManager.stop(); } catch (Throwable ignored) {}
        }
        if (statboardManager != null) {
            try { statboardManager.cleanup(); } catch (Throwable ignored) {}
        }
        if (arenaManager != null) {
            arenaManager.saveArenas();
        }
        if (timerManager != null) {
            try { timerManager.cleanupAllReplays(); } catch (Throwable ignored) {}
        }
        if (islandNpcManager != null) {
            try { islandNpcManager.removeAll(); } catch (Throwable ignored) {}
        }
        // Belt-and-suspenders sweep, in case any of the tracked-cleanup calls above missed
        // something (an exception mid-loop, an entity that was never tracked, etc.) - see
        // removeLeftoverManagedEntities()'s javadoc. Running it here too means a clean shutdown
        // leaves nothing behind even in that edge case, on top of the same sweep already running
        // as a safety net on the next startup.
        try { removeLeftoverManagedEntities(); } catch (Throwable ignored) {}
    }

    /**
     * Removes every armor stand hologram (statboard + replay-hologram) and every Citizens island
     * NPC that this plugin owns, anywhere they currently exist in the world/Citizens' own
     * registry - regardless of whether THIS session ever tracked them in memory.
     * <p>
     * Why this exists: StatboardManager/TimerManager/IslandNpcManager only know about entities
     * THEY spawned during the current run (tracked in an in-memory map), so their own cleanup
     * (cleanup()/cleanupAllReplays()/removeAll(), called from onDisable() above) can only remove
     * what this session actually created. Armor stands are real entities saved into the world's
     * chunk data by vanilla Minecraft itself, and Citizens NPCs are saved into Citizens' own
     * storage independently of this plugin - so if a previous shutdown wasn't clean (a crash, a
     * force-kill, a host reboot, plugin load-order hiccups, or simply an exception thrown midway
     * through the old cleanup loop), those entities/NPCs are reloaded right back into the world
     * on the next startup with nothing in memory to ever recognize or remove them again, and they
     * just sit there permanently as nonfunctional leftovers.
     * <p>
     * The fix is to identify OUR entities by a durable marker instead of by session memory:
     * armor stands we spawn carry a "skepifb_hologram" PersistentDataContainer tag (see
     * StatboardManager/TimerManager), and Citizens NPCs we spawn carry a "skepifb-island-npc"
     * persistent metadata flag (see IslandNpcManager) - both survive independently of this
     * plugin's own memory/session state, so this sweep can find and remove them no matter how the
     * previous session ended. Called unconditionally at the very start of onEnable() (before
     * anything new is spawned) and again at the end of onDisable() as a second safety net.
     */
    private void removeLeftoverManagedEntities() {
        org.bukkit.NamespacedKey hologramKey = new org.bukkit.NamespacedKey(this, "skepifb_hologram");
        int removedHolograms = 0;
        for (org.bukkit.World world : Bukkit.getServer().getWorlds()) {
            for (org.bukkit.entity.ArmorStand stand : world.getEntitiesByClass(org.bukkit.entity.ArmorStand.class)) {
                try {
                    if (stand.getPersistentDataContainer().has(hologramKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                        stand.remove();
                        removedHolograms++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (removedHolograms > 0) {
            getLogger().info("Removed " + removedHolograms + " leftover statboard/replay-hologram armor stand(s) "
                    + "from a previous run.");
        }

        if (Bukkit.getPluginManager().getPlugin("Citizens") != null) {
            int removedNpcs = me.skepi.skepifb.npc.IslandNpcManager.removeLeftoverNpcs();
            if (removedNpcs > 0) {
                getLogger().info("Removed " + removedNpcs + " leftover island NPC(s) from a previous run.");
            }
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
