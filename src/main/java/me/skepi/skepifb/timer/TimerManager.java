package me.skepi.skepifb.timer;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaBoundary;
import me.skepi.skepifb.arena.ArenaIsland;
import me.skepi.skepifb.arena.ArenaLocation;
import me.skepi.skepifb.arena.ArenaManager;
import me.skepi.skepifb.inventory.InventoryManager;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.stats.PlayerStatsManager;
import me.skepi.skepifb.hotbar.HotbarAction;
import me.skepi.skepifb.hotbar.HotbarItem;
import me.skepi.skepifb.hotbar.HotbarManager;
import me.skepi.skepifb.replay.ReplayBlockEvent;
import me.skepi.skepifb.replay.ReplayFrame;
import me.skepi.skepifb.replay.ReplayManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.FireworkEffect;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class TimerManager implements Listener {

    private final SkepiFBPlugin plugin;
    private final PlayerManager playerManager;
    private final ArenaManager arenaManager;
    private final InventoryManager inventoryManager;
    private final PlayerStatsManager statsManager;
    private final HotbarManager hotbarManager;
    private final ReplayManager replayManager;
    private final Map<UUID, AttemptSession> sessions = new HashMap<>(); 
    private final Map<UUID, BukkitTask> replayTasks = new HashMap<>(); 
    private final Map<UUID, Object> replayNpcs = new HashMap<>(); 
    // One stacked-hologram line per configured entry in "replay-hologram.lines" (see
    // ConfigManager#getReplayHologramLines), above the replay ghost NPC's head, per viewer. Each
    // line's text is re-rendered (not just repositioned) on every frame move, driven by
    // ReplayPlaceholderManager's 9 placeholders (%xcoordinate%, %ping%, %leftcps%, etc.) against
    // that frame's RECORDED data (not the viewer's live state) - see spawnReplayInfoLines/
    // moveReplayInfoLines/removeReplayInfoLines below.
    private final Map<UUID, List<org.bukkit.entity.ArmorStand>> replayInfoLines = new HashMap<>();
    // The configured line templates (color codes + placeholders, "none" entries already filtered
    // out), in the same top-to-bottom order as replayInfoLines' armor stands, so moveReplayInfoLines
    // knows what raw template to re-run placeholder substitution against for each stand.
    private final Map<UUID, List<String>> replayInfoLineTemplates = new HashMap<>();

    // Height (in blocks above the replay ghost's feet) of the LOWEST/closest-to-the-NPC hologram
    // line - i.e. the last one in the configured top-to-bottom list. Deliberately well clear of
    // where the Citizens username nametag itself renders (~2.3-2.4 blocks up for a player-model
    // NPC) so the bottom info line never overlaps or fights with the nametag above the ghost's head.
    private static final double REPLAY_INFO_LINE_BASE_HEIGHT = 2.75;
    private static final double REPLAY_INFO_LINE_SPACING = 0.27;
    // Config lines beyond this many are ignored - keeps the hologram stack from growing unbounded
    // if an admin pastes in a huge list.
    private static final int REPLAY_INFO_LINE_MAX_CONFIGURED = 10;
    private final java.util.Set<UUID> inReplayMode = new java.util.HashSet<>(); 
    private final java.util.Set<UUID> practiceModePlayers = new java.util.HashSet<>();
    // One saved checkpoint (position/facing + timer state) per player - see setPracticeCheckpoint/
    // teleportToPracticeCheckpoint below. Available any time practice mode is on.
    private final Map<UUID, PracticeCheckpoint> practiceCheckpoints = new HashMap<>();
    private final java.util.Set<UUID> movementStartSuppressed = new java.util.HashSet<>();
    private final Map<UUID, java.util.Set<org.bukkit.Location>> practiceBlocks = new HashMap<>();
    private final Map<UUID, java.util.Set<org.bukkit.Location>> replayFakeBlocks = new HashMap<>();
    private final Map<UUID, java.util.Queue<FakeBlockRestore>> replayRestoreQueues = new HashMap<>();
    private final Map<String, org.bukkit.block.data.BlockData> replayBlockDataCache = new HashMap<>();
    private final Map<UUID, java.util.List<ReplayFrame>> activeReplayFrames = new HashMap<>();
    private final Map<UUID, Integer> activeReplayIndex = new HashMap<>();
    private final Map<UUID, Boolean> replayPaused = new HashMap<>();
    private final Map<UUID, Long> replayControlsReadyAt = new HashMap<>();
    private final Map<UUID, BukkitTask> cleanupTasks = new HashMap<>();
    private final Map<UUID, java.util.List<CleanupAnimationEntry>> cleanupEntries = new HashMap<>();
    // Correlates a real, in-flight FallingBlock entity (spawned by the falling reset animation)
    // back to its CleanupAnimationEntry, so the EntityChangeBlockEvent listener below - which only
    // gets an Entity, not our own bookkeeping - knows which entry to finalize and, critically,
    // that this FallingBlock belongs to us at all and its landing should be intercepted instead of
    // left to place a real block like vanilla sand/gravel would.
    private final Map<UUID, CleanupAnimationEntry> activeFallingBlockEntries = new HashMap<>();
    private final Map<UUID, Object> cleanupNpcs = new HashMap<>();
    private final Map<UUID, UUID> cleanupSessionIds = new HashMap<>();
    private final Map<UUID, BossBar> xpBossBars = new HashMap<>();
    private final Map<UUID, Integer> lastKnownXp = new HashMap<>();
    private final Map<UUID, Integer> lastKnownLevel = new HashMap<>();

    private static final class FakeBlockRestore {
        private final org.bukkit.Location location;
        private final org.bukkit.block.data.BlockData blockData;

        private FakeBlockRestore(org.bukkit.Location location, org.bukkit.block.data.BlockData blockData) {
            this.location = location;
            this.blockData = blockData;
        }
    }

    private static final class CleanupAnimationEntry {
        private final org.bukkit.Location location;
        private final org.bukkit.Material previousMaterial;
        private final org.bukkit.block.data.BlockData previousBlockData;
        private final boolean previousWasAir;
        private Entity ghost;
        private int phase;
        private final int startTick;
        private boolean blockRemoved;
        private boolean animationComplete;
        private double fallStartY;
        private boolean fallingSpawned;

        private CleanupAnimationEntry(org.bukkit.Location location, org.bukkit.Material previousMaterial, org.bukkit.block.data.BlockData previousBlockData, boolean previousWasAir, int startTick) {
            this.location = location;
            this.previousMaterial = previousMaterial;
            this.previousBlockData = previousBlockData;
            this.previousWasAir = previousWasAir;
            this.ghost = null;
            this.phase = 0;
            this.blockRemoved = false;
            this.animationComplete = false;
            this.startTick = startTick;
        }
    }

    public TimerManager(SkepiFBPlugin plugin, PlayerManager playerManager, ArenaManager arenaManager, InventoryManager inventoryManager, PlayerStatsManager statsManager, HotbarManager hotbarManager) {
        this.plugin = plugin;
        this.playerManager = playerManager;
        this.arenaManager = arenaManager;
        this.inventoryManager = inventoryManager;
        this.statsManager = statsManager;
        this.hotbarManager = hotbarManager;
        this.replayManager = plugin.getReplayManager();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallingResetBlockLand(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        // Real FallingBlock entities place a real block into the world the instant they land -
        // that's how vanilla sand/gravel works. The falling reset animation uses real FallingBlock
        // entities (for authentic physics/tumbling instead of a manually-teleported fake), but per
        // spec the block must disappear on landing, never actually place - so every landing from
        // one of our own tracked falling blocks gets cancelled here, and this is also our only
        // reliable signal that a block has actually landed (as opposed to being capped off after
        // falling too far without hitting anything).
        if (!(event.getEntity() instanceof org.bukkit.entity.FallingBlock)) {
            return;
        }
        CleanupAnimationEntry entry = activeFallingBlockEntries.remove(event.getEntity().getUniqueId());
        if (entry == null) {
            return;
        }
        event.setCancelled(true);
        org.bukkit.Location landingLocation = event.getEntity().getLocation();
        org.bukkit.World world = landingLocation.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FALLING_DUST, landingLocation, 14, 0.15, 0.05, 0.15, entry.previousBlockData);
            world.spawnParticle(Particle.CLOUD, landingLocation, 8, 0.12, 0.05, 0.12, 0.02);
            world.playSound(landingLocation, Sound.BLOCK_STONE_BREAK, 0.8f, 1.0f);
        }
        try {
            event.getEntity().remove();
        } catch (Throwable ignored) {
        }
        entry.animationComplete = true;
        entry.ghost = null;
    }

    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkFinishForRunningPlayers, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::processReplayRestores, 1L, 1L);
        // Single, simple, self-healing owner of the rank helmet: every second, for every online
        // arena/test-mode player, make sure they're wearing the correct rank helmet - and nothing
        // else in this codebase ever calls setHelmet(...) at all (confirmed by searching the whole
        // project). Previously there were TWO separate systems fighting over the helmet slot
        // (HotbarManager's per-tick enforcement racing this class's XP-triggered equip), which is
        // what caused it to intermittently disappear/flicker. Rather than trying to perfectly
        // coordinate two systems, this replaces them both with exactly one: a periodic pass that
        // only ever ADDS the correct helmet if it's missing or wrong - it never removes/clears the
        // helmet under any circumstance, so however it might go missing (another plugin, death
        // slipping past the damage-cancel below, a manual /clear, anything), it gets restored
        // within a second on its own instead of relying on catching every possible trigger event.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                try {
                    equipRankHelmet(online);
                } catch (Throwable ignored) {
                }
            }
        }, 20L, 20L);
    }

    public void startReplay(Player player, me.skepi.skepifb.replay.ReplayMetadata metadata) {
        if (player == null || metadata == null) return; 
        UUID playerUuid = player.getUniqueId(); 
        // load frames 
        java.util.List<ReplayFrame> frames = replayManager.loadReplayFrames(metadata.getPlayerUuid(), metadata.getArenaName(), metadata.getReplayIndex()); 
        if (frames.isEmpty()) { 
            player.sendMessage(ChatColor.RED + "Unable to load replay frames."); 
            return; 
        } 
 
        // If another replay active for this player, close it first 
        if (isInReplay(playerUuid)) { 
            closeReplay(player); 
        } 
 
        // Stop and reset any active timer/session for player 
        resetPlayerSession(playerUuid, player.getWorld()); 
        // Enter replay mode state 
        inReplayMode.add(playerUuid); 
 
        // Enable flight and keep survival 
        try { 
            player.setGameMode(org.bukkit.GameMode.SURVIVAL); 
            player.setAllowFlight(true); 
        } catch (Throwable ignored) {} 
 
        // Teleport the viewer to their island spawn so replay starts from the arena view.
        teleportPlayerToSpawn(player);
 
        // Clear only the hotbar/main storage and offhand, then give replay controls. Using the
        // no-arg PlayerInventory#clear() here (as before) also wipes ALL FOUR ARMOR SLOTS - including
        // the rankup helmet - since PlayerInventory#clear() clears every slot the inventory has, not
        // just the visible 9x4 storage grid. Nothing in startReplay (or anywhere else while a replay
        // is active) ever restored the helmet afterward, so the player had no helmet - and therefore
        // no vanilla Water Breathing refresh from the Turtle Helmet at Level 1 - for the ENTIRE
        // replay. This is what caused the helmet to be gone during replays and the Water Breathing
        // effect to only reappear (as a single fresh application that then expired with no further
        // refresh) once the helmet was restored on replay exit. The player is still considered "in
        // FastBuilder" while watching a replay, so their armor should never have been touched here at
        // all - only clear the hotbar/storage/offhand explicitly, leaving armor completely alone.
        for (int i = 0; i < 36; i++) {
            player.getInventory().setItem(i, null);
        }
        player.getInventory().setItemInOffHand(null);
        player.getInventory().setItem(2, createReplayControlItem(plugin.getConfigManager().getReplaySeekBackwardMaterial(), plugin.getConfigManager().getReplaySeekBackwardName()));
        player.getInventory().setItem(3, createReplayControlItem(plugin.getConfigManager().getReplayPreviousTickMaterial(), plugin.getConfigManager().getReplayPreviousTickName()));
        player.getInventory().setItem(4, createReplayToggleItem(playerUuid));
        player.getInventory().setItem(5, createReplayControlItem(plugin.getConfigManager().getReplayNextTickMaterial(), plugin.getConfigManager().getReplayNextTickName()));
        player.getInventory().setItem(6, createReplayControlItem(plugin.getConfigManager().getReplaySeekForwardMaterial(), plugin.getConfigManager().getReplaySeekForwardName()));
        ItemStack close = new ItemStack(org.bukkit.Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta cm = close.getItemMeta(); 
        if (cm != null) { 
            cm.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&cClose Replay")); 
            close.setItemMeta(cm); 
        } 
        player.getInventory().setItem(8, close); 
 
        ReplayFrame start = frames.get(0);
// Spawn Citizens NPC (reflection) at start and make visible only to this player
        UUID replayOwnerUuid = metadata.getPlayerUuid();
        Object npc = spawnCitizensNpcForPlayer(player, metadata.getPlayerName(), start, replayOwnerUuid);
        if (npc != null) {
            replayNpcs.put(playerUuid, npc);
        }
 
        // cancel any existing replay task 
        cancelReplay(playerUuid); 

        // store frames and index for debug and strict per-tick playback
        activeReplayFrames.put(playerUuid, frames);
        activeReplayIndex.put(playerUuid, 0);
        setReplayPaused(playerUuid, true);
        // Ignore any replay control clicks for a short grace period: control items are
        // placed directly into the player's live hotbar, so a right-click already in
        // flight from whatever menu was open a moment ago can otherwise land on
        // whichever control item now occupies that same slot (e.g. seek-forward),
        // instantly jumping the replay dozens of ticks ahead right after it opens.
        replayControlsReadyAt.put(playerUuid, System.currentTimeMillis() + 400L);
        player.getInventory().setItem(4, createReplayToggleItem(playerUuid));

        // Render the initial frame immediately so the first block is visible before any tick controls are used.
        refreshReplayToIndex(playerUuid, 0);

        // Schedule NPC movement per-tick (1 tick = 50ms) - consume exactly one frame per server tick
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !isInReplay(playerUuid)) {
                    cancelReplay(playerUuid);
                    return;
                }
                java.util.List<ReplayFrame> f = activeReplayFrames.get(playerUuid);
                int idx = activeReplayIndex.getOrDefault(playerUuid, 0);
                if (f == null || idx >= f.size()) {
                    if (f != null && !f.isEmpty()) {
                        finishReplay(player);
                    }
                    return;
                }
                if (isReplayPaused(playerUuid)) {
                    updateReplayActionBar(playerUuid);
                    refreshReplayToggleItem(player);
                    return;
                }
                ReplayFrame frame = f.get(idx);
                // advance index by exactly one per tick
                activeReplayIndex.put(playerUuid, idx + 1);
                try {
                    moveNpcToFrame(playerUuid, frame);
                    updateNpcHeldItem(playerUuid, frame);
                    applyReplayBlocks(playerUuid, frame);
                    applyReplayBreaks(playerUuid, frame);
                    applyReplayArmSwing(playerUuid, frame);
                    updateReplayActionBar(playerUuid);
                    refreshReplayToggleItem(player);
                } catch (Throwable ignored) {}
            }
        }, 0L, 1L);

        replayTasks.put(playerUuid, task);
        updateReplayActionBar(playerUuid);
        player.sendMessage(ChatColor.GREEN + "Replay loaded and paused.");
    }

    private void cancelReplay(UUID playerUuid) {
        BukkitTask existing = replayTasks.remove(playerUuid);
        if (existing != null) {
            try { existing.cancel(); } catch (Throwable ignored) {}
        }
        activeReplayFrames.remove(playerUuid);
        activeReplayIndex.remove(playerUuid);
        replayPaused.remove(playerUuid);
        replayControlsReadyAt.remove(playerUuid);
        restoreReplayBlocks(playerUuid);
    }

    private void restoreReplayBlocks(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        java.util.Set<org.bukkit.Location> fakeSet = replayFakeBlocks.remove(playerUuid);
        if (fakeSet == null || fakeSet.isEmpty()) {
            return;
        }
        if (player == null || !player.isOnline()) {
            return;
        }
        for (org.bukkit.Location loc : new java.util.ArrayList<>(fakeSet)) {
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            try {
                org.bukkit.block.Block real = loc.getWorld().getBlockAt(loc);
                player.sendBlockChange(loc, real.getBlockData());
            } catch (Throwable ignored) {}
        }
    }

    private void finishReplay(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }

        setReplayPaused(playerUuid, true);
        refreshReplayToggleItem(player);
        updateReplayActionBar(playerUuid);
    }

    private boolean isReplayFinished(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        return frames != null && !frames.isEmpty() && activeReplayIndex.getOrDefault(playerUuid, 0) >= frames.size();
    }

    private void restartReplay(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int targetIndex = frames.size() > 1 ? 1 : 0;
        refreshReplayToIndex(playerUuid, targetIndex);
        setReplayPaused(playerUuid, false);
        refreshReplayToggleItem(player);
        updateReplayActionBar(playerUuid);
    }

    private void setReplayPaused(UUID playerUuid, boolean paused) {
        if (paused) {
            replayPaused.put(playerUuid, true);
        } else {
            replayPaused.remove(playerUuid);
        }
    }

    private boolean isReplayPaused(UUID playerUuid) {
        return replayPaused.getOrDefault(playerUuid, false);
    }

    public boolean handleReplayControlClick(Player player, ItemStack item) {
        if (player == null || item == null) {
            return false;
        }
        UUID playerUuid = player.getUniqueId();
        Long readyAt = replayControlsReadyAt.get(playerUuid);
        if (readyAt != null && System.currentTimeMillis() < readyAt) {
            // Within the just-opened grace period: swallow the interaction so it can't
            // be misdelivered onto whichever control item now sits in this hotbar slot,
            // but don't act on it.
            return true;
        }
        String name = null;
        if (item.hasItemMeta()) {
            ItemStack metaItem = item.clone();
            if (metaItem.hasItemMeta()) {
                org.bukkit.inventory.meta.ItemMeta meta = metaItem.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    name = meta.getDisplayName();
                }
            }
        }
        Material type = item.getType();
        Material previousMaterial = plugin.getConfigManager().getReplayPreviousTickMaterial();
        Material nextMaterial = plugin.getConfigManager().getReplayNextTickMaterial();
        Material seekBackwardMaterial = plugin.getConfigManager().getReplaySeekBackwardMaterial();
        Material seekForwardMaterial = plugin.getConfigManager().getReplaySeekForwardMaterial();
        String previousName = ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getReplayPreviousTickName());
        String nextName = ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getReplayNextTickName());
        String seekBackwardName = ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getReplaySeekBackwardName());
        String seekForwardName = ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getReplaySeekForwardName());
        String toggleRunningName = ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getReplayToggleRunningName());
        String togglePausedName = ChatColor.translateAlternateColorCodes('&', plugin.getConfigManager().getReplayTogglePausedName());
        String toggleRestartName = ChatColor.translateAlternateColorCodes('&', "&6Restart");

        if (type == seekBackwardMaterial && seekBackwardName.equals(name)) {
            handleReplaySeekBackward(player);
            return true;
        }
        if (type == previousMaterial && previousName.equals(name)) {
            handleReplayPreviousTick(player);
            return true;
        }
        if (type == nextMaterial && nextName.equals(name)) {
            handleReplayNextTick(player);
            return true;
        }
        if (type == seekForwardMaterial && seekForwardName.equals(name)) {
            handleReplaySeekForward(player);
            return true;
        }
        if ((type == Material.RED_DYE && toggleRunningName.equals(name))
                || (type == Material.LIME_DYE && togglePausedName.equals(name))
                || (type == Material.ORANGE_DYE && toggleRestartName.equals(name))) {
            handleReplayToggle(player);
            return true;
        }
        return false;
    }

    private void handleReplayPreviousTick(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int currentIdx = Math.max(0, activeReplayIndex.getOrDefault(playerUuid, 0) - 1);
        int targetIndex = Math.max(0, currentIdx - 1);
        refreshReplayToIndex(playerUuid, targetIndex);
        updateReplayActionBar(playerUuid);
        refreshReplayToggleItem(player);
    }

    private void handleReplayNextTick(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int currentIdx = Math.max(0, activeReplayIndex.getOrDefault(playerUuid, 0) - 1);
        int targetIndex = Math.min(frames.size() - 1, currentIdx + 1);
        refreshReplayToIndex(playerUuid, targetIndex);
        updateReplayActionBar(playerUuid);
        refreshReplayToggleItem(player);
    }

    private void handleReplaySeekBackward(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int currentIdx = Math.max(0, activeReplayIndex.getOrDefault(playerUuid, 0) - 1);
        int targetIndex = Math.max(0, currentIdx - 60);
        refreshReplayToIndex(playerUuid, targetIndex);
        updateReplayActionBar(playerUuid);
        refreshReplayToggleItem(player);
    }

    private void handleReplaySeekForward(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int currentIdx = Math.max(0, activeReplayIndex.getOrDefault(playerUuid, 0) - 1);
        int targetIndex = Math.min(frames.size() - 1, currentIdx + 60);
        refreshReplayToIndex(playerUuid, targetIndex);
        updateReplayActionBar(playerUuid);
        refreshReplayToggleItem(player);
    }

    private void handleReplayToggle(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }
        if (isReplayFinished(playerUuid)) {
            restartReplay(player);
            return;
        }
        boolean paused = !isReplayPaused(playerUuid);
        setReplayPaused(playerUuid, paused);
        refreshReplayToggleItem(player);
        updateReplayActionBar(playerUuid);
        player.sendMessage(paused ? ChatColor.YELLOW + "Replay paused." : ChatColor.GREEN + "Replay resumed.");
    }

    private void refreshReplayToIndex(UUID playerUuid, int targetIndex) {
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        if (frames == null || frames.isEmpty() || targetIndex < 0 || targetIndex >= frames.size()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        java.util.Set<org.bukkit.Location> fakeSet = replayFakeBlocks.remove(playerUuid);
        if (fakeSet != null) {
            for (org.bukkit.Location loc : fakeSet) {
                try {
                    org.bukkit.block.Block real = loc.getWorld().getBlockAt(loc);
                    player.sendBlockChange(loc, real.getBlockData());
                } catch (Throwable ignored) {}
            }
        }
        replayFakeBlocks.put(playerUuid, new java.util.HashSet<>());

        for (int i = 0; i <= targetIndex; i++) {
            ReplayFrame frame = frames.get(i);
            applyReplayBlocks(playerUuid, frame);
            applyReplayBreaks(playerUuid, frame);
        }

        activeReplayIndex.put(playerUuid, targetIndex + 1);
        moveNpcToFrame(playerUuid, frames.get(targetIndex));
        updateNpcHeldItem(playerUuid, frames.get(targetIndex));
        updateReplayActionBar(playerUuid);
        refreshReplayToggleItem(player);
    }

    private ItemStack createReplayControlItem(Material material, String displayName) {
        ItemStack item = new ItemStack(material == null ? Material.BLAZE_ROD : material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName == null ? "" : displayName));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void refreshReplayToggleItem(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!isInReplay(playerUuid)) {
            return;
        }
        player.getInventory().setItem(4, createReplayToggleItem(playerUuid));
    }

    private ItemStack createReplayToggleItem(UUID playerUuid) {
        boolean paused = isReplayPaused(playerUuid);
        boolean finished = isReplayFinished(playerUuid);
        Material material;
        String displayName;
        List<String> lore = new ArrayList<>();
        if (finished) {
            material = Material.ORANGE_DYE;
            displayName = "&6Restart";
            lore.add(ChatColor.GRAY + "Replay finished");
            lore.add(ChatColor.YELLOW + "Click to restart from tick 1");
        } else if (paused) {
            material = plugin.getConfigManager().getReplayTogglePausedMaterial();
            displayName = plugin.getConfigManager().getReplayTogglePausedName();
            lore.add(ChatColor.GRAY + "Replay is paused");
            lore.add(ChatColor.YELLOW + "Click to start playback");
        } else {
            material = plugin.getConfigManager().getReplayToggleRunningMaterial();
            displayName = plugin.getConfigManager().getReplayToggleRunningName();
            lore.add(ChatColor.GRAY + "Replay is playing");
            lore.add(ChatColor.YELLOW + "Click to pause playback");
        }
        ItemStack item = new ItemStack(material == null ? Material.LIME_DYE : material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName == null ? "" : displayName));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void updateReplayActionBar(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline() || !isInReplay(playerUuid)) {
            return;
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(playerUuid);
        if (frames == null || frames.isEmpty()) {
            player.sendActionBar("0.000");
            return;
        }
        int index = activeReplayIndex.getOrDefault(playerUuid, 0);
        int displayTick = Math.max(0, Math.min(index, frames.size()));
        int maxTick = frames.size();
        double seconds = displayTick * 0.05;
        player.sendActionBar(formatReplayActionbar(playerUuid, displayTick, maxTick, seconds));
    }

    private String formatReplayActionbar(UUID playerUuid, int tick, int maxTick, double seconds) {
        String format = plugin.getConfigManager().getReplayActionbarFormat();
        if (format == null || format.isBlank()) {
            return String.format(Locale.ROOT, "%.3f", seconds);
        }
        String result = ChatColor.translateAlternateColorCodes('&', format);
        result = result.replace("%tick%", String.valueOf(tick));
        result = result.replace("%max_tick%", String.valueOf(maxTick));
        result = result.replace("%seconds%", String.format(Locale.ROOT, "%.3f", seconds));
        result = result.replace("%time%", String.format(Locale.ROOT, "%.3f", seconds));
        return result;
    }

    private void teleportPlayerToSpawn(Player player) {
        if (player == null) {
            return;
        }
        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            return;
        }

        Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) {
            return;
        }

        ArenaIsland island = arena.findIslandByPlayer(player.getUniqueId()).orElse(null);
        if (island == null) {
            return;
        }

        // Face the arena's configured spawn direction (island.getSpawnLocation().getYaw()/getPitch(),
        // settable per-arena via /fb setfacing) instead of whatever direction the player happened
        // to already be facing at the moment of reset/replay-entry - using the player's own current
        // yaw/pitch here meant this teleport never actually turned the player to face the intended
        // direction at all.
        org.bukkit.Location destination = new Location(
                player.getWorld(),
                island.getSpawnLocation().getX(),
                island.getSpawnLocation().getY(),
                island.getSpawnLocation().getZ(),
                island.getSpawnLocation().getYaw(),
                island.getSpawnLocation().getPitch()
        );
        try { player.teleport(destination); } catch (Throwable ignored) {}
    }

    public boolean isInReplay(UUID playerUuid) { 
        return inReplayMode.contains(playerUuid); 
    } 
 
    public void closeReplay(Player player) { 
        if (player == null) return; 
        UUID playerUuid = player.getUniqueId(); 
 
        // stop task 
        cancelReplay(playerUuid); 
 
        // remove npc 
        Object npc = replayNpcs.remove(playerUuid); 
        if (npc != null) { 
            try { 
                // call destroy 
                npc.getClass().getMethod("destroy").invoke(npc); 
            } catch (Throwable ignored) {} 
        } 
        removeReplayInfoLines(playerUuid);
 
        // disable flight 
        try { player.setAllowFlight(false); } catch (Throwable ignored) {} 
 
        // restore inventory and hotbar 
        inventoryManager.giveArenaBlock(player); 
        hotbarManager.giveHotbarToPlayer(player); 
        updateXpBossBar(player); 
 
        // teleport back to island spawn 
        teleportPlayerBackToSpawn(player); 
 
        // ensure timer stopped and reset 
        resetPlayerSession(playerUuid, player.getWorld()); 
 
        // leave replay mode 
        inReplayMode.remove(playerUuid); 
        restoreReplayBlocks(playerUuid);
        player.sendMessage(ChatColor.GREEN + "Replay closed.");
    }

    private void processReplayRestores() {
        if (replayRestoreQueues.isEmpty()) {
            return;
        }

        int maxPerTick = plugin.getConfigManager().getReplayRestorePerTick();
        for (UUID playerUuid : new ArrayList<>(replayRestoreQueues.keySet())) {
            java.util.Queue<FakeBlockRestore> queue = replayRestoreQueues.get(playerUuid);
            if (queue == null || queue.isEmpty()) {
                replayRestoreQueues.remove(playerUuid);
                continue;
            }

            Player player = Bukkit.getPlayer(playerUuid);
            int restored = 0;
            while (restored < maxPerTick && !queue.isEmpty()) {
                FakeBlockRestore restore = queue.poll();
                if (restore == null) {
                    continue;
                }
                try {
                    player = player == null ? Bukkit.getPlayer(playerUuid) : player;
                    if (player != null && player.isOnline()) {
                        player.sendBlockChange(restore.location, restore.blockData);
                    }
                } catch (Throwable ignored) {}
                restored++;
            }

            if (queue.isEmpty()) {
                replayRestoreQueues.remove(playerUuid);
            }
        }
    }

    private org.bukkit.block.data.BlockData getCachedBlockData(String materialName) {
        if (materialName == null) {
            return org.bukkit.Material.STONE.createBlockData();
        }
        return replayBlockDataCache.computeIfAbsent(materialName, name -> {
            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(name);
            if (mat == null) {
                mat = org.bukkit.Material.STONE;
            }
            try {
                return mat.createBlockData();
            } catch (Throwable ignored) {
                return org.bukkit.Material.STONE.createBlockData();
            }
        });
    }

    private String getReplayNpcDisplayName(UUID ownerUuid, String ownerName) {
        if (ownerName == null || ownerName.isEmpty()) {
            ownerName = "";
        }
        try {
            String lpFormatted = getLuckPermsFormattedName(ownerUuid, ownerName);
            if (lpFormatted != null && !lpFormatted.isEmpty()) {
                return lpFormatted;
            }
        } catch (Throwable ignored) {}
        return ownerName;
    }

    private String getEquippedBlockMaterialName(Player player) {
        if (player == null || plugin == null) {
            return null;
        }
        try {
            if (plugin.getShopManager() != null) {
                org.bukkit.Material material = plugin.getShopManager().getEquippedBlockMaterial(player.getUniqueId());
                if (material != null && material != org.bukkit.Material.AIR) {
                    return material.name();
                }
            }
        } catch (Throwable ignored) {
        }
        // Ground-truth fallback: if the shop-cosmetic lookup above couldn't resolve a material for
        // any reason (e.g. a customized shop.yml with no default item configured for block_shop),
        // fall back to whatever block the player is actually holding in their hand right now. This
        // is what makes the replay NPC hold a real block in every case instead of nothing, since it
        // no longer depends entirely on the cosmetic-shop lookup succeeding.
        try {
            org.bukkit.inventory.ItemStack mainHand = player.getInventory().getItemInMainHand();
            if (mainHand != null && mainHand.getType() != null && mainHand.getType() != org.bukkit.Material.AIR
                    && mainHand.getType().isBlock()) {
                return mainHand.getType().name();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String getLuckPermsFormattedName(UUID playerUuid, String fallbackName) {
        try {
            Class<?> luckClass = Class.forName("net.luckperms.api.LuckPerms");
            Object luckApi = luckClass.getMethod("getApi").invoke(null);
            Object userManager = luckApi.getClass().getMethod("getUserManager").invoke(luckApi);
            Object user = null;
            try {
                user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, playerUuid);
            } catch (NoSuchMethodException ignored) {}
            if (user == null) {
                try {
                    Object future = userManager.getClass().getMethod("loadUser", UUID.class).invoke(userManager, playerUuid);
                    if (future != null) {
                        Object result = future.getClass().getMethod("join").invoke(future);
                        user = result;
                    }
                } catch (Throwable ignored) {}
            }
            if (user == null) {
                return fallbackName;
            }
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
            String prefix = (String) metaData.getClass().getMethod("getPrefix").invoke(metaData);
            String suffix = (String) metaData.getClass().getMethod("getSuffix").invoke(metaData);
            if (prefix == null) prefix = "";
            if (suffix == null) suffix = "";
            return prefix + fallbackName + suffix;
        } catch (Throwable ignored) {
            return fallbackName;
        }
    }

    private void moveNpcToFrame(UUID playerUuid, ReplayFrame frame) {
        if (frame == null) {
            return;
        }
        Object npc = replayNpcs.get(playerUuid);
        if (npc == null) {
            return;
        }
        try {
            Object entity = null;
            try {
                entity = npc.getClass().getMethod("getEntity").invoke(npc);
            } catch (NoSuchMethodException ignored) {
                // fallback: ignore
            }
            if (entity instanceof org.bukkit.entity.Entity) {
                org.bukkit.entity.Entity ent = (org.bukkit.entity.Entity) entity;
                org.bukkit.Location loc;
                if (frame.isCoordinatesRelative()) {
                    Player viewer = Bukkit.getPlayer(playerUuid);
                    if (viewer != null && viewer.isOnline()) {
                        try {
                            String arenaName = playerManager.getPlayerArena(viewer.getUniqueId());
                            if (arenaName != null) {
                                Arena arena = arenaManager.getArena(arenaName);
                                if (arena != null) {
                                    ArenaIsland island = arena.findIslandByPlayer(viewer.getUniqueId()).orElse(null);
                                    if (island != null) {
                                        // Floor X/Z the same way applyReplayBlocks/applyReplayBreaks already do (and the
                                        // way ReplayManager now WRITES relative coordinates) - island spawn X/Z carry a
                                        // +0.5 block-center offset that Y never had, so reconstructing the NPC's absolute
                                        // position from the raw (un-floored) spawn X/Z here overshot by 0.5 on X/Z,
                                        // visibly placing the replay NPC (and the hologram riding above it, which just
                                        // follows this same location) half a block off from where it was actually
                                        // recorded/where the placed blocks appear.
                                        double baseX = Math.floor(island.getSpawnLocation().getX());
                                        double baseY = island.getSpawnLocation().getY();
                                        double baseZ = Math.floor(island.getSpawnLocation().getZ());
                                        loc = new org.bukkit.Location(ent.getWorld(), baseX + frame.getX(), baseY + frame.getY(), baseZ + frame.getZ(), frame.getYaw(), frame.getPitch());
                                    } else {
                                        loc = new org.bukkit.Location(ent.getWorld(), frame.getX(), frame.getY(), frame.getZ(), frame.getYaw(), frame.getPitch());
                                    }
                                } else {
                                    loc = new org.bukkit.Location(ent.getWorld(), frame.getX(), frame.getY(), frame.getZ(), frame.getYaw(), frame.getPitch());
                                }
                            } else {
                                loc = new org.bukkit.Location(ent.getWorld(), frame.getX(), frame.getY(), frame.getZ(), frame.getYaw(), frame.getPitch());
                            }
                        } catch (Throwable ignored) {
                            loc = new org.bukkit.Location(ent.getWorld(), frame.getX(), frame.getY(), frame.getZ(), frame.getYaw(), frame.getPitch());
                        }
                    } else {
                        loc = new org.bukkit.Location(ent.getWorld(), frame.getX(), frame.getY(), frame.getZ(), frame.getYaw(), frame.getPitch());
                    }
                } else {
                    loc = new org.bukkit.Location(ent.getWorld(), frame.getX(), frame.getY(), frame.getZ(), frame.getYaw(), frame.getPitch());
                }
                try {
                    try {
                        Object navigator = npc.getClass().getMethod("getNavigator").invoke(npc);
                        if (navigator != null) {
                            try { navigator.getClass().getMethod("cancelNavigation").invoke(navigator); } catch (Throwable ignored) {}
                            try { navigator.getClass().getMethod("setPaused", boolean.class).invoke(navigator, true); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    ent.teleport(loc);
                    moveReplayInfoLines(playerUuid, loc, frame);
                    try { ent.getClass().getMethod("setGravity", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setAI", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setCustomName", String.class).invoke(ent, ""); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setCustomNameVisible", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setSneaking", boolean.class).invoke(ent, frame.isSneaking()); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setSprinting", boolean.class).invoke(ent, frame.isSprinting()); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyReplayBlocks(UUID playerUuid, ReplayFrame frame) {
        try {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            java.util.Set<org.bukkit.Location> fakeSet = replayFakeBlocks.computeIfAbsent(playerUuid, k -> new java.util.HashSet<>());
            for (ReplayBlockEvent placement : frame.getPlacements()) {
                org.bukkit.World world = Bukkit.getWorld(placement.getWorld());
                if (world == null) {
                    continue;
                }
                org.bukkit.Location bLoc;
                if (frame.isCoordinatesRelative()) {
                    Player viewer = Bukkit.getPlayer(playerUuid);
                    if (viewer != null && viewer.isOnline()) {
                        try {
                            String arenaName = playerManager.getPlayerArena(viewer.getUniqueId());
                            if (arenaName != null) {
                                Arena arena = arenaManager.getArena(arenaName);
                                if (arena != null) {
                                    ArenaIsland island = arena.findIslandByPlayer(viewer.getUniqueId()).orElse(null);
                                    if (island != null) {
                                        int baseX = (int) Math.floor(island.getSpawnLocation().getX());
                                        int baseY = (int) Math.floor(island.getSpawnLocation().getY());
                                        int baseZ = (int) Math.floor(island.getSpawnLocation().getZ());
                                        bLoc = new org.bukkit.Location(world, baseX + placement.getX(), baseY + placement.getY(), baseZ + placement.getZ());
                                    } else {
                                        bLoc = new org.bukkit.Location(world, placement.getX(), placement.getY(), placement.getZ());
                                    }
                                } else {
                                    bLoc = new org.bukkit.Location(world, placement.getX(), placement.getY(), placement.getZ());
                                }
                            } else {
                                bLoc = new org.bukkit.Location(world, placement.getX(), placement.getY(), placement.getZ());
                            }
                        } catch (Throwable ignored) {
                            bLoc = new org.bukkit.Location(world, placement.getX(), placement.getY(), placement.getZ());
                        }
                    } else {
                        bLoc = new org.bukkit.Location(world, placement.getX(), placement.getY(), placement.getZ());
                    }
                } else {
                    bLoc = new org.bukkit.Location(world, placement.getX(), placement.getY(), placement.getZ());
                }
                org.bukkit.block.data.BlockData blockData = getCachedBlockData(placement.getMaterial());
                player.sendBlockChange(bLoc, blockData);
                fakeSet.add(bLoc);
            }
        } catch (Throwable ignored) {}
    }

    private void applyReplayBreaks(UUID playerUuid, ReplayFrame frame) {
        try {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            java.util.Set<org.bukkit.Location> fakeSet = replayFakeBlocks.computeIfAbsent(playerUuid, k -> new java.util.HashSet<>());
            for (ReplayBlockEvent breakEvent : frame.getBreaks()) {
                org.bukkit.World world = Bukkit.getWorld(breakEvent.getWorld());
                if (world == null) {
                    continue;
                }
                org.bukkit.Location bLoc;
                if (frame.isCoordinatesRelative()) {
                    Player viewer = Bukkit.getPlayer(playerUuid);
                    if (viewer != null && viewer.isOnline()) {
                        try {
                            String arenaName = playerManager.getPlayerArena(viewer.getUniqueId());
                            if (arenaName != null) {
                                Arena arena = arenaManager.getArena(arenaName);
                                if (arena != null) {
                                    ArenaIsland island = arena.findIslandByPlayer(viewer.getUniqueId()).orElse(null);
                                    if (island != null) {
                                        int baseX = (int) Math.floor(island.getSpawnLocation().getX());
                                        int baseY = (int) Math.floor(island.getSpawnLocation().getY());
                                        int baseZ = (int) Math.floor(island.getSpawnLocation().getZ());
                                        bLoc = new org.bukkit.Location(world, baseX + breakEvent.getX(), baseY + breakEvent.getY(), baseZ + breakEvent.getZ());
                                    } else {
                                        bLoc = new org.bukkit.Location(world, breakEvent.getX(), breakEvent.getY(), breakEvent.getZ());
                                    }
                                } else {
                                    bLoc = new org.bukkit.Location(world, breakEvent.getX(), breakEvent.getY(), breakEvent.getZ());
                                }
                            } else {
                                bLoc = new org.bukkit.Location(world, breakEvent.getX(), breakEvent.getY(), breakEvent.getZ());
                            }
                        } catch (Throwable ignored) {
                            bLoc = new org.bukkit.Location(world, breakEvent.getX(), breakEvent.getY(), breakEvent.getZ());
                        }
                    } else {
                        bLoc = new org.bukkit.Location(world, breakEvent.getX(), breakEvent.getY(), breakEvent.getZ());
                    }
                } else {
                    bLoc = new org.bukkit.Location(world, breakEvent.getX(), breakEvent.getY(), breakEvent.getZ());
                }
                player.sendBlockChange(bLoc, org.bukkit.Material.AIR.createBlockData());
                fakeSet.remove(bLoc);
            }
        } catch (Throwable ignored) {}
    }

    private void applyReplayArmSwing(UUID playerUuid, ReplayFrame frame) {
        if (frame.getArmSwings().isEmpty()) {
            return;
        }
        Object npc = replayNpcs.get(playerUuid);
        if (npc == null) {
            return;
        }
        try {
            Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
            if (entity instanceof org.bukkit.entity.Entity) {
                try { entity.getClass().getMethod("swingMainHand").invoke(entity); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void applyReplayNpcHeldItem(Object npc, String heldMaterialName) {
        if (npc == null || heldMaterialName == null || heldMaterialName.isBlank()) {
            return;
        }
        try {
            Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
            if (!(entity instanceof org.bukkit.entity.LivingEntity)) {
                return;
            }
            org.bukkit.entity.LivingEntity living = (org.bukkit.entity.LivingEntity) entity;
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(heldMaterialName);
            if (material == null || material == org.bukkit.Material.AIR) {
                return;
            }
            org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material, 1);
            org.bukkit.inventory.EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                equipment.setItemInMainHand(item);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateNpcHeldItem(UUID playerUuid, ReplayFrame frame) {
        if (frame == null) {
            return;
        }
        Object npc = replayNpcs.get(playerUuid);
        if (npc == null) {
            return;
        }
        applyReplayNpcHeldItem(npc, frame.getHeldMaterial());
    }

    /**
     * Reads "replay-hologram.lines" from config.yml (up to 10 entries, top-to-bottom as authored
     * there), drops any entry that's blank or literally "none" (case-insensitive - the documented
     * way to leave a slot empty), and caps the result at REPLAY_INFO_LINE_MAX_CONFIGURED.
     */
    private java.util.List<String> getActiveReplayHologramLines() {
        java.util.List<String> configured = plugin.getConfigManager().getReplayHologramLines();
        java.util.List<String> active = new ArrayList<>();
        for (String raw : configured) {
            if (active.size() >= REPLAY_INFO_LINE_MAX_CONFIGURED) {
                break;
            }
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none")) {
                continue;
            }
            active.add(trimmed);
        }
        return active;
    }

    /**
     * Height above the replay ghost's feet for the line at "index" (0 = the first/topmost entry in
     * the configured list) out of "total" active lines. The LAST configured entry always lands at
     * REPLAY_INFO_LINE_BASE_HEIGHT (closest to, but deliberately clear of, the npc's own username
     * nametag) and earlier entries stack upward from there - so "x coordinate at the top" is simply
     * a matter of listing it first in config.yml, and "jump ticks at the bottom" a matter of
     * listing it last, with no special-casing needed here.
     */
    private static double replayInfoLineHeight(int index, int total) {
        return REPLAY_INFO_LINE_BASE_HEIGHT + (total - 1 - index) * REPLAY_INFO_LINE_SPACING;
    }

    /**
     * Spawns the stack of configurable hologram lines above the replay ghost's head, visible only
     * to "viewer" - same private-ghost visibility rule the NPC itself uses. Every line's text is
     * rendered by running it through ReplayPlaceholderManager against "startFrame" - the RECORDED
     * data for the first frame of this replay (%xcoordinate%, %ping%, %leftcps%, etc. - see that
     * class), so the lines show this replay's actual recorded values, not the viewer's own live
     * state.
     */
    private void spawnReplayInfoLines(Player viewer, Location baseLoc, ReplayFrame startFrame) {
        if (viewer == null || baseLoc == null || baseLoc.getWorld() == null) {
            return;
        }
        java.util.List<String> templates = getActiveReplayHologramLines();
        if (templates.isEmpty()) {
            return;
        }
        java.util.List<org.bukkit.entity.ArmorStand> lines = new ArrayList<>();
        int total = templates.size();
        for (int i = 0; i < total; i++) {
            String template = templates.get(i);
            try {
                double height = replayInfoLineHeight(i, total);
                Location lineLoc = baseLoc.clone().add(0, height, 0);
                String rendered = ChatColor.translateAlternateColorCodes('&', plugin.getReplayPlaceholderManager().applyRecorded(startFrame, template));
                org.bukkit.entity.ArmorStand stand = baseLoc.getWorld().spawn(lineLoc, org.bukkit.entity.ArmorStand.class, as -> {
                    as.setVisible(false);
                    as.setMarker(true);
                    as.setGravity(false);
                    as.setInvulnerable(true);
                    as.setSmall(true);
                    as.setBasePlate(false);
                    as.setCollidable(false);
                    as.setCustomNameVisible(true);
                    as.setCustomName(rendered);
                    as.getPersistentDataContainer().set(
                            new org.bukkit.NamespacedKey(plugin, "skepifb_hologram"),
                            org.bukkit.persistence.PersistentDataType.STRING,
                            "replay_hologram");
                });
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getUniqueId().equals(viewer.getUniqueId())) {
                        try { p.hideEntity(plugin, stand); } catch (Throwable ignored) {}
                    }
                }
                lines.add(stand);
            } catch (Throwable ignored) {
                // A single failed line shouldn't take the rest of the stack (or the replay ghost
                // NPC itself) down with it.
            }
        }
        if (!lines.isEmpty()) {
            replayInfoLines.put(viewer.getUniqueId(), lines);
            replayInfoLineTemplates.put(viewer.getUniqueId(), templates);
        }
    }

    /**
     * Keeps the info-line hologram stack riding along above the replay ghost as it's teleported
     * frame-to-frame in moveNpcToFrame(), AND re-renders each line's text against "frame" - the
     * RECORDED data for whichever replay frame is currently being played back - so placeholders
     * like %xcoordinate%/%yaw%/%ping%/%leftcps%/%rightcps% update in sync with the replay's own
     * motion, showing exactly what was recorded at that instant rather than the viewer's own live
     * state. No-op if this viewer doesn't have a line stack (e.g. Citizens wasn't present when the
     * replay started, or every configured line was "none").
     */
    private void moveReplayInfoLines(UUID playerUuid, Location baseLoc, ReplayFrame frame) {
        java.util.List<org.bukkit.entity.ArmorStand> lines = replayInfoLines.get(playerUuid);
        java.util.List<String> templates = replayInfoLineTemplates.get(playerUuid);
        if (lines == null || lines.isEmpty() || templates == null || baseLoc == null) {
            return;
        }
        int total = lines.size();
        for (int i = 0; i < total; i++) {
            org.bukkit.entity.ArmorStand stand = lines.get(i);
            if (stand == null || stand.isDead()) {
                continue;
            }
            try {
                stand.teleport(baseLoc.clone().add(0, replayInfoLineHeight(i, total), 0));
            } catch (Throwable ignored) {
            }
            if (frame != null && i < templates.size()) {
                try {
                    String rendered = ChatColor.translateAlternateColorCodes('&', plugin.getReplayPlaceholderManager().applyRecorded(frame, templates.get(i)));
                    stand.setCustomName(rendered);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Removes and destroys this viewer's info-line hologram stack. Called from every place the
     * replay ghost NPC itself is torn down (closeReplay, cleanupAllReplays, clearSession), so none
     * of these armor stands are ever left behind in the world.
     */
    private void removeReplayInfoLines(UUID playerUuid) {
        replayInfoLineTemplates.remove(playerUuid);
        java.util.List<org.bukkit.entity.ArmorStand> lines = replayInfoLines.remove(playerUuid);
        if (lines == null) {
            return;
        }
        for (org.bukkit.entity.ArmorStand stand : lines) {
            if (stand != null && !stand.isDead()) {
                try { stand.remove(); } catch (Throwable ignored) {}
            }
        }
    }

    private Object spawnCitizensNpcForPlayer(Player viewer, String ownerName, ReplayFrame startFrame, UUID ownerUuid) {
        try {
            if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
                return null;
            }
            Class<?> apiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = apiClass.getMethod("getNPCRegistry").invoke(null);
            Class<?> registryClass = registry.getClass();
            Class<?> entityTypeClass = Class.forName("org.bukkit.entity.EntityType");
            Object playerType = Enum.valueOf((Class) entityTypeClass, "PLAYER");
            Object npc = registryClass.getMethod("createNPC", Class.forName("org.bukkit.entity.EntityType"), String.class).invoke(registry, playerType, ownerName);

            // set skin trait if available, prefer UUID-based if supported
            try {
                Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
                Object trait = npc.getClass().getMethod("getTrait", Class.class).invoke(npc, skinTraitClass);
                if (trait != null) {
                    try {
                        // try UUID-based setter first
                        try { trait.getClass().getMethod("setSkinUuid", java.util.UUID.class).invoke(trait, ownerUuid); } catch (NoSuchMethodException ignored) {}
                        try { trait.getClass().getMethod("setSkinUUID", java.util.UUID.class).invoke(trait, ownerUuid); } catch (NoSuchMethodException ignored) {}
                        // fallback to name-based setter
                        try { trait.getClass().getMethod("setSkinName", String.class).invoke(trait, ownerName); } catch (NoSuchMethodException ignored) {}
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // spawn at start location
            org.bukkit.Location startLoc;
            if (startFrame != null && startFrame.isCoordinatesRelative()) {
                try {
                    String arenaName = playerManager.getPlayerArena(viewer.getUniqueId());
                    if (arenaName != null) {
                        Arena arena = arenaManager.getArena(arenaName);
                        if (arena != null) {
                            ArenaIsland island = arena.findIslandByPlayer(viewer.getUniqueId()).orElse(null);
                            if (island != null) {
                                // Same floor(X/Z) fix as moveNpcToFrame below - keeps the NPC's initial spawn
                                // position (and the hologram spawned right above it) consistent with every
                                // frame it's moved to afterward, and with where the recorded blocks appear.
                                double baseX = Math.floor(island.getSpawnLocation().getX());
                                double baseY = island.getSpawnLocation().getY();
                                double baseZ = Math.floor(island.getSpawnLocation().getZ());
                                startLoc = new org.bukkit.Location(viewer.getWorld(), baseX + startFrame.getX(), baseY + startFrame.getY(), baseZ + startFrame.getZ(), startFrame.getYaw(), startFrame.getPitch());
                            } else {
                                startLoc = new org.bukkit.Location(viewer.getWorld(), startFrame.getX(), startFrame.getY(), startFrame.getZ(), startFrame.getYaw(), startFrame.getPitch());
                            }
                        } else {
                            startLoc = new org.bukkit.Location(viewer.getWorld(), startFrame.getX(), startFrame.getY(), startFrame.getZ(), startFrame.getYaw(), startFrame.getPitch());
                        }
                    } else {
                        startLoc = new org.bukkit.Location(viewer.getWorld(), startFrame.getX(), startFrame.getY(), startFrame.getZ(), startFrame.getYaw(), startFrame.getPitch());
                    }
                } catch (Throwable ignored) {
                    startLoc = new org.bukkit.Location(viewer.getWorld(), startFrame.getX(), startFrame.getY(), startFrame.getZ(), startFrame.getYaw(), startFrame.getPitch());
                }
            } else {
                startLoc = new org.bukkit.Location(viewer.getWorld(), startFrame.getX(), startFrame.getY(), startFrame.getZ(), startFrame.getYaw(), startFrame.getPitch());
            }
            try {
                npc.getClass().getMethod("spawn", org.bukkit.Location.class).invoke(npc, startLoc);
            } catch (NoSuchMethodException ex) {
                // older API may use different method
            }

            applyReplayNpcHeldItem(npc, startFrame.getHeldMaterial());

            // hide npc from other players so only viewer sees it, and ensure name never renders
            try {
                Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
                if (entity instanceof org.bukkit.entity.Entity) {
                    org.bukkit.entity.Entity ent = (org.bukkit.entity.Entity) entity;
                    // disable Citizens movement and collisions if supported
                    try {
                        Object navigator = npc.getClass().getMethod("getNavigator").invoke(npc);
                        if (navigator != null) {
                            try { navigator.getClass().getMethod("cancelNavigation").invoke(navigator); } catch (Throwable ignored) {}
                            try { navigator.getClass().getMethod("setPaused", boolean.class).invoke(navigator, true); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setGravity", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setAI", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setCollidable", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    try { ent.getClass().getMethod("setCustomNameVisible", boolean.class).invoke(ent, false); } catch (Throwable ignored) {}
                    String displayName = getReplayNpcDisplayName(ownerUuid, ownerName);
                    try { ent.getClass().getMethod("setCustomName", String.class).invoke(ent, displayName); } catch (Throwable ignored) {}
                    try {
                        org.bukkit.scoreboard.Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
                        if (scoreboard != null) {
                            org.bukkit.scoreboard.Team replayTeam = scoreboard.getTeam("skepifb_replay_nametag");
                            if (replayTeam == null) {
                                replayTeam = scoreboard.registerNewTeam("skepifb_replay_nametag");
                                replayTeam.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
                                replayTeam.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
                            }
                            try {
                                String entry = ent.getName();
                                if (entry != null && !entry.isEmpty() && !replayTeam.hasEntry(entry)) {
                                    replayTeam.addEntry(entry);
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Class<?> nametagTraitClass = Class.forName("net.citizensnpcs.trait.NametagTrait");
                        Object trait = npc.getClass().getMethod("getTrait", Class.class).invoke(npc, nametagTraitClass);
                        if (trait != null) {
                            try { trait.getClass().getMethod("setNameVisible", boolean.class).invoke(trait, false); } catch (Throwable ignored) {}
                            try { trait.getClass().getMethod("setName", String.class).invoke(trait, displayName); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (!p.getUniqueId().equals(viewer.getUniqueId())) {
                            try { p.hideEntity(plugin, ent); } catch (Throwable ignored) {}
                        }
                    }
                    spawnReplayInfoLines(viewer, ent.getLocation(), startFrame);
                }
            } catch (Throwable ignored) {}

            return npc;
        } catch (Throwable ex) {
            return null;
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!isInReplay(player.getUniqueId())) {
            return;
        }
        Object npc = replayNpcs.get(player.getUniqueId());
        if (npc == null) {
            return;
        }
        try {
            Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
            if (!(entity instanceof org.bukkit.entity.Entity)) {
                return;
            }
            org.bukkit.entity.Entity npcEntity = (org.bukkit.entity.Entity) entity;
            if (!npcEntity.equals(event.getRightClicked())) {
                return;
            }
            org.bukkit.Location loc = npcEntity.getLocation();
            if (loc != null) {
                player.teleport(loc);
                event.setCancelled(true);
                updateReplayActionBar(player.getUniqueId());
            }
        } catch (Throwable ignored) {
        }
    }

    public void cleanupAllReplays() {
        // cancel tasks and restore any per-player replay block changes
        for (UUID u : new ArrayList<>(replayTasks.keySet())) {
            cancelReplay(u);
        }
        // restore any remaining fake blocks for players that were in replay mode
        for (UUID u : new ArrayList<>(replayFakeBlocks.keySet())) {
            restoreReplayBlocks(u);
        }
        // destroy NPCs
        for (UUID u : new ArrayList<>(replayNpcs.keySet())) {
            Object npc = replayNpcs.remove(u);
            if (npc != null) {
                try { npc.getClass().getMethod("destroy").invoke(npc); } catch (Throwable ignored) {}
            }
            removeReplayInfoLines(u);
        }
        replayFakeBlocks.clear();
        replayRestoreQueues.clear();
        inReplayMode.clear();
    }

    public String getReplayDebugString(UUID viewerUuid) {
        if (!isInReplay(viewerUuid)) {
            return "Not in replay.";
        }
        java.util.List<ReplayFrame> frames = activeReplayFrames.get(viewerUuid);
        int total = frames == null ? 0 : frames.size();
        int currentIdx = Math.max(0, activeReplayIndex.getOrDefault(viewerUuid, 0) - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("Current replay frame: ").append(currentIdx).append('\n');
        sb.append("Total replay frames: ").append(total).append('\n');

        org.bukkit.Location npcLoc = null;
        Object npc = replayNpcs.get(viewerUuid);
        if (npc != null) {
            try {
                Object entity = npc.getClass().getMethod("getEntity").invoke(npc);
                if (entity instanceof org.bukkit.entity.Entity) {
                    npcLoc = ((org.bukkit.entity.Entity) entity).getLocation();
                }
            } catch (Throwable ignored) {}
        }
        if (npcLoc != null) {
            sb.append("Current NPC X Y Z: ").append(String.format(Locale.ROOT, "%.3f %.3f %.3f", npcLoc.getX(), npcLoc.getY(), npcLoc.getZ())).append('\n');
        } else {
            sb.append("Current NPC X Y Z: (unknown)\n");
        }

        if (frames != null && currentIdx >= 0 && currentIdx < frames.size()) {
            ReplayFrame f = frames.get(currentIdx);
            sb.append("Current replay frame X Y Z: ").append(String.format(Locale.ROOT, "%.3f %.3f %.3f", f.getX(), f.getY(), f.getZ())).append('\n');
            if (npcLoc != null) {
                try {
                    double dist = npcLoc.distance(new org.bukkit.Location(npcLoc.getWorld(), f.getX(), f.getY(), f.getZ()));
                    sb.append("Distance between NPC and replay frame: ").append(String.format(Locale.ROOT, "%.5f", dist)).append('\n');
                } catch (Throwable ignored) {
                    sb.append("Distance between NPC and replay frame: (error)\n");
                }
            }
            // verify tick sequence
            boolean ok = true;
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i).getTick() != i) { ok = false; break; }
            }
            sb.append(ok ? "Ticks sequential: OK\n" : "Ticks sequential: MISMATCH\n");
        }

        return sb.toString();
    }

    private void checkFinishForRunningPlayers() {
        for (UUID playerUuid : new java.util.ArrayList<>(sessions.keySet())) {
            AttemptSession session = sessions.get(playerUuid);
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            // Only consider players actually in FastBuilder arenas or test mode for run/attempt processing
            if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
                continue;
            }
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (!isInReplay(playerUuid)) {
                hotbarManager.validateFastBuilderInventory(player);
            }
            if (session == null || !session.isRunning()) {
                continue;
            }
            session.advanceTick();
            session.updateJumpTicks(player.isOnGround());
            session.updateLiveSpeed(player.getLocation());
            if (session.getTickCount() % 5 == 0) {
                session.sampleAverageSpeed(player.getLocation());
            }
            checkAndAwardPlaytimeXp(player, session);
            updateXpBossBar(player);
            sendRunningActionBar(player, session);
            if (shouldFinishAttempt(player)) {
                handleFinish(player, session);
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        if (inReplayMode.contains(playerUuid)) {
            event.setCancelled(true);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        ItemStack placedItem = event.getItemInHand();
        HotbarItem hotbarItem = hotbarManager.getHotbarItemForStack(placedItem);
        boolean placedPracticeBlock = hotbarItem != null && hotbarItem.getAction() == HotbarAction.PRACTICE_BLOCK;
        if (placedPracticeBlock) {
            if (!isPracticeMode(playerUuid)) {
                event.setCancelled(true);
                return;
            }

            Block block = event.getBlockPlaced();
            if (block != null) {
                trackPracticeBlock(playerUuid, block.getLocation());
            }
            return;
        }

        AttemptSession session = getOrCreateSession(playerUuid);
        if (session.hasFinished()) {
            event.setCancelled(true);
            return;
        }

        Block block = event.getBlockPlaced();

        if (!session.isRunning() && shouldStartAttemptOnBlockPlacement(player)) {
            startAttemptIfNotRunning(player);
        }

        if (block != null) {
            session.trackBlock(block);
            if (session.isRunning()) {
                session.trackBlockPlace();
                session.addBlockPlacement(new ReplayBlockEvent(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().name(), false));
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }

        boolean hasMeaningfulMovement = hasMeaningfulMovement(event);
        if (!inReplayMode.contains(playerUuid)) {
            if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                if (hasMeaningfulMovement) {
                    checkBoundaries(player);
                }
            }
        }

        // Replay mode uses its own dedicated hotbar (replay controls only) and must never be touched
        // by normal/practice FastBuilder hotbar upkeep - the replay controls can occupy the same slot
        // numbers as normal hotbar items (e.g. the Practice Block slot), so running this logic while
        // watching a replay would incorrectly treat a replay control as a "missing" practice item and
        // clear it out from under the viewer.
        if (!inReplayMode.contains(playerUuid)) {
            if (!isPracticeMode(playerUuid)) {
                hotbarManager.removePracticeBlocksFromInventory(player);
            }
            inventoryManager.refillHotbarIfNeeded(player);
            applyInArenaProtection(player);
        }
        updateXpBossBar(player);

        AttemptSession session = sessions.get(playerUuid);
        if (session == null || !session.isRunning()) {
            if (!inReplayMode.contains(playerUuid) && hasMeaningfulMovement && shouldStartAttemptOnMovement(player)) {
                if (startAttemptIfNotRunning(player)) {
                    session = sessions.get(playerUuid);
                    if (session != null && session.isRunning()) {
                        recordMovementFrame(session, player);
                    }
                }
            }
            return;
        }

        if (!inReplayMode.contains(playerUuid) && hasMeaningfulMovement) {
            recordMovementFrame(session, player);
            if (shouldFinishAttempt(player)) {
                handleFinish(player, session);
            }
        }
    }

    /**
     * Thin wrapper around AttemptSession#recordMovementFrame that stamps the new frame with the
     * player's ping and left/right CPS AT THIS EXACT MOMENT (via ReplayPlaceholderManager), so a
     * replay of this frame later shows what those values genuinely were during the run, not
     * whoever's watching it. See ReplayPlaceholderManager's class javadoc for why this matters.
     */
    private void recordMovementFrame(AttemptSession session, Player player) {
        int ping = me.skepi.skepifb.placeholder.ReplayPlaceholderManager.getCurrentPing(player);
        int leftCps = plugin.getReplayPlaceholderManager().getCurrentLeftCps(player.getUniqueId());
        int rightCps = plugin.getReplayPlaceholderManager().getCurrentRightCps(player.getUniqueId());
        session.recordMovementFrame(player.getLocation(), player.getLocation().getYaw(), player.getLocation().getPitch(), player.isSneaking(), player.isSprinting(), getEquippedBlockMaterialName(player), ping, leftCps, rightCps);
    }

    static boolean hasMeaningfulMovement(PlayerMoveEvent event) {
        if (event == null || event.getTo() == null) {
            return false;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null) {
            return false;
        }
        if (from.getWorld() != null && to.getWorld() != null && from.getWorld() != to.getWorld()) {
            return true;
        }
        return Double.compare(from.getX(), to.getX()) != 0
                || Double.compare(from.getY(), to.getY()) != 0
                || Double.compare(from.getZ(), to.getZ()) != 0;
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
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        if (isInReplay(playerUuid)) {
            try { closeReplay(event.getPlayer()); } catch (Throwable ignored) {}
        }
        removeXpBossBar(playerUuid);
        resetPlayerSession(playerUuid, event.getPlayer().getWorld());
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        movementStartSuppressed.add(playerUuid);
        AttemptSession session = sessions.get(playerUuid);
        if (session != null && session.isRunning()) {
            session.resetSpeedTracking();
            session.updateLiveSpeed(player.getLocation());
            session.sampleAverageSpeed(player.getLocation());
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        movementStartSuppressed.add(playerUuid);
        AttemptSession session = sessions.get(playerUuid);
        if (session != null && session.isRunning()) {
            session.resetSpeedTracking();
            session.updateLiveSpeed(player.getLocation());
            session.sampleAverageSpeed(player.getLocation());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String arenaName = playerManager.getPlayerArena(playerUuid);
        boolean inFastBuilder = playerManager.isInArena(playerUuid);
        boolean inTestMode = playerManager.isInTestMode(playerUuid);
        boolean inReplay = inReplayMode.contains(playerUuid);
        AttemptSession session = sessions.get(playerUuid);
        boolean inAttempt = session != null && session.isRunning();
        org.bukkit.block.Block block = event.getBlock();

        if (!inFastBuilder && !inTestMode) {
            return;
        }
        if (inReplay) {
            event.setCancelled(true);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (block == null) {
            event.setCancelled(true);
            return;
        }

        if (isPracticeBlock(playerUuid, block.getLocation())) {
            removePracticeBlock(playerUuid, block.getLocation());
            return;
        }

        if (isPracticeMode(playerUuid)) {
            if (session != null && session.isOwnerOfBlock(block, playerUuid)) {
                session.addBlockBreak(new ReplayBlockEvent(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().name(), true));
                session.removeTrackedBlock(block);
            } else {
                event.setCancelled(true);
            }
            return;
        }

        if (session != null && session.isOwnerOfBlock(block, playerUuid)) {
            session.addBlockBreak(new ReplayBlockEvent(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().name(), true));
            session.removeTrackedBlock(block);
            return;
        }

        if (session == null || !session.isRunning()) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        boolean isBedBlock = clickedBlock.getBlockData() instanceof org.bukkit.block.data.type.Bed;
        if (!isBedBlock) {
            return;
        }

        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        if (arenaName == null || !"BED".equalsIgnoreCase(plugin.getConfigManager().getArenaFinishMode(arenaName))) {
            return;
        }

        AttemptSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isRunning() || session.hasFinished()) {
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        handleFinish(player, session);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        resetPlayerSession(event.getPlayer().getUniqueId(), event.getPlayer().getWorld());
    }

    public String getTimerPlaceholder(UUID playerUuid) {
        AttemptSession session = sessions.get(playerUuid);
        if (session == null) {
            return "0.000";
        }
        return session.getTimerText();
    }

    public int getBlockCountPlaceholder(UUID playerUuid) {
        AttemptSession session = sessions.get(playerUuid);
        if (session == null) {
            return 0;
        }
        return session.getBlockPlacementCount();
    }

    public int getCoinsPlaceholder(UUID playerUuid) {
        return statsManager.getCoins(playerUuid);
    }

    public String getSpeedPlaceholder(UUID playerUuid) {
        AttemptSession session = sessions.get(playerUuid);
        if (session == null) {
            return "0.00";
        }
        return session.getCurrentSpeedText();
    }

    public String getAverageSpeedPlaceholder(UUID playerUuid) {
        AttemptSession session = sessions.get(playerUuid);
        if (session == null) {
            return "0.00";
        }
        return session.getAverageSpeedText();
    }

    public void saveFailedReplayIfApplicable(Player player, AttemptSession session) {
        if (player == null || session == null || !session.isRunning()) {
            return;
        }
        if (isPracticeMode(player.getUniqueId())) {
            return;
        }
        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            return;
        }
        List<ReplayFrame> frames = session.getFrames();
        if (frames.isEmpty()) {
            return;
        }
        replayManager.saveFailedReplay(player.getUniqueId(), arenaName, player.getName(), session.getTimerSeconds(), session.getBlockPlacementCount(), frames);
    }

    public double getPersonalBestPlaceholder(UUID playerUuid, String arenaName) {
        return statsManager.getPersonalBest(playerUuid, arenaName);
    }

    public int getAttemptsPlaceholder(UUID playerUuid, String arenaName) {
        return statsManager.getAttempts(playerUuid, arenaName);
    }

    public void clearSession(UUID playerUuid) {
        if (playerUuid == null) return;

        // Ensure any active replay for this player is fully cancelled and cleaned up
        try { cancelReplay(playerUuid); } catch (Throwable ignored) {}
        restoreReplayBlocks(playerUuid);
        Object npc = replayNpcs.remove(playerUuid);
        if (npc != null) {
            try { npc.getClass().getMethod("destroy").invoke(npc); } catch (Throwable ignored) {}
        }
        removeReplayInfoLines(playerUuid);
        inReplayMode.remove(playerUuid);

        // Clear replay-related buffers and tasks
        replayTasks.remove(playerUuid);
        replayFakeBlocks.remove(playerUuid);
        replayRestoreQueues.remove(playerUuid);
        activeReplayFrames.remove(playerUuid);
        activeReplayIndex.remove(playerUuid);
        replayPaused.remove(playerUuid);

        // Clear session attempt and practice blocks
        sessions.remove(playerUuid);
        removePracticeBlocks(playerUuid);
        practiceModePlayers.remove(playerUuid);

        // Misc cleanup
        movementStartSuppressed.remove(playerUuid);
        practiceCheckpoints.remove(playerUuid);
    }

    public AttemptSession getSession(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    public void removeTrackedBlocksInWorld(Player player, org.bukkit.World world) {
        AttemptSession session = getSession(player.getUniqueId());
        if (session != null) {
            session.removeTrackedBlocksInWorld(world);
        }
    }

    private AttemptSession getOrCreateSession(UUID playerUuid) {
        return sessions.computeIfAbsent(playerUuid, AttemptSession::new);
    }

    private String placeholders(Player player, AttemptSession session, String input) {
        return placeholders(player, session, input, 0.0, 0, false, 0.0);
    }

    private String placeholders(Player player, AttemptSession session, String input, double finishTime, int receivedCoins, boolean isNewPB, double previousPB) {
        if (input == null) {
            return "";
        }
        String result = input;
        if (result.contains("%timer%")) {
            result = result.replace("%timer%", getTimerPlaceholder(player.getUniqueId()));
        }
        if (result.contains("%time%")) {
            result = result.replace("%time%", String.format("%.3f", finishTime));
        }
        if (result.contains("%xp%")) {
            result = result.replace("%xp%", String.valueOf(statsManager.getXp(player.getUniqueId())));
        }
        if (result.contains("%level%")) {
            result = result.replace("%level%", String.valueOf(getLevelForXp(statsManager.getXp(player.getUniqueId()))));
        }
        if (result.contains("%next_level_xp%")) {
            result = result.replace("%next_level_xp%", String.valueOf(getNextLevelXpForXp(statsManager.getXp(player.getUniqueId()))));
        }
        if (result.contains("%bossbar_percent%")) {
            result = result.replace("%bossbar_percent%", String.valueOf(getBossbarPercentForXp(statsManager.getXp(player.getUniqueId()))));
        }
        if (result.contains("%blocks%")) {
            result = result.replace("%blocks%", String.valueOf(getBlockCountPlaceholder(player.getUniqueId())));
        }
        if (result.contains("%coins%")) {
            result = result.replace("%coins%", String.valueOf(getCoinsPlaceholder(player.getUniqueId())));
        }
        if (result.contains("%receivedcoins%")) {
            result = result.replace("%receivedcoins%", String.valueOf(receivedCoins));
        }
        if (result.contains("%attempts%")) {
            String arenaName = playerManager.getPlayerArena(player.getUniqueId());
            int attempts = arenaName != null ? getAttemptsPlaceholder(player.getUniqueId(), arenaName) : 0;
            result = result.replace("%attempts%", String.valueOf(attempts));
        }
        if (result.contains("%pb%")) {
            String arenaName = playerManager.getPlayerArena(player.getUniqueId());
            double pb = arenaName != null ? getPersonalBestPlaceholder(player.getUniqueId(), arenaName) : 0.0;
            result = result.replace("%pb%", pb == 0.0 ? "N/A" : String.format("%.3f", pb));
        }
        if (result.contains("%speed%")) {
            String speedValue = finishTime > 0.0 ? getAverageSpeedPlaceholder(player.getUniqueId()) : getSpeedPlaceholder(player.getUniqueId());
            result = result.replace("%speed%", speedValue);
        }
        if (result.contains("%averagespeed%")) {
            result = result.replace("%averagespeed%", getAverageSpeedPlaceholder(player.getUniqueId()));
        }
        if (result.contains("%pbdiff%")) {
            result = result.replace("%pbdiff%", formatPBDiff(finishTime, previousPB));
        }
        if (result.contains("%sessiontop1%") || result.contains("%sessiontop2%") || result.contains("%sessiontop3%") || result.contains("%sessiontop4%") || result.contains("%sessiontop5%")) {
            String arenaName = playerManager.getPlayerArena(player.getUniqueId());
            SkepiFBPlugin main = (SkepiFBPlugin) plugin;
            for (int i = 1; i <= 5; i++) {
                String key = "%sessiontop" + i + "%";
                if (result.contains(key)) {
                    String entry = main.getSessionTopManager().getFormattedEntry(arenaName, i, player);
                    result = result.replace(key, entry);
                }
            }
        }
        // These four were only ever supported by the scoreboard's own placeholder resolver, not
        // here - so a finish-title/actionbar config line using %player%, %mode%, %top%,
        // %averagetime% or %completions% (all of which are documented as general-purpose
        // placeholders) would print the literal text instead of a value. Same values, same
        // sources as ScoreboardManager, so they now behave identically everywhere.
        if (result.contains("%player%")) {
            result = result.replace("%player%", player.getName());
        }
        if (result.contains("%mode%") || result.contains("%top%") || result.contains("%averagetime%") || result.contains("%completions%")) {
            String arenaName = playerManager.getPlayerArena(player.getUniqueId());
            String mode = isPracticeMode(player.getUniqueId()) ? "Practice" : (arenaName != null ? arenaName : "None");
            SkepiFBPlugin main = (SkepiFBPlugin) plugin;
            double topPercent = arenaName == null ? -1.0 : main.getScoreboardManager().getTopPercentile(player.getUniqueId(), arenaName);
            double averageTime = arenaName == null ? 0.0 : statsManager.getAverageCompletionTime(player.getUniqueId(), arenaName);
            int completions = arenaName == null ? 0 : statsManager.getCompletions(player.getUniqueId(), arenaName);
            result = result.replace("%mode%", mode);
            result = result.replace("%top%", topPercent < 0.0 ? "N/A" : String.format(Locale.ROOT, "%.2f%%", topPercent));
            result = result.replace("%averagetime%", averageTime <= 0.0 ? "N/A" : String.format(Locale.ROOT, "%.3f", averageTime));
            result = result.replace("%completions%", completions <= 0 ? "N/A" : String.valueOf(completions));
        }
        return result;
    }

    private String formatPBDiff(double finishTime, double pb) {
        if (pb <= 0) {
            return "&e-0.000";
        }
        double diff = finishTime - pb;
        String formatted = String.format(Locale.ROOT, "%.3f", Math.abs(diff));
        if (diff > 0) {
            return "&c+" + formatted;
        } else if (diff < 0) {
            return "&a-" + formatted;
        } else {
            return "&e-0.000";
        }
    }

    private void applyInArenaProtection(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
        if (player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    private void sendFinishMessages(Player player, AttemptSession session, double finishTime, int receivedCoins, boolean isNewPB, double previousPB) {
        java.util.List<String> lines = isNewPB
                ? plugin.getConfigManager().getFinishMessagesPersonalBest()
                : plugin.getConfigManager().getFinishMessagesNormalFinish();

        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            if (rawLine.equals("none")) {
                continue;
            }
            String renderedLine = placeholders(player, session, rawLine, finishTime, receivedCoins, isNewPB, previousPB);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', renderedLine));
        }
    }

    private boolean shouldFinishAttempt(Player player) {
        if (player == null) {
            return false;
        }
        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            return detectGoldenPressurePlateOverlap(player);
        }
        String finishMode = plugin.getConfigManager().getArenaFinishMode(arenaName);
        if ("BED".equalsIgnoreCase(finishMode)) {
            return false;
        }
        return detectGoldenPressurePlateOverlap(player);
    }

    private boolean shouldStartAttemptOnMovement(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerUuid = player.getUniqueId();
        if (movementStartSuppressed.remove(playerUuid)) {
            return false;
        }
        String arenaName = playerManager.getPlayerArena(playerUuid);
        if (arenaName == null) {
            return false;
        }
        return "MOVE".equalsIgnoreCase(plugin.getConfigManager().getArenaStartMode(arenaName));
    }

    private boolean shouldStartAttemptOnBlockPlacement(Player player) {
        if (player == null) {
            return false;
        }
        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            return playerManager.isInTestMode(player.getUniqueId());
        }
        return "BLOCK".equalsIgnoreCase(plugin.getConfigManager().getArenaStartMode(arenaName));
    }

    private boolean detectGoldenPressurePlateOverlap(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return false;
        }

        org.bukkit.util.BoundingBox aabb = player.getBoundingBox();
        if (aabb == null) {
            return false;
        }

        double shrink = plugin.getConfigManager().getFinishHorizontalShrink();
        double jumpHeight = plugin.getConfigManager().getFinishJumpPlates();

        double minX = aabb.getMinX() + shrink;
        double maxX = aabb.getMaxX() - shrink;
        double minZ = aabb.getMinZ() + shrink;
        double maxZ = aabb.getMaxZ() - shrink;
        double minY = aabb.getMinY() - jumpHeight;
        double maxY = aabb.getMaxY();

        if (maxX < minX || maxZ < minZ) {
            return false;
        }

        int minBlockX = (int) Math.floor(minX);
        int maxBlockX = (int) Math.ceil(maxX) - 1;
        int minBlockY = (int) Math.floor(minY);
        int maxBlockY = (int) Math.ceil(maxY) - 1;
        int minBlockZ = (int) Math.floor(minZ);
        int maxBlockZ = (int) Math.ceil(maxZ) - 1;

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int y = minBlockY; y <= maxBlockY; y++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                            || type == Material.HEAVY_WEIGHTED_PRESSURE_PLATE
                            || type == Material.STONE_PRESSURE_PLATE
                            || type == Material.OAK_PRESSURE_PLATE) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean detectBedOverlap(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return false;
        }

        org.bukkit.util.BoundingBox aabb = player.getBoundingBox();
        if (aabb == null) {
            return false;
        }

        int minBlockX = (int) Math.floor(aabb.getMinX());
        int maxBlockX = (int) Math.ceil(aabb.getMaxX()) - 1;
        int minBlockY = (int) Math.floor(aabb.getMinY());
        int maxBlockY = (int) Math.ceil(aabb.getMaxY()) - 1;
        int minBlockZ = (int) Math.floor(aabb.getMinZ());
        int maxBlockZ = (int) Math.ceil(aabb.getMaxZ()) - 1;

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int y = minBlockY; y <= maxBlockY; y++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    String materialName = type.name();
                    if (materialName.endsWith("_BED") || "BED".equals(materialName) || "BED_BLOCK".equals(materialName)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void sendRunningActionBar(Player player, AttemptSession session) {
        if (player == null || session == null || !session.isRunning()) {
            return;
        }
        String format = plugin.getConfigManager().getActionbarFormat();
        String rendered = placeholders(player, session, format);
        player.sendActionBar(ChatColor.translateAlternateColorCodes('&', rendered));
    }

    private static final long FINISH_RESPAWN_DELAY_TICKS = 30L;

    /**
     * Broadcasts a staff alert (to anyone online with "skepifb.admin.alerts") if this completed
     * run's RAW finish time is at or under the mode's configured "max-time" flag threshold
     * (arena_settings.yml) - checks the time itself, NOT the player's personal best. A threshold
     * of 0.000 (the default) disables this entirely for that mode, since no legitimate run can
     * finish that fast.
     */
    private void flagSuspiciouslyFastFinish(Player player, String arenaName, double finishTime) {
        double maxTime = plugin.getConfigManager().getArenaMaxTime(arenaName);
        if (maxTime <= 0.0 || finishTime > maxTime) {
            return;
        }
        String formattedTime = String.format(java.util.Locale.ROOT, "%.3f", finishTime);
        String formattedThreshold = String.format(java.util.Locale.ROOT, "%.3f", maxTime);
        String alert = ChatColor.RED + "[Anti-Cheat] " + ChatColor.YELLOW + player.getName() + ChatColor.RED
                + " finished " + ChatColor.YELLOW + arenaName + ChatColor.RED + " in " + ChatColor.YELLOW
                + formattedTime + "s" + ChatColor.RED + " (flag threshold " + ChatColor.YELLOW + formattedThreshold
                + "s" + ChatColor.RED + ") - possible cheating.";
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("skepifb.admin.alerts")) {
                staff.sendMessage(alert);
            }
        }
        plugin.getLogger().warning("[Anti-Cheat] " + player.getName() + " finished " + arenaName + " in " + formattedTime
                + "s (<= flag threshold " + formattedThreshold + "s)");
    }

    private void handleFinish(Player player, AttemptSession session) {
        session.finishAttempt();
        // Preserve the final displayed actionbar (do not clear to 0.000)
        double finishTime = session.getTimerSeconds();
        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        boolean practiceMode = isPracticeMode(player.getUniqueId());
        if (arenaName != null && !practiceMode) {
            flagSuspiciouslyFastFinish(player, arenaName, finishTime);
        }

        int receivedCoins = 0;
        boolean isNewPB = false;
        double previousPB = 0.0;
        if (arenaName != null && !practiceMode) {
            previousPB = statsManager.getPersonalBest(player.getUniqueId(), arenaName);
            isNewPB = statsManager.updatePersonalBestIfFaster(player.getUniqueId(), arenaName, finishTime);
            receivedCoins = isNewPB ? plugin.getConfigManager().getFinishRewardPersonalBest() : plugin.getConfigManager().getFinishRewardNormal();
            statsManager.addCoins(player.getUniqueId(), receivedCoins);
            // Award run completion XP silently
            statsManager.addXp(player.getUniqueId(), 50);
        }

        String finishTitle = plugin.getConfigManager().getFinishTitle();
        String finishSubtitle = plugin.getConfigManager().getFinishSubtitle();
        player.sendTitle(
                ChatColor.translateAlternateColorCodes('&', placeholders(player, session, finishTitle, finishTime, receivedCoins, isNewPB, previousPB)),
                ChatColor.translateAlternateColorCodes('&', placeholders(player, session, finishSubtitle, finishTime, receivedCoins, isNewPB, previousPB)),
                5,
                20,
                5
        );

        sendFinishMessages(player, session, finishTime, receivedCoins, isNewPB, previousPB);

        // Record runtime session top (do not persist)
        if (arenaName != null && !practiceMode) {
            try {
                SkepiFBPlugin main = (SkepiFBPlugin) plugin;
                main.getSessionTopManager().recordFinish(arenaName, player.getUniqueId(), finishTime);
            } catch (Throwable ignored) {
            }
        }

        if (arenaName != null && !practiceMode) {
            statsManager.recordCompletion(player.getUniqueId(), arenaName, finishTime);
        }

        // Auto-qualify for the mode's UNVERIFIED leaderboard - unlike the VERIFIED board (which
        // only an admin can place someone on via "/fb lb add"), every completed, non-practice run
        // is checked here and, if it's fast enough, the player is placed in the highest open/beaten
        // slot out of the top 10 for that mode. Silently does nothing if the time doesn't qualify.
        if (arenaName != null && !practiceMode) {
            try {
                plugin.getLeaderboardManager().addEntry(
                        me.skepi.skepifb.leaderboard.LeaderboardManager.LeaderboardType.UNVERIFIED,
                        arenaName,
                        player.getUniqueId(),
                        player.getName(),
                        finishTime
                );
            } catch (Throwable ignored) {
            }
        }

        if (arenaName != null && !practiceMode) {
            List<ReplayFrame> replayFrames = session.getFrames();
            replayManager.recordReplay(player.getUniqueId(), arenaName, player.getName(), finishTime, session.getBlockPlacementCount(), replayFrames, isNewPB, player);
        }

        org.bukkit.Location finishLocation = player.getLocation().clone();
        launchFinishFireworks(player, finishLocation, isNewPB);

        java.util.List<org.bukkit.Location> trackedLocations = session.getTrackedBlockLocations(player.getWorld());
        scheduleRespawnAfterFinish(session, player, trackedLocations);
    }

    @EventHandler
    private void handlePlayerAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return;
        }
        AttemptSession session = sessions.get(playerUuid);
        if (session == null || !session.isRunning()) {
            return;
        }
        session.addArmSwing(event.getAnimationType().name());
    }

    private enum RankLevel {
        LEVEL_1(1, 0, 4999, ChatColor.GREEN, Material.TURTLE_HELMET),
        LEVEL_2(2, 5000, 14999, ChatColor.GOLD, Material.LEATHER_HELMET),
        LEVEL_3(3, 15000, 39999, ChatColor.GRAY, Material.CHAINMAIL_HELMET),
        LEVEL_4(4, 40000, 99999, ChatColor.DARK_GRAY, Material.IRON_HELMET),
        LEVEL_5(5, 100000, 249999, ChatColor.YELLOW, Material.GOLDEN_HELMET),
        LEVEL_6(6, 250000, 999999, ChatColor.AQUA, Material.DIAMOND_HELMET),
        LEVEL_7(7, 1000000, Integer.MAX_VALUE, ChatColor.DARK_PURPLE, Material.NETHERITE_HELMET);

        private final int level;
        private final int minXp;
        private final int maxXp;
        private final ChatColor chatColor;
        private final Material helmet;

        RankLevel(int level, int minXp, int maxXp, ChatColor chatColor, Material helmet) {
            this.level = level;
            this.minXp = minXp;
            this.maxXp = maxXp;
            this.chatColor = chatColor;
            this.helmet = helmet;
        }

        public int getLevel() {
            return level;
        }

        public int getMinXp() {
            return minXp;
        }

        public int getMaxXp() {
            return maxXp;
        }

        public ChatColor getChatColor() {
            return chatColor;
        }

        public Material getHelmetMaterial() {
            return helmet;
        }

        public static RankLevel fromXp(int xp) {
            for (RankLevel rank : values()) {
                if (xp >= rank.minXp && xp <= rank.maxXp) {
                    return rank;
                }
            }
            return LEVEL_1;
        }

        public static RankLevel fromLevel(int level) {
            for (RankLevel rank : values()) {
                if (rank.level == level) {
                    return rank;
                }
            }
            return LEVEL_1;
        }
    }

    private void checkAndAwardPlaytimeXp(Player player, AttemptSession session) {
        if (player == null || session == null) {
            return;
        }
        int currentTicks = session.getTickCount();
        while (currentTicks >= session.getNextPlaytimeXpRewardTick()) {
            session.advanceNextPlaytimeXpRewardTick();
            statsManager.addXp(player.getUniqueId(), 500);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&9+&3500 XP &8[&bPlaytime&8]"));
            onPlayerXpChanged(player.getUniqueId());
        }
    }

    public void onPlayerXpChanged(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        equipRankHelmet(player);
        updateXpBossBar(player);
        plugin.getShopManager().refreshOpenShopMenus();

        try {
            int currentXp = statsManager.getXp(playerUuid);
            Integer prevXp = lastKnownXp.get(playerUuid);
            int currentLevel = getLevelForXp(currentXp);
            Integer prevLevel = lastKnownLevel.get(playerUuid);

            // First observation: initialize and do not treat as level-up
            if (prevXp == null) {
                lastKnownXp.put(playerUuid, currentXp);
                lastKnownLevel.put(playerUuid, currentLevel);
                return;
            }

            // Only act when XP actually changed (prevents triggering on refresh/login)
            if (currentXp != prevXp) {
                if (prevLevel != null && currentLevel > prevLevel) {
                    // Level-up: send single chat message and launch one firework
                    String levelColored = getColoredLevelString(currentLevel);
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&bYou have leveled up to " + levelColored + "&b!"));

                    try {
                        org.bukkit.Color fwColor = mapDyeKeyToColor(getPlayerFireworkColorKey(playerUuid));
                        launchFireworkAt(player.getWorld(), player.getLocation(), fwColor);
                        launchFireworkAt(player.getWorld(), player.getLocation().clone().add(-0.5, 0.0, 0.0), fwColor);
                    } catch (Throwable ignored) {}
                }
            }

            lastKnownXp.put(playerUuid, currentXp);
            lastKnownLevel.put(playerUuid, currentLevel);
        } catch (Throwable ignored) {
        }
    }

    private String getPlayerFireworkColorKey(UUID playerUuid) {
        try {
            SkepiFBPlugin main = (SkepiFBPlugin) plugin;
            java.util.Optional<String> equipped = main.getShopManager().getEquippedShopItem(playerUuid, "firework_color");
            if (equipped.isPresent()) {
                String v = equipped.get();
                String[] parts = v.split(":");
                if (parts.length >= 2) return parts[1];
            }
            // fallback: default item from shop definition
            java.util.Optional<me.skepi.skepifb.shop.ShopManager.ShopDefinition> shopDef = main.getShopManager().getShopDefinition("firework_color");
            if (shopDef.isPresent()) {
                for (me.skepi.skepifb.shop.ShopManager.ShopCategoryDefinition cat : shopDef.get().getCategories()) {
                    for (me.skepi.skepifb.shop.ShopManager.ShopItemDefinition it : cat.getItems()) {
                        if (it.isDefaultItem()) return it.getKey();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void launchFireworkAt(World world, Location location, org.bukkit.Color color) {
        if (world == null || location == null) {
            return;
        }
        try {
            Firework fw = (Firework) world.spawnEntity(location, EntityType.FIREWORK_ROCKET);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().withColor(color).with(FireworkEffect.Type.BALL).build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
        } catch (Throwable ignored) {}
    }

    private void launchFinishFireworks(Player player, Location finishLocation, boolean isNewPersonalBest) {
        if (player == null || finishLocation == null) {
            return;
        }
        org.bukkit.Color fwColor = mapDyeKeyToColor(getPlayerFireworkColorKey(player.getUniqueId()));
        launchFireworkAt(player.getWorld(), finishLocation.clone().add(0.5, 0.0, 0.0), fwColor);
        launchFireworkAt(player.getWorld(), finishLocation.clone().add(-0.5, 0.0, 0.0), fwColor);

        if (!isNewPersonalBest) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            org.bukkit.Color delayedColor = mapDyeKeyToColor(getPlayerFireworkColorKey(player.getUniqueId()));
            launchFireworkAt(player.getWorld(), finishLocation.clone(), delayedColor);
            launchFireworkAt(player.getWorld(), finishLocation.clone(), delayedColor);
            launchFireworkAt(player.getWorld(), finishLocation.clone(), delayedColor);
        }, 5L);
    }

    private String getColoredLevelString(int level) {
        return switch (level) {
            case 1 -> "&aLevel 1";
            case 2 -> "&6Level 2";
            case 3 -> "&7Level 3";
            case 4 -> "&8Level 4";
            case 5 -> "&eLevel 5";
            case 6 -> "&bLevel 6";
            case 7 -> "&5Level 7";
            default -> "&fLevel " + level;
        };
    }

    private org.bukkit.Color mapDyeKeyToColor(String key) {
        if (key == null) return org.bukkit.Color.fromRGB(255,255,255);
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "white_dye" -> org.bukkit.Color.fromRGB(255,255,255);
            case "orange_dye" -> org.bukkit.Color.fromRGB(255,165,0);
            case "magenta_dye" -> org.bukkit.Color.fromRGB(255,0,255);
            case "light_blue_dye" -> org.bukkit.Color.fromRGB(173,216,230);
            case "yellow_dye" -> org.bukkit.Color.fromRGB(255,255,0);
            case "lime_dye" -> org.bukkit.Color.fromRGB(0,255,0);
            case "pink_dye" -> org.bukkit.Color.fromRGB(255,192,203);
            case "gray_dye" -> org.bukkit.Color.fromRGB(128,128,128);
            case "light_gray_dye" -> org.bukkit.Color.fromRGB(211,211,211);
            case "cyan_dye" -> org.bukkit.Color.fromRGB(0,255,255);
            case "purple_dye" -> org.bukkit.Color.fromRGB(128,0,128);
            case "blue_dye" -> org.bukkit.Color.fromRGB(0,0,255);
            case "brown_dye" -> org.bukkit.Color.fromRGB(150,75,0);
            case "green_dye" -> org.bukkit.Color.fromRGB(0,128,0);
            case "red_dye" -> org.bukkit.Color.fromRGB(255,0,0);
            case "black_dye" -> org.bukkit.Color.fromRGB(0,0,0);
            default -> org.bukkit.Color.fromRGB(255,255,255);
        };
    }

    /**
     * Applies the rank helmet appropriate for the player's current XP, if it isn't already equipped.
     * This is the single owner of the helmet slot for FastBuilder - HotbarManager used to also
     * enforce/restore the helmet on every tick, which raced with this method and was the cause of
     * the helmet intermittently disappearing/flickering; HotbarManager now just calls this method
     * whenever gear needs to be (re)applied instead of touching the helmet slot itself.
     */
    public void equipRankHelmet(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!playerManager.isInArena(player.getUniqueId()) && !playerManager.isInTestMode(player.getUniqueId())) {
            return;
        }
        int xp = statsManager.getXp(player.getUniqueId());
        RankLevel rank = RankLevel.fromXp(xp);
        ItemStack current = player.getInventory().getHelmet();
        // Only actually touch the equipment slot if the rank helmet materially changed (e.g. a
        // rankup). Re-setting an identical helmet on every XP gain (attempt start, finish, playtime
        // ticks, etc.) forces a fresh equipment sync to the client on every single XP tick. For
        // Level 1 the rank helmet is a Turtle Helmet, and vanilla automatically grants/refreshes a
        // Water Breathing effect while one is worn - momentarily un-equipping and re-equipping it
        // (even with an otherwise-identical replacement ItemStack) interrupts that vanilla tracking,
        // which is what produced the visible helmet flash and the "Water Breathing 0:00" HUD glitch
        // every time XP was gained. Skipping redundant re-equips fixes the cause, not just the symptom.
        if (current != null && current.getType() == rank.getHelmetMaterial()) {
            return;
        }
        // This should now be rare-to-never: the actual, confirmed root cause of the helmet
        // repeatedly clearing was a bug in HotbarManager.validateFastBuilderInventory (its "extra
        // inventory slots" cleanup loop was reading PlayerInventory#getContents(), which - unlike a
        // generic Inventory - includes the armor slots (helmet at index 39) in that array, so the
        // loop was calling setItem(39, null) on the helmet every single tick). That's fixed now. If
        // this warning still shows up, something else is genuinely clearing the helmet (another
        // plugin, or a cause not yet found) and is worth investigating with the details below.
        plugin.getLogger().warning("[SkepiFB] Rank helmet missing/wrong for " + player.getName()
                + " - had " + (current == null || current.getType() == Material.AIR ? "nothing" : current.getType())
                + ", expected " + rank.getHelmetMaterial()
                + " (xp=" + xp + ", gamemode=" + player.getGameMode() + ", tick=" + Bukkit.getCurrentTick() + "). Restoring it now.");
        ItemStack helmet = new ItemStack(rank.getHelmetMaterial());
        org.bukkit.inventory.meta.ItemMeta helmetMeta = helmet.getItemMeta();
        if (helmetMeta != null) {
            // Belt-and-suspenders: make sure the rank helmet can never break/disappear from combat
            // or durability damage, however unlikely that is inside an arena (damage is cancelled
            // for arena/test-mode players - see onEntityDamage below - but this costs nothing and
            // removes one more theoretical way for the helmet to vanish).
            helmetMeta.setUnbreakable(true);
            helmet.setItemMeta(helmetMeta);
        }
        player.getInventory().setHelmet(helmet);
    }

    public Material getExpectedRankHelmet(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        int xp = statsManager.getXp(playerUuid);
        RankLevel rank = RankLevel.fromXp(xp);
        return rank.getHelmetMaterial();
    }

    private void updateXpBossBar(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            removeXpBossBar(playerUuid);
            return;
        }
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        if (!main.getConfigManager().isBossbarEnabled()) {
            removeXpBossBar(playerUuid);
            return;
        }
        int xp = statsManager.getXp(playerUuid);
        RankLevel rank = RankLevel.fromXp(xp);
        int currentLevel = rank.getLevel();
        int minXp = rank.getMinXp();
        int maxXp = rank.getMaxXp();
        int nextLevelXp = currentLevel < 7 ? RankLevel.fromLevel(currentLevel + 1).getMinXp() : maxXp;
        int xpIntoLevel = xp - minXp;
        int xpForLevel = (currentLevel < 7 ? nextLevelXp - minXp : 1);
        double progress = currentLevel < 7 ? (double) xpIntoLevel / xpForLevel : 1.0;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;
        int percent = (int) (progress * 100.0);
        // Title is now fully configurable (bossbar.title-format in config.yml) instead of a single
        // hardcoded format - supports every general placeholder plus %level_color%, which resolves
        // to this rank's configured chat color (bossbar.colors.level-N).
        BarColor barColor = getBarColorForRank(rank);
        String levelColorCode = "&" + barColorToFormatChar(barColor);
        String title = main.getConfigManager().getBossbarTitleFormat()
                .replace("%level_color%", levelColorCode)
                .replace("%level%", String.valueOf(currentLevel))
                .replace("%bossbar_percent%", String.valueOf(percent))
                .replace("%xp%", String.valueOf(xp))
                .replace("%next_level_xp%", String.valueOf(currentLevel < 7 ? nextLevelXp : xp));
        BarStyle barStyle;
        try {
            barStyle = BarStyle.valueOf(main.getConfigManager().getBossbarStyleName().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            barStyle = BarStyle.SOLID;
        }
        final BarStyle finalBarStyle = barStyle;
        BossBar bossBar = xpBossBars.computeIfAbsent(playerUuid, uuid -> {
            BossBar bar = Bukkit.createBossBar("", barColor, finalBarStyle);
            bar.addPlayer(player);
            bar.setVisible(true);
            return bar;
        });
        bossBar.setColor(barColor);
        bossBar.setStyle(barStyle);
        bossBar.setProgress(progress);
        bossBar.setTitle(ChatColor.translateAlternateColorCodes('&', title));
        if (!bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }
        bossBar.setVisible(true);
    }

    /**
     * Converts a BarColor to the closest vanilla & color-code character, used to resolve
     * %level_color% in the configurable bossbar title format.
     */
    private char barColorToFormatChar(BarColor color) {
        if (color == null) {
            return 'f';
        }
        return switch (color) {
            case PINK -> 'd';
            case BLUE -> '9';
            case RED -> 'c';
            case GREEN -> 'a';
            case YELLOW -> 'e';
            case PURPLE -> '5';
            case WHITE -> 'f';
        };
    }

    private BarColor getBarColorForRank(RankLevel rank) {
        if (rank == null) {
            return BarColor.WHITE;
        }
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        try {
            return BarColor.valueOf(main.getConfigManager().getBossbarColorName(rank.getLevel()).toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return switch (rank) {
                case LEVEL_1 -> BarColor.GREEN;
                case LEVEL_2 -> BarColor.YELLOW;
                case LEVEL_3 -> BarColor.WHITE;
                case LEVEL_4 -> BarColor.WHITE;
                case LEVEL_5 -> BarColor.YELLOW;
                case LEVEL_6 -> BarColor.BLUE;
                case LEVEL_7 -> BarColor.PURPLE;
            };
        }
    }

    private void removeXpBossBar(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        BossBar bar = xpBossBars.remove(playerUuid);
        if (bar == null) {
            return;
        }
        for (Player p : new ArrayList<>(bar.getPlayers())) {
            bar.removePlayer(p);
        }
        try {
            bar.setVisible(false);
        } catch (Throwable ignored) {}
    }

    private List<ReplayFrame> collectReplayFrames(AttemptSession session) {
        if (session == null) {
            return List.of();
        }
        return session.getFrames();
    }

    private boolean startAttemptIfNotRunning(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInArena(playerUuid) && !playerManager.isInTestMode(playerUuid)) {
            return false;
        }
        // Cancel any existing animation only when actually beginning a new attempt.
        cancelActiveCleanup(playerUuid, "new-attempt");

        AttemptSession session = getOrCreateSession(playerUuid);
        if (session.isRunning() || session.hasFinished()) {
            return false;
        }
        session.startAttempt(session.hasTrackedBlocks());
        session.updateCurrentFrame(player.getLocation(), player.getLocation().getYaw(), player.getLocation().getPitch(), player.isSneaking(), player.isSprinting(), getEquippedBlockMaterialName(player),
                me.skepi.skepifb.placeholder.ReplayPlaceholderManager.getCurrentPing(player),
                plugin.getReplayPlaceholderManager().getCurrentLeftCps(playerUuid),
                plugin.getReplayPlaceholderManager().getCurrentRightCps(playerUuid));
        String arenaName = playerManager.getPlayerArena(playerUuid);
        if (arenaName != null) {
            statsManager.incrementAttempts(playerUuid, arenaName);
        }
        statsManager.addXp(playerUuid, 5);
        onPlayerXpChanged(playerUuid);
        return true;
    }

    private void checkBoundaries(Player player) {
        UUID playerUuid = player.getUniqueId();

        if (playerManager.isInTestMode(playerUuid)) {
            org.bukkit.Location testSpawn = playerManager.getTestModeSpawnLocation(playerUuid);
            if (testSpawn == null) {
                return;
            }
            ArenaBoundary boundary = ArenaBoundary.defaultTestBoundary();
            if (isOutOfBounds(player.getLocation(), new ArenaLocation(testSpawn.getX(), testSpawn.getBlockY(), testSpawn.getZ()), boundary)) {
                AttemptSession session = sessions.get(playerUuid);
                if (session != null && session.isRunning()) {
                        saveFailedReplayIfApplicable(player, session);
                        session.stopAttempt();
                        // Play configured animation steps while removing tracked blocks
                        playRemovalAnimation(session, player);
                }
                teleportPlayerBackToSpawn(player);
            }
            return;
        }

        String arenaName = playerManager.getPlayerArena(playerUuid);
        if (arenaName == null) {
            return;
        }

        Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) {
            return;
        }

        ArenaIsland island = arena.findIslandByPlayer(playerUuid).orElse(null);
        if (island == null) {
            return;
        }

        if (isOutOfBounds(player.getLocation(), island.getSpawnLocation(), arena.getBoundary(), plugin.getConfigManager().isArenaDirectionDiagonal(arenaName))) {
            AttemptSession session = sessions.get(playerUuid);
            if (session != null && session.isRunning()) {
                saveFailedReplayIfApplicable(player, session);
                session.stopAttempt();
                playRemovalAnimation(session, player);
            }
            teleportPlayerBackToSpawn(player);
        }
    }

    private boolean isWithinArenaBounds(Location location, Arena arena, ArenaIsland island) {
        if (location == null || arena == null || island == null) {
            return false;
        }
        boolean diagonal = plugin.getConfigManager().isArenaDirectionDiagonal(arena.getName());
        return !isOutOfBounds(location, island.getSpawnLocation(), arena.getBoundary(), diagonal);
    }

    private boolean isOutOfBounds(Location current, ArenaLocation spawn, ArenaBoundary boundary) {
        return isOutOfBounds(current, spawn, boundary, false);
    }

    /**
     * "diagonal" (the arena/mode's "direction" setting in arena_settings.yml - see
     * ConfigManager#getArenaDirection) rotates the left/right/back/forward boundary axes 45
     * degrees to the right instead of measuring them straight along world X/Z. In STRAIGHT mode
     * "right" is a straight line along +X and "forward" a straight line along +Z, same as before
     * this existed. In DIAGONAL mode "right" instead runs along the (+X,+Z) diagonal and
     * "forward" along the (+Z,-X) diagonal, using (dx+dz)/2 and (dz-dx)/2 rather than dx/dz
     * directly - that /2 is deliberate, not a normalization by sqrt(2): each single diagonal step
     * (dx=+1,dz=+1) advances this axis by exactly 1, so a boundary of e.g. 5 still takes exactly 5
     * blocks of player movement to reach, just 5 DIAGONAL blocks (moving both X and Z each step)
     * instead of 5 STRAIGHT blocks along one axis. up/down (Y) are never affected by direction.
     */
    private boolean isOutOfBounds(Location current, ArenaLocation spawn, ArenaBoundary boundary, boolean diagonal) {
        double currentY = current.getY();
        double spawnY = spawn.getY();

        double rightAxis;
        double forwardAxis;
        if (diagonal) {
            double dx = current.getX() - spawn.getX();
            double dz = current.getZ() - spawn.getZ();
            rightAxis = (dx + dz) / 2.0;
            forwardAxis = (dz - dx) / 2.0;
        } else {
            rightAxis = current.getX() - spawn.getX();
            forwardAxis = current.getZ() - spawn.getZ();
        }

        if (boundary.getLeft() >= 0 && rightAxis < -boundary.getLeft()) {
            return true;
        }
        if (boundary.getRight() >= 0 && rightAxis > boundary.getRight()) {
            return true;
        }
        if (boundary.getBack() >= 0 && forwardAxis < -boundary.getBack()) {
            return true;
        }
        if (boundary.getForward() >= 0 && forwardAxis > boundary.getForward()) {
            return true;
        }
        if (boundary.getDown() >= 0 && currentY < spawnY - boundary.getDown()) {
            return true;
        }
        if (boundary.getUp() >= 0 && currentY > spawnY + boundary.getUp()) {
            return true;
        }
        return false;
    }

    private void teleportPlayerBackToSpawn(Player player) {
        // Use PlayerManager to resolve respawn destination (temporary spawn if present)
        org.bukkit.Location dest = playerManager.getResolvedRespawnLocation(player.getUniqueId());
        if (dest == null) return;
        try { player.teleport(dest); } catch (Throwable ignored) {}
        inventoryManager.giveArenaBlock(player);
        hotbarManager.giveHotbarToPlayer(player);
        hotbarManager.validateFastBuilderInventory(player);
        try {
            player.updateInventory();
        } catch (Throwable ignored) {}
    }

    private void scheduleRespawnAfterFinish(AttemptSession session, Player player, java.util.List<org.bukkit.Location> trackedLocations) {
        if (session == null || player == null) {
            return;
        }

        boolean hasTrackedBlocks = trackedLocations != null && !trackedLocations.isEmpty();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player == null || !player.isOnline()) {
                return;
            }
            AttemptSession currentSession = sessions.get(player.getUniqueId());
            if (currentSession == null || currentSession.getAttemptId() == null || !currentSession.getAttemptId().equals(session.getAttemptId())) {
                return;
            }
            session.clearFinished();
            session.endAttempt();
            teleportPlayerBackToSpawn(player);
            if (hasTrackedBlocks) {
                playRemovalAnimation(session, player, trackedLocations, "respawn", null);
            }
        }, FINISH_RESPAWN_DELAY_TICKS);
    }

    public void resetPlayerSession(UUID playerUuid, org.bukkit.World world) {
        if (playerUuid == null) return;

        cancelActiveCleanup(playerUuid, "reset-session");

        // Reset any running attempt state
        AttemptSession session = sessions.get(playerUuid);
        if (session != null) {
            Player player = Bukkit.getPlayer(playerUuid);
            try {
                // Play removal animation (sequential) before fully resetting session
                if (player != null) {
                    playRemovalAnimation(session, player);
                }
            } catch (Throwable ignored) {}
            try { session.resetAttempt(world); } catch (Throwable ignored) {}
            sessions.remove(playerUuid);
        }

        // Clear replay/practice/session state
        clearSession(playerUuid);

        // Clear transient UI
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            try { player.sendActionBar(""); } catch (Throwable ignored) {}
        }

        // Remove practice blocks limited to this world
        removePracticeBlocksInWorld(playerUuid, world);
    }

    public void removePracticeBlocksInWorld(UUID playerUuid, org.bukkit.World world) {
        if (playerUuid == null) {
            return;
        }
        java.util.Set<org.bukkit.Location> blocks = practiceBlocks.remove(playerUuid);
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (org.bukkit.Location loc : new java.util.ArrayList<>(blocks)) {
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            if (world != null && !world.equals(loc.getWorld())) {
                continue;
            }
            try {
                loc.getWorld().getBlockAt(loc).setType(org.bukkit.Material.AIR);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * A single saved practice checkpoint - one per player, overwritten every time a new one is
     * saved (see setPracticeCheckpoint/teleportToPracticeCheckpoint below). Deliberately only
     * in-memory (cleared on logout/practice-mode-off) rather than persisted to disk - it's a
     * quick mid-run undo point, not something meant to survive a restart.
     */
    private static final class PracticeCheckpoint {
        final org.bukkit.Location location;
        final int movementPacketCount;
        final boolean timerRunning;
        final java.util.Set<String> trackedBlockKeys;

        PracticeCheckpoint(org.bukkit.Location location, int movementPacketCount, boolean timerRunning,
                            java.util.Set<String> trackedBlockKeys) {
            this.location = location;
            this.movementPacketCount = movementPacketCount;
            this.timerRunning = timerRunning;
            this.trackedBlockKeys = trackedBlockKeys;
        }
    }

    /**
     * "world:x:y:z" identity key for a block location - used to diff the blocks placed at
     * checkpoint-save time against whatever's placed at checkpoint-load time, deliberately not
     * relying on Location#equals (which also compares yaw/pitch/exact doubles - block-tracking
     * Locations aren't always constructed identically) for that comparison.
     */
    private static String blockKey(org.bukkit.Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private static java.util.Set<String> blockKeySet(java.util.Collection<org.bukkit.Location> locations) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (locations == null) {
            return keys;
        }
        for (org.bukkit.Location loc : locations) {
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            keys.add(blockKey(loc));
        }
        return keys;
    }

    /**
     * Saves the player's current position/facing, elapsed-timer state, and which real build
     * blocks are currently placed, as their one practice checkpoint, overwriting whatever was
     * saved before. Practice/reference blocks are intentionally NOT part of this - they're a
     * persistent guide layout the player builds against, not part of the attempt being
     * checkpointed, so a checkpoint save/restore never touches them.
     */
    public void setPracticeCheckpoint(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        AttemptSession session = sessions.get(uuid);
        int movementPacketCount = 0;
        boolean timerRunning = false;
        java.util.Set<String> trackedBlockKeys = java.util.Collections.emptySet();
        if (session != null) {
            timerRunning = session.isRunning();
            movementPacketCount = timerRunning ? session.getMovementPacketCount() : session.getFinalMovementPacketCount();
            trackedBlockKeys = blockKeySet(session.getTrackedBlockLocations(player.getWorld()));
        }
        practiceCheckpoints.put(uuid, new PracticeCheckpoint(player.getLocation().clone(),
                movementPacketCount, timerRunning, trackedBlockKeys));
        player.sendMessage(ChatColor.GREEN + "Checkpoint saved.");
    }

    /**
     * Teleports the player back to their saved practice checkpoint: restores position/facing,
     * rewinds the elapsed-timer state to exactly what it was when the checkpoint was saved, and
     * removes any REAL build block placed since (practice/reference blocks are left completely
     * alone - see setPracticeCheckpoint's javadoc for why).
     */
    public void teleportToPracticeCheckpoint(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PracticeCheckpoint checkpoint = practiceCheckpoints.get(uuid);
        if (checkpoint == null) {
            player.sendMessage(ChatColor.RED + "You haven't set a checkpoint yet - left-click to set one.");
            return;
        }

        AttemptSession session = sessions.get(uuid);
        if (session != null) {
            for (org.bukkit.Location loc : session.getTrackedBlockLocations(player.getWorld())) {
                if (loc == null || loc.getWorld() == null || checkpoint.trackedBlockKeys.contains(blockKey(loc))) {
                    continue;
                }
                org.bukkit.block.Block block = loc.getWorld().getBlockAt(loc);
                try {
                    removeBlockAndUpdateNeighbors(block);
                } catch (Throwable ignored) {
                }
                session.removeTrackedBlock(block);
            }
            session.restoreCheckpointTimer(checkpoint.movementPacketCount, checkpoint.timerRunning);
        }

        try {
            player.teleport(checkpoint.location);
        } catch (Throwable ignored) {
        }
        try {
            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        } catch (Throwable ignored) {
        }

        player.sendMessage(ChatColor.GREEN + "Returned to checkpoint.");
    }

    public boolean isPracticeMode(UUID playerUuid) {
        return playerUuid != null && practiceModePlayers.contains(playerUuid);
    }

    public boolean togglePracticeMode(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        Player player = Bukkit.getPlayer(playerUuid);
        boolean currentlyEnabled = practiceModePlayers.contains(playerUuid);
        if (currentlyEnabled) {
            practiceModePlayers.remove(playerUuid);
            removePracticeBlocks(playerUuid);
            practiceCheckpoints.remove(playerUuid);
            if (player != null && player.isOnline()) {
                mainPlugin.getHotbarManager().removePracticeBlocksFromInventory(player);
                mainPlugin.getHotbarManager().giveHotbarToPlayer(player);
                refreshStatboardForPlayer(player);
                // Teleport back to island's original/default spawn when disabling practice mode
                org.bukkit.Location defaultSpawn = mainPlugin.getPlayerManager().getResolvedRespawnLocation(playerUuid);
                // For practice mode disable, ensure we use island original spawn not temporary custom spawn
                // getResolvedRespawnLocation may return temporary; instead compute island spawn directly
                String arenaName = mainPlugin.getPlayerManager().getPlayerArena(playerUuid);
                if (arenaName != null) {
                    Arena arena = arenaManager.getArena(arenaName);
                    if (arena != null) {
                        ArenaIsland island = arena.findIslandByPlayer(playerUuid).orElse(null);
                        if (island != null) {
                            ArenaLocation spawnLocation = island.getSpawnLocation();
                org.bukkit.Location islandSpawn = new org.bukkit.Location(player.getWorld(), spawnLocation.getX(), spawnLocation.getY(), spawnLocation.getZ(), 0.0f, 0.0f);
                            try { player.teleport(islandSpawn); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
            return false;
        }

        practiceModePlayers.add(playerUuid);
        if (player != null && player.isOnline()) {
            mainPlugin.getHotbarManager().giveHotbarToPlayer(player);
            refreshStatboardForPlayer(player);
        }
        return true;
    }


    private void refreshStatboardForPlayer(Player player) {
        if (player == null) {
            return;
        }
        SkepiFBPlugin mainPlugin = (SkepiFBPlugin) plugin;
        UUID playerUuid = player.getUniqueId();
        String arenaName = playerManager.getPlayerArena(playerUuid);
        if (arenaName == null) {
            return;
        }
        Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) {
            return;
        }
        ArenaIsland island = arena.findIslandByPlayer(playerUuid).orElse(null);
        if (island == null) {
            return;
        }

        if (mainPlugin.getStatboardManager().hasStatboard(playerUuid)) {
            mainPlugin.getStatboardManager().updateStatboard(playerUuid);
        } else {
            mainPlugin.getStatboardManager().createStatboard(playerUuid, arena, island);
        }
    }

    public void playRemovalAnimation(AttemptSession session, Player player) {
        playRemovalAnimation(session, player, (java.util.List<org.bukkit.Location>) null, "other", null);
    }

    public void playRemovalAnimation(AttemptSession session, Player player, Runnable onComplete) {
        playRemovalAnimation(session, player, (java.util.List<org.bukkit.Location>) null, "other", onComplete);
    }

    public void playRemovalAnimation(AttemptSession session, Player player, java.util.List<org.bukkit.Location> trackedLocations, Runnable onComplete) {
        playRemovalAnimation(session, player, trackedLocations, "other", onComplete);
    }

    public void playRemovalAnimation(AttemptSession session, Player player, String reason, Runnable onComplete) {
        playRemovalAnimation(session, player, (java.util.List<org.bukkit.Location>) null, reason, onComplete);
    }

    public boolean isCleanupActive(UUID playerUuid) {
        return playerUuid != null && cleanupTasks.containsKey(playerUuid);
    }

    private boolean isCurrentSessionForCleanup(UUID playerUuid, AttemptSession session, UUID cleanupSessionId) {
        if (playerUuid == null || session == null) {
            return false;
        }
        AttemptSession currentSession = sessions.get(playerUuid);
        if (currentSession == null) {
            return false;
        }
        UUID currentAttemptId = currentSession.getAttemptId();
        UUID sessionAttemptId = session.getAttemptId();
        if (cleanupSessionId != null) {
            return cleanupSessionId.equals(currentAttemptId) && cleanupSessionId.equals(sessionAttemptId);
        }
        return currentSession == session && currentAttemptId != null && sessionAttemptId != null && currentAttemptId.equals(sessionAttemptId);
    }

    private void playRemovalAnimation(AttemptSession session, Player player, java.util.List<org.bukkit.Location> trackedLocations, String reason, Runnable onComplete) {
        if (session == null || player == null) {
            if (onComplete != null) {
                Bukkit.getScheduler().runTask(plugin, onComplete);
            }
            return;
        }
        me.skepi.skepifb.animations.AnimationsManager am = ((SkepiFBPlugin) plugin).getAnimationsManager();
        if (am == null) {
            if (onComplete != null) {
                Bukkit.getScheduler().runTask(plugin, onComplete);
            }
            return;
        }

        UUID playerUuid = player.getUniqueId();
        UUID cleanupSessionId = session != null ? session.getAttemptId() : null;
        if (!isCurrentSessionForCleanup(playerUuid, session, cleanupSessionId)) {
            if (onComplete != null) {
                Bukkit.getScheduler().runTask(plugin, onComplete);
            }
            return;
        }

        java.util.List<org.bukkit.Location> locations = trackedLocations;
        if (locations == null) {
            locations = session.getTrackedBlockLocations(player.getWorld());
        }

        // Snapshot tracked block locations before cancelling any active animation task.
        if (locations == null || locations.isEmpty()) {
            if (onComplete != null) {
                Bukkit.getScheduler().runTask(plugin, onComplete);
            }
            return;
        }

        cancelActiveCleanup(playerUuid, reason);
        cleanupSessionIds.put(playerUuid, cleanupSessionId);

        final java.util.List<CleanupAnimationEntry> entries = new java.util.ArrayList<>();
        final String animationName = am.getEquippedAnimation(playerUuid).map(String::trim).orElse("NONE");
        final String normalizedAnimationName = am.normalizeAnimationName(animationName);

        Object creativeNpc = null;
        if ("CREATIVE_MODE".equals(normalizedAnimationName)) {
            creativeNpc = am.spawnCreativeNpc(player, player.getLocation());
        }
        if (creativeNpc != null) {
            cleanupNpcs.put(playerUuid, creativeNpc);
        }

        // FALLING blocks fall 1 tick apart (5 tracked blocks = 5th one starts falling on tick 5).
        // Every other animation style keeps its existing, slightly wider stagger since a BlockDisplay
        // ghost is inert until its own animation starts, so a bigger gap there just means "sits still
        // a little longer before starting" rather than any visible side effect - unlike FallingBlock,
        // which is a REAL, physics-driven entity, so exactly when it spawns is exactly when it starts
        // dropping (see the FALLING branch of the setup loop below and advanceFallingEntry).
        int staggerTicksPerBlock = "FALLING".equals(normalizedAnimationName) ? 1 : 2;

        int trackedIndex = 0;
        for (org.bukkit.Location loc : locations) {
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            org.bukkit.block.Block b = loc.getWorld().getBlockAt(loc);
            org.bukkit.Material prev = b != null ? b.getType() : org.bukkit.Material.AIR;
            org.bukkit.block.data.BlockData previousBlockData = prev.createBlockData();
            boolean previousWasAir = prev == org.bukkit.Material.AIR;
            org.bukkit.Location ghostLocation = loc.clone();

            CleanupAnimationEntry entry = new CleanupAnimationEntry(ghostLocation, prev, previousBlockData, previousWasAir, trackedIndex * staggerTicksPerBlock);
            entries.add(entry);
            trackedIndex++;

            // Every non-air entry (including FALLING) gets a BlockDisplay showing the original
            // block, spawned immediately and in the exact same spot the real block was removed from
            // - this is what makes the transition from "real block" to "animation" seamless with no
            // visible gap, for every animation style including FALLING. For FALLING specifically,
            // this placeholder display sits motionless until this entry's staggered turn comes up
            // (advanceFallingEntry below), at which point it's swapped for a real FallingBlock entity
            // in the exact same position, which is what actually falls - a display can't fall with
            // real gravity, only a real Bukkit FallingBlock entity can.
            if (prev != org.bukkit.Material.AIR) {
                try {
                    BlockDisplay display = (BlockDisplay) entry.location.getWorld().spawnEntity(entry.location.clone(), EntityType.BLOCK_DISPLAY);
                    display.setBlock(prev.createBlockData());
                    entry.ghost = display;
                } catch (Throwable ignored) {
                }
            }
        }

        // cleanupEntries is populated now (immediately after the placeholder BlockDisplays above
        // were spawned) rather than after the real-block removal below, so a cancellation that
        // happens during the one-tick gap between "displays spawned" and "real blocks removed"
        // (see below) can still find and clean up those ghost entities.
        cleanupEntries.put(playerUuid, entries);

        final Object cleanupNpc = creativeNpc;

        // Removing the real blocks now happens a full tick AFTER the placeholder BlockDisplays
        // were spawned above, instead of in the same tick. Spawning an entity and changing a block
        // are different packet types (entity-add vs. block-change) and are not guaranteed to
        // render on the same client frame even when sent within the same server tick, which could
        // occasionally let the real block's removal be visible for a frame before its BlockDisplay
        // replacement had actually rendered - i.e. a brief flicker of empty air between "real
        // block" and "animation". Giving the display a full extra tick to arrive and render
        // client-side before the real block disappears closes that gap completely.
        BukkitTask startTask = Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isCurrentSessionForCleanup(playerUuid, session, cleanupSessionId)) {
                finishCleanup(playerUuid, onComplete, cleanupSessionId);
                return;
            }

            if (!entries.isEmpty()) {
                session.clearTrackedBlocks();
                for (CleanupAnimationEntry entry : entries) {
                    if (entry == null || entry.location == null || entry.location.getWorld() == null) {
                        continue;
                    }
                    // A new attempt may have started while these ghosts were being set up.
                    // If so, stop touching the world for this (now stale) cleanup entirely -
                    // a partial pass would otherwise mark later entries "removed" without
                    // ever having actually removed their real block, since the guard below
                    // would keep silently skipping them.
                    if (!isCurrentSessionForCleanup(playerUuid, session, cleanupSessionId)) {
                        break;
                    }
                    try {
                        org.bukkit.World w = entry.location.getWorld();
                        org.bukkit.block.Block block = w.getBlockAt(entry.location);
                        if (block != null && block.getType() != org.bukkit.Material.AIR) {
                            removeBlockAndUpdateNeighbors(block);
                        }
                        entry.blockRemoved = true;
                    } catch (Throwable ignored) {
                    }
                }
            }

        org.bukkit.scheduler.BukkitRunnable runner = new org.bukkit.scheduler.BukkitRunnable() {
            private int tickNumber = 0;

            @Override
            public void run() {
                try {
                    UUID activeCleanupSessionId = cleanupSessionIds.get(playerUuid);
                    AttemptSession currentSession = sessions.get(playerUuid);
                    UUID currentAttemptId = currentSession != null ? currentSession.getAttemptId() : null;
                    if (!Objects.equals(activeCleanupSessionId, currentAttemptId)) {
                        finishCleanup(playerUuid, onComplete, cleanupSessionId);
                        this.cancel();
                        return;
                    }

                    tickNumber++;
                    boolean allDone = true;
                    for (CleanupAnimationEntry entry : entries) {
                        if (entry == null) continue;

                        // If this entry has not reached its start tick yet, it is not started
                        if (tickNumber < entry.startTick + 1) {
                            allDone = false;
                            continue;
                        }

                        // THE ACTUAL BUG behind particles repeating over and over: this entry may
                        // have already finished its own animation on an earlier tick (ghost removed,
                        // animationComplete = true) while OTHER entries (staggered later) are still
                        // going - but without this check, the loop kept calling playAnimationStep
                        // for it again on every subsequent tick anyway (entry.ghost was just null by
                        // then), and several of the animation cases spawn their finishing particle
                        // burst unconditionally whenever their finishing phase number is reached -
                        // which it always was, forever, since phase kept incrementing past it. Once
                        // an entry is done, skip it entirely instead of re-processing it every tick
                        // until the whole batch finishes.
                        if (entry.animationComplete) {
                            continue;
                        }

                        if ("FALLING".equals(normalizedAnimationName)) {
                            if (!advanceFallingEntry(entry)) {
                                allDone = false;
                            }
                            continue;
                        }

                        // Play animation step for this entry
                        int animationPhase = entry.phase == 0 ? 1 : entry.phase;
                        boolean animationDone = am.playAnimationStep(player, entry.location, entry.ghost, entry.previousMaterial, normalizedAnimationName, animationPhase, cleanupNpc);
                        entry.phase = animationPhase + 1;
                        if (animationDone) {
                            entry.animationComplete = true;
                        }

                        // If there is still a ghost and it has completed, remove it
                        if (entry.ghost != null) {
                            if (entry.blockRemoved && entry.animationComplete) {
                                if (!entry.ghost.isDead()) {
                                    entry.ghost.remove();
                                }
                                entry.ghost = null;
                            } else {
                                allDone = false;
                            }
                        }
                    }

                    if (allDone) {
                        finishCleanup(playerUuid, onComplete, cleanupSessionId);
                        this.cancel();
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("TimerManager.playRemovalAnimation error: " + t.getMessage());
                    finishCleanup(playerUuid, onComplete, cleanupSessionId);
                    this.cancel();
                }
            }

            /**
             * Advances one FALLING-style entry by one tick and reports whether it is fully done.
             * Every tracked block starts out as a motionless BlockDisplay placeholder (spawned
             * immediately for every entry, up front - see the setup loop above), so nothing ever
             * visibly disappears and reappears out of nowhere. Exactly on this entry's staggered
             * turn, the placeholder is swapped for a real FallingBlock entity in the same spot, which
             * is what actually falls with real gravity - a display can't do that, only a real entity
             * can. Landing is detected via the EntityChangeBlockEvent listener
             * (onFallingResetBlockLand above), which cancels the real block placement (so it
             * disappears instead of becoming permanent) and marks the entry complete. A block that
             * falls MAX_FALL_DISTANCE without landing on anything (e.g. straight into open air/void)
             * is force-despawned here instead, so it never falls forever.
             */
            private static final double MAX_FALL_DISTANCE = 5.0;

            private boolean advanceFallingEntry(CleanupAnimationEntry entry) {
                if (entry.previousWasAir || entry.animationComplete) {
                    return true;
                }
                if (!entry.fallingSpawned) {
                    // This entry's stagger turn has arrived (the outer loop only calls this once
                    // tickNumber >= entry.startTick + 1) - swap its placeholder BlockDisplay for a
                    // real FallingBlock right now, so gravity starts acting on it at exactly this
                    // moment, not any earlier.
                    entry.fallingSpawned = true;
                    try {
                        if (entry.ghost != null) {
                            if (!entry.ghost.isDead()) {
                                entry.ghost.remove();
                            }
                            entry.ghost = null;
                        }
                        org.bukkit.World world = entry.location.getWorld();
                        org.bukkit.Location origin = entry.location.clone().add(0.5, 0.0, 0.5);
                        org.bukkit.entity.FallingBlock fallingBlock = world.spawnFallingBlock(origin, entry.previousBlockData);
                        fallingBlock.setDropItem(false);
                        fallingBlock.setHurtEntities(false);
                        fallingBlock.setCancelDrop(true);
                        entry.ghost = fallingBlock;
                        entry.fallStartY = origin.getY();
                        activeFallingBlockEntries.put(fallingBlock.getUniqueId(), entry);
                        world.spawnParticle(Particle.FALLING_DUST, origin, 8, 0.08, 0.04, 0.08, entry.previousBlockData);
                        world.spawnParticle(Particle.CLOUD, origin, 4, 0.06, 0.03, 0.06);
                    } catch (Throwable ignored) {
                        // Could not spawn (e.g. unloaded chunk) - don't get the whole cleanup stuck.
                        entry.animationComplete = true;
                        return true;
                    }
                    return false;
                }
                if (entry.ghost == null) {
                    // Failed to spawn (e.g. unloaded chunk) - don't get the whole cleanup stuck on it.
                    entry.animationComplete = true;
                    return true;
                }
                if (entry.ghost.isDead()) {
                    // Landed naturally: the EntityChangeBlockEvent listener already cancelled the
                    // placement, removed the entity, played landing effects, and marked this entry
                    // complete.
                    activeFallingBlockEntries.remove(entry.ghost.getUniqueId());
                    entry.animationComplete = true;
                    entry.ghost = null;
                    return true;
                }

                double fallenSoFar = entry.fallStartY - entry.ghost.getLocation().getY();
                if (fallenSoFar >= MAX_FALL_DISTANCE) {
                    org.bukkit.Location capLocation = entry.ghost.getLocation();
                    org.bukkit.World world = capLocation.getWorld();
                    activeFallingBlockEntries.remove(entry.ghost.getUniqueId());
                    try {
                        entry.ghost.remove();
                    } catch (Throwable ignored) {
                    }
                    if (world != null) {
                        world.spawnParticle(Particle.FALLING_DUST, capLocation, 10, 0.1, 0.05, 0.1, entry.previousBlockData);
                        world.spawnParticle(Particle.CLOUD, capLocation, 5, 0.08, 0.04, 0.08);
                        world.playSound(capLocation, Sound.BLOCK_STONE_BREAK, 0.6f, 1.0f);
                    }
                    entry.ghost = null;
                    entry.animationComplete = true;
                    return true;
                }

                // Still falling naturally - not done yet.
                return false;
            }
        };
            BukkitTask task = runner.runTaskTimer(plugin, 1L, 1L);
            cleanupTasks.put(playerUuid, task);
        });
        cleanupTasks.put(playerUuid, startTask);
    }

    private void finishCleanup(UUID playerUuid, Runnable onComplete, UUID cleanupSessionId) {
        cleanupTasks.remove(playerUuid);
        cleanupSessionIds.remove(playerUuid);
        java.util.List<CleanupAnimationEntry> entries = cleanupEntries.remove(playerUuid);
        if (entries != null) {
            for (CleanupAnimationEntry entry : entries) {
                if (entry != null) {
                    if (entry.ghost != null) {
                        activeFallingBlockEntries.remove(entry.ghost.getUniqueId());
                        if (!entry.ghost.isDead()) {
                            entry.ghost.remove();
                        }
                    }
                    // NOTE: deliberately NOT touching whatever real block is sitting at the ghost's
                    // final (landing) location here. The falling animation is purely visual - the
                    // real tracked block was already removed from its ORIGINAL position back when
                    // cleanup started, and the ghost never places anything solid at the spot it
                    // lands on. Removing "whatever is there" used to also strip out pre-existing,
                    // non-full arena geometry the ghost happened to land on/near (e.g. a slab),
                    // since a block's data can coincidentally match the falling block's original
                    // data without actually being the same block. Landing should only ever be a
                    // visual cue, never a world edit.
                }
            }
        }
        Object cleanupNpc = cleanupNpcs.remove(playerUuid);
        if (cleanupNpc != null) {
            try {
                cleanupNpc.getClass().getMethod("destroy").invoke(cleanupNpc);
            } catch (Throwable ignored) {}
        }
        if (onComplete != null) {
            try {
                Bukkit.getScheduler().runTask(plugin, onComplete);
            } catch (Throwable ignored) {}
        }
    }

    public void cancelActiveCleanup(UUID playerUuid, String reason) {
        cleanupSessionIds.remove(playerUuid);
        BukkitTask oldTask = cleanupTasks.remove(playerUuid);
        if (oldTask != null) {
            oldTask.cancel();
        }
        java.util.List<CleanupAnimationEntry> oldEntries = cleanupEntries.remove(playerUuid);
        if (oldEntries != null) {
            for (CleanupAnimationEntry entry : oldEntries) {
                if (entry == null) {
                    continue;
                }
                if (entry.ghost != null) {
                    activeFallingBlockEntries.remove(entry.ghost.getUniqueId());
                    if (!entry.ghost.isDead()) {
                        entry.ghost.remove();
                    }
                }
            }
        }
        Object oldNpc = cleanupNpcs.remove(playerUuid);
        if (oldNpc != null) {
            try {
                oldNpc.getClass().getMethod("destroy").invoke(oldNpc);
            } catch (Throwable ignored) {}
        }
        // Keep current tracked blocks intact here; the cleanup task is only responsible for animation visuals.
    }

    private void removeBlockAndUpdateNeighbors(org.bukkit.block.Block block) {
        if (block == null) {
            return;
        }
        org.bukkit.World world = block.getWorld();
        if (world == null) {
            return;
        }
        try {
            block.setType(org.bukkit.Material.AIR, true);
        } catch (Throwable ignored) {
            try {
                block.setType(org.bukkit.Material.AIR);
            } catch (Throwable ignored2) {
            }
        }
        try {
            org.bukkit.Location loc = block.getLocation();
            for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN}) {
                org.bukkit.block.Block neighbor = loc.getBlock().getRelative(face);
                if (neighbor != null) {
                    neighbor.getState().update(true, true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void setPracticeMode(UUID playerUuid, boolean enabled) {
        if (playerUuid == null) {
            return;
        }
        if (enabled) {
            practiceModePlayers.add(playerUuid);
        } else {
            practiceModePlayers.remove(playerUuid);
        }
    }

    public void trackPracticeBlock(UUID playerUuid, org.bukkit.Location location) {
        if (playerUuid == null || location == null || location.getWorld() == null) {
            return;
        }
        java.util.Set<org.bukkit.Location> blocks = practiceBlocks.computeIfAbsent(playerUuid, k -> new java.util.HashSet<>());
        blocks.add(location.clone());
    }

    public boolean isPracticeBlock(UUID playerUuid, org.bukkit.Location location) {
        if (playerUuid == null || location == null) {
            return false;
        }
        java.util.Set<org.bukkit.Location> blocks = practiceBlocks.get(playerUuid);
        return blocks != null && blocks.contains(location);
    }

    public void removePracticeBlock(UUID playerUuid, org.bukkit.Location location) {
        if (playerUuid == null || location == null) {
            return;
        }
        java.util.Set<org.bukkit.Location> blocks = practiceBlocks.get(playerUuid);
        if (blocks != null) {
            blocks.remove(location);
            if (blocks.isEmpty()) {
                practiceBlocks.remove(playerUuid);
            }
        }
    }

    public void removePracticeBlocks(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        java.util.Set<org.bukkit.Location> blocks = practiceBlocks.remove(playerUuid);
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (org.bukkit.Location loc : new java.util.ArrayList<>(blocks)) {
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            try {
                removeBlockAndUpdateNeighbors(loc.getWorld().getBlockAt(loc));
            } catch (Throwable ignored) {
            }
        }
    }

    public void clearPracticeBlocks(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        practiceBlocks.remove(playerUuid);
    }

    /**
     * Snapshot of every block currently tracked as a practice block for this player (copy - safe
     * to iterate/mutate without affecting live state).
     */
    public java.util.Set<org.bukkit.Location> getPracticeBlockLocations(UUID playerUuid) {
        if (playerUuid == null) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<org.bukkit.Location> blocks = practiceBlocks.get(playerUuid);
        return blocks == null ? java.util.Collections.emptySet() : new java.util.LinkedHashSet<>(blocks);
    }

    /**
     * The floored block-integer origin (floor(spawnX), spawnY, floor(spawnZ)) of the island the
     * player is currently assigned to within their current arena/mode - the same origin
     * convention used for practice-block template offsets, spawn template offsets, and (as of
     * the relative-coordinate fix) the replay hologram's relative coordinates. Returns null if
     * the player isn't currently on an island (not in an arena, or their island couldn't be
     * resolved).
     */
    public org.bukkit.Location getSpawnBlockOrigin(Player player) {
        if (player == null) {
            return null;
        }
        String arenaName = playerManager.getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            return null;
        }
        Arena arena = arenaManager.getArena(arenaName);
        if (arena == null) {
            return null;
        }
        ArenaIsland island = arena.findIslandByPlayer(player.getUniqueId()).orElse(null);
        if (island == null) {
            return null;
        }
        ArenaLocation spawn = island.getSpawnLocation();
        if (spawn == null) {
            return null;
        }
        return new org.bukkit.Location(player.getWorld(), Math.floor(spawn.getX()), spawn.getY(), Math.floor(spawn.getZ()));
    }

    /**
     * Captures the player's currently-placed practice blocks as offsets relative to their
     * current island's spawn origin, for saving into a {@link me.skepi.skepifb.templates.PracticeTemplate}.
     * Returns an empty list if the player has no island resolved or no practice blocks placed.
     */
    public List<me.skepi.skepifb.templates.PracticeTemplate.BlockEntry> capturePracticeBlocksRelative(Player player) {
        List<me.skepi.skepifb.templates.PracticeTemplate.BlockEntry> result = new ArrayList<>();
        org.bukkit.Location origin = getSpawnBlockOrigin(player);
        if (origin == null) {
            return result;
        }
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();
        for (org.bukkit.Location loc : getPracticeBlockLocations(player.getUniqueId())) {
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            org.bukkit.Material mat;
            try {
                mat = loc.getBlock().getType();
            } catch (Throwable t) {
                mat = org.bukkit.Material.STONE;
            }
            if (mat == org.bukkit.Material.AIR) {
                continue;
            }
            int dx = loc.getBlockX() - baseX;
            int dy = loc.getBlockY() - baseY;
            int dz = loc.getBlockZ() - baseZ;
            result.add(new me.skepi.skepifb.templates.PracticeTemplate.BlockEntry(dx, dy, dz, mat.name()));
        }
        return result;
    }

    /**
     * Loads a saved practice-block template for the player: turns practice mode on if it isn't
     * already (if it IS already on, every currently-placed practice block is removed first so
     * only the template's blocks remain), then places every block in the template at its
     * position relative to the player's current island spawn and tracks each one as a practice
     * block - so they behave identically to manually placed ones (removable, cleared when
     * practice mode is turned back off, etc).
     */
    public boolean applyPracticeTemplate(Player player, me.skepi.skepifb.templates.PracticeTemplate template) {
        if (player == null || template == null || template.size() == 0) {
            return false;
        }
        org.bukkit.Location origin = getSpawnBlockOrigin(player);
        if (origin == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (isPracticeMode(uuid)) {
            removePracticeBlocks(uuid);
        } else {
            practiceModePlayers.add(uuid);
            hotbarManager.giveHotbarToPlayer(player);
            refreshStatboardForPlayer(player);
        }
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();
        for (me.skepi.skepifb.templates.PracticeTemplate.BlockEntry entry : template.getBlocks()) {
            org.bukkit.Location loc = new org.bukkit.Location(player.getWorld(), baseX + entry.dx, baseY + entry.dy, baseZ + entry.dz);
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(entry.material);
            if (material == null) {
                material = org.bukkit.Material.STONE;
            }
            try {
                loc.getBlock().setType(material, true);
            } catch (Throwable ignored) {
            }
            trackPracticeBlock(uuid, loc);
        }
        return true;
    }

    /**
     * Captures the player's current position/facing as offsets relative to their current
     * island's spawn origin, for saving into a {@link me.skepi.skepifb.templates.SpawnTemplate}.
     * Returns null if the player has no island resolved.
     */
    public me.skepi.skepifb.templates.SpawnTemplate captureSpawnRelative(Player player) {
        org.bukkit.Location origin = getSpawnBlockOrigin(player);
        if (origin == null) {
            return null;
        }
        org.bukkit.Location loc = player.getLocation();
        double dx = loc.getX() - origin.getBlockX();
        double dy = loc.getY() - origin.getBlockY();
        double dz = loc.getZ() - origin.getBlockZ();
        return new me.skepi.skepifb.templates.SpawnTemplate(dx, dy, dz, loc.getYaw(), loc.getPitch());
    }

    /**
     * Loads a saved spawn template: resolves it against the player's current island spawn
     * origin and sets it as their temporary spawn (same mechanism as the existing manual
     * "set spawn" action), then teleports them there immediately.
     */
    public boolean applySpawnTemplate(Player player, me.skepi.skepifb.templates.SpawnTemplate template) {
        if (player == null || template == null) {
            return false;
        }
        org.bukkit.Location origin = getSpawnBlockOrigin(player);
        if (origin == null) {
            return false;
        }
        org.bukkit.Location target = new org.bukkit.Location(
                player.getWorld(),
                origin.getBlockX() + template.dx,
                origin.getBlockY() + template.dy,
                origin.getBlockZ() + template.dz,
                template.yaw,
                template.pitch);
        playerManager.setTemporarySpawn(player.getUniqueId(), target);
        try {
            player.teleport(target);
        } catch (Throwable ignored) {
        }
        return true;
    }

    public void ensureSession(UUID playerUuid) {
        getOrCreateSession(playerUuid);
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            onPlayerXpChanged(playerUuid);
        }
    }

    public int getLevelForXp(int xp) {
        return RankLevel.fromXp(xp).getLevel();
    }

    public int getNextLevelXpForXp(int xp) {
        RankLevel rank = RankLevel.fromXp(xp);
        if (rank.getLevel() >= 7) {
            return rank.getMinXp();
        }
        return RankLevel.fromLevel(rank.getLevel() + 1).getMinXp();
    }

    public int getBossbarPercentForXp(int xp) {
        RankLevel rank = RankLevel.fromXp(xp);
        if (rank.getLevel() >= 7) {
            return 100;
        }
        int xpIntoLevel = xp - rank.getMinXp();
        int nextLevelXp = getNextLevelXpForXp(xp);
        int xpForLevel = nextLevelXp - rank.getMinXp();
        if (xpForLevel <= 0) {
            return 0;
        }
        int percent = (int) ((xpIntoLevel * 100.0) / xpForLevel);
        return Math.max(0, Math.min(100, percent));
    }

    public void pausePlayerSession(UUID playerUuid) {
        AttemptSession session = sessions.get(playerUuid);
        if (session == null) {
            return;
        }
        session.stopAttempt();
    }
}
