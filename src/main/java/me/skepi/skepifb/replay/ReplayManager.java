package me.skepi.skepifb.replay;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.replay.ReplayFrame;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReplayManager {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SkepiFBPlugin plugin;
    private final Path replayRoot;
    private final Map<String, Integer> replayCounters = new HashMap<>();
    private final Map<UUID, Path> replayFileIndex = new HashMap<>();
    private volatile boolean replayIndexFullyBuilt = false;

    public ReplayManager(SkepiFBPlugin plugin) {
        this.plugin = plugin;
        this.replayRoot = plugin.getDataFolder().toPath().resolve("replays");
        try {
            Files.createDirectories(replayRoot);
        } catch (IOException ignored) {
        }
        loadReplayCounters();
    }

    public Path getReplayRoot() {
        return replayRoot;
    }

    public ReplayMetadata recordReplay(UUID playerUuid, String arenaName, String playerName, double durationSeconds, int blocksPlaced, List<ReplayFrame> frames, boolean wasPersonalBest) {
        return recordReplay(playerUuid, arenaName, playerName, durationSeconds, blocksPlaced, frames, wasPersonalBest, null);
    }

    /**
     * Same as the 7-arg overload, but also prunes this player/arena's replays down to their
     * resolved replay-count tier (permissions.yml) immediately afterward - the favorited replay
     * and the current personal-best replay, if any, are never auto-deleted by this, only ordinary
     * ones beyond the cap, oldest first. Pass the online Player so the permission check has
     * someone to check against; pass null (see the other overload) to skip pruning entirely
     * (e.g. if the caller doesn't have a live Player reference).
     */
    public ReplayMetadata recordReplay(UUID playerUuid, String arenaName, String playerName, double durationSeconds, int blocksPlaced, List<ReplayFrame> frames, boolean wasPersonalBest, org.bukkit.entity.Player player) {
        UUID replayId = UUID.randomUUID();
        int replayIndex = getNextReplayIndex(playerUuid, arenaName);
        long timestamp = Instant.now().toEpochMilli();
        ReplayMetadata metadata = new ReplayMetadata(replayId, arenaName, timestamp, durationSeconds, blocksPlaced, playerUuid, playerName, replayIndex, wasPersonalBest);
        saveReplay(playerUuid, metadata, frames);
        if (player != null) {
            pruneReplaysToTierLimit(player, playerUuid, arenaName);
        }
        return metadata;
    }

    /**
     * Deletes the oldest replays for this player/arena beyond their resolved replay-count tier
     * limit. Never deletes the currently-favorited replay or the current personal-best replay,
     * even if they happen to be among the oldest - those are exactly the two a player is least
     * likely to want auto-deleted.
     */
    private void pruneReplaysToTierLimit(org.bukkit.entity.Player player, UUID playerUuid, String arenaName) {
        try {
            int limit = plugin.getPermissionsManager().resolveReplayTier(player).replayCount;
            List<ReplayMetadata> replays = listReplayMetadata(playerUuid, arenaName);
            if (replays.size() <= limit) {
                return;
            }
            UUID favoriteId = getFavoriteReplay(playerUuid, arenaName).map(ReplayMetadata::getReplayId).orElse(null);
            UUID pbId = getCurrentPersonalBestReplay(playerUuid, arenaName).map(ReplayMetadata::getReplayId).orElse(null);
            replays.sort(Comparator.comparingInt(ReplayMetadata::getReplayIndex));
            int toDelete = replays.size() - limit;
            for (ReplayMetadata metadata : replays) {
                if (toDelete <= 0) {
                    break;
                }
                if (metadata.getReplayId().equals(favoriteId) || metadata.getReplayId().equals(pbId)) {
                    continue;
                }
                deleteReplay(playerUuid, arenaName, metadata.getReplayIndex());
                toDelete--;
            }
        } catch (Throwable ignored) {
        }
    }

    private int getNextReplayIndex(UUID playerUuid, String arenaName) {
        String key = getCounterKey(playerUuid, arenaName);
        int next = replayCounters.getOrDefault(key, 0) + 1;
        replayCounters.put(key, next);
        return next;
    }

    private String getCounterKey(UUID playerUuid, String arenaName) {
        return playerUuid.toString() + "|" + arenaName;
    }

    private void loadReplayCounters() {
        try {
            if (!Files.exists(replayRoot) || !Files.isDirectory(replayRoot)) {
                return;
            }
            Files.list(replayRoot).filter(Files::isDirectory).forEach(playerPath -> {
                String playerUuid = playerPath.getFileName().toString();
                try {
                    Files.list(playerPath).filter(Files::isDirectory).forEach(arenaPath -> {
                        String arenaName = arenaPath.getFileName().toString();
                        AtomicInteger maxIndex = new AtomicInteger(0);
                        try {
                            Files.list(arenaPath).forEach(filePath -> {
                                String fileName = filePath.getFileName().toString();
                                if (!fileName.startsWith("replay_") || !fileName.endsWith(".yml")) {
                                    return;
                                }
                                String indexText = fileName.substring("replay_".length(), fileName.length() - ".yml".length());
                                try {
                                    int index = Integer.parseInt(indexText);
                                    maxIndex.updateAndGet(current -> Math.max(current, index));
                                } catch (NumberFormatException ignored) {
                                }
                            });
                        } catch (IOException ignored) {
                        }
                        replayCounters.put(playerUuid + "|" + arenaName, maxIndex.get());
                    });
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void saveReplay(UUID playerUuid, ReplayMetadata metadata, List<ReplayFrame> frames) {
        Path arenaFolder = replayRoot.resolve(playerUuid.toString()).resolve(metadata.getArenaName());
        try {
            Files.createDirectories(arenaFolder);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create replay folder for arena " + metadata.getArenaName() + ": " + e.getMessage());
            return;
        }
        String filename = String.format("replay_%06d.yml", metadata.getReplayIndex());
        Path replayFile = arenaFolder.resolve(filename);
        try (Writer writer = Files.newBufferedWriter(replayFile, StandardCharsets.UTF_8)) {
            writeYamlReplay(writer, metadata, frames);
            replayFileIndex.put(metadata.getReplayId(), replayFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save replay file " + replayFile + ": " + e.getMessage());
        }
    }

    private void writeYamlReplay(Writer writer, ReplayMetadata metadata, List<ReplayFrame> frames) throws IOException {
        writer.write("replayId: " + metadata.getReplayId().toString() + "\n");
        writer.write("arena: " + quote(metadata.getArenaName()) + "\n");
        writer.write("playerName: " + quote(metadata.getPlayerName()) + "\n");
        writer.write("playerUuid: " + metadata.getPlayerUuid().toString() + "\n");
        writer.write("timestamp: " + metadata.getTimestamp() + "\n");
        writer.write("durationSeconds: " + String.format(Locale.ROOT, "%.3f", metadata.getDurationSeconds()) + "\n");
        writer.write("blocksPlaced: " + metadata.getBlocksPlaced() + "\n");
        writer.write("replayIndex: " + metadata.getReplayIndex() + "\n");
        writer.write("wasPersonalBest: " + metadata.isWasPersonalBest() + "\n");
        // Determine if we can write coordinates relative to the island spawn used for this recording
        boolean writeRelative = false;
        double originX = 0.0, originY = 0.0, originZ = 0.0;
        try {
            me.skepi.skepifb.arena.Arena arena = plugin.getArenaManager().getArena(metadata.getArenaName());
            if (arena != null) {
                java.util.Optional<me.skepi.skepifb.arena.ArenaIsland> islandOpt = arena.findIslandByPlayer(metadata.getPlayerUuid());
                if (islandOpt.isPresent()) {
                    me.skepi.skepifb.arena.ArenaLocation spawn = islandOpt.get().getSpawnLocation();
                    originX = spawn.getX();
                    originY = spawn.getY();
                    originZ = spawn.getZ();
                    writeRelative = true;
                }
            }
        } catch (Throwable ignored) {}
        writer.write("coordinateMode: " + (writeRelative ? "relative" : "absolute") + "\n");
        writer.write("frames:\n");
        for (ReplayFrame frame : frames) {
            writer.write("  - tick: " + frame.getTick() + "\n");
            double outX = frame.getX();
            double outY = frame.getY();
            double outZ = frame.getZ();
            if (writeRelative) {
                // Player-position relative coordinates must be computed against the same
                // floored block origin the block-placement/break relative coordinates below
                // already use (Math.floor(originX/originZ)) - NOT the raw spawn.getX()/getZ(),
                // which is offset by +0.5 (islands spawn at block-center, see
                // Arena#calculateSpawn). Subtracting the un-floored, center-offset origin here
                // shifted every recorded X/Z decimal by 0.5 versus the player's real fractional
                // position (Y was never affected since island spawn Y has no +0.5 offset), which
                // is the ".500 off" relative-coordinate bug on the replay hologram. Flooring
                // originX/originZ here (matching the placements/breaks below) keeps the decimal
                // portion of X/Z identical to the player's real position, only shifting the
                // whole-number part, exactly like Y already behaved.
                outX = frame.getX() - Math.floor(originX);
                outY = frame.getY() - originY;
                outZ = frame.getZ() - Math.floor(originZ);
                frame.setCoordinatesRelative(true);
            }
            writer.write("    x: " + String.format(Locale.ROOT, "%.3f", outX) + "\n");
            writer.write("    y: " + String.format(Locale.ROOT, "%.3f", outY) + "\n");
            writer.write("    z: " + String.format(Locale.ROOT, "%.3f", outZ) + "\n");
            writer.write("    yaw: " + String.format(Locale.ROOT, "%.3f", frame.getYaw()) + "\n");
            writer.write("    pitch: " + String.format(Locale.ROOT, "%.3f", frame.getPitch()) + "\n");
            writer.write("    sneaking: " + frame.isSneaking() + "\n");
            writer.write("    sprinting: " + frame.isSprinting() + "\n");
            writer.write("    ping: " + frame.getPing() + "\n");
            writer.write("    leftCps: " + frame.getLeftCps() + "\n");
            writer.write("    rightCps: " + frame.getRightCps() + "\n");
            writer.write("    jumpTicks: " + frame.getJumpTicks() + "\n");
            writer.write("    heldMaterial: " + quote(frame.getHeldMaterial()) + "\n");
            writer.write("    armSwings:\n");
            for (String swing : frame.getArmSwings()) {
                writer.write("      - " + quote(swing) + "\n");
            }
            writer.write("    placements:\n");
            for (ReplayBlockEvent placement : frame.getPlacements()) {
                int pX = placement.getX();
                int pY = placement.getY();
                int pZ = placement.getZ();
                if (writeRelative) {
                    // compute offset relative to integer spawn block coords
                    pX = placement.getX() - (int) Math.floor(originX);
                    pY = placement.getY() - (int) Math.floor(originY);
                    pZ = placement.getZ() - (int) Math.floor(originZ);
                }
                writer.write("      - world: " + quote(placement.getWorld()) + "\n");
                writer.write("        x: " + pX + "\n");
                writer.write("        y: " + pY + "\n");
                writer.write("        z: " + pZ + "\n");
                writer.write("        material: " + quote(placement.getMaterial()) + "\n");
            }
            writer.write("    breaks:\n");
            for (ReplayBlockEvent breakEvent : frame.getBreaks()) {
                int bX = breakEvent.getX();
                int bY = breakEvent.getY();
                int bZ = breakEvent.getZ();
                if (writeRelative) {
                    bX = breakEvent.getX() - (int) Math.floor(originX);
                    bY = breakEvent.getY() - (int) Math.floor(originY);
                    bZ = breakEvent.getZ() - (int) Math.floor(originZ);
                }
                writer.write("      - world: " + quote(breakEvent.getWorld()) + "\n");
                writer.write("        x: " + bX + "\n");
                writer.write("        y: " + bY + "\n");
                writer.write("        z: " + bZ + "\n");
                writer.write("        material: " + quote(breakEvent.getMaterial()) + "\n");
            }
        }
    }

    private String quote(String text) {
        if (text == null) {
            return "''";
        }
        return "'" + text.replace("'", "''") + "'";
    }

    public List<ReplayMetadata> listReplayMetadata(UUID playerUuid, String arenaName) {
        Path arenaFolder = replayRoot.resolve(playerUuid.toString()).resolve(arenaName);
        if (!Files.exists(arenaFolder) || !Files.isDirectory(arenaFolder)) {
            return Collections.emptyList();
        }
        List<ReplayMetadata> metadataList = new ArrayList<>();
        try {
            Files.list(arenaFolder).forEach(filePath -> {
                if (!Files.isRegularFile(filePath) || !filePath.getFileName().toString().endsWith(".yml")) {
                    return;
                }
                if (filePath.getFileName().toString().equalsIgnoreCase("failed_replay.yml")) {
                    return;
                }
                Optional<ReplayMetadata> metadata = readMetadataFromFile(filePath);
                metadata.ifPresent(metadataList::add);
            });
        } catch (IOException ignored) {
        }
        metadataList.sort(Comparator.comparingInt(ReplayMetadata::getReplayIndex));
        return metadataList;
    }

    public Optional<ReplayMetadata> getFailedReplayMetadata(UUID playerUuid, String arenaName) {
        Path failedFile = replayRoot.resolve(playerUuid.toString()).resolve(arenaName).resolve("failed_replay.yml");
        if (!Files.exists(failedFile) || !Files.isRegularFile(failedFile)) {
            return Optional.empty();
        }
        return readMetadataFromFile(failedFile);
    }

    // ------------------------------------------------------------------
    // Favorite replay (per player, per arena). Stored as a single small
    // "favorites.yml" directly under the player's replay folder (NOT inside
    // an arena subfolder) so it is never picked up by listReplayMetadata's
    // per-arena directory scan, which otherwise treats every ".yml" file in
    // an arena folder as a candidate replay file. We persist only the
    // replay's identifier - not a copy of the replay itself - and resolve
    // it back to a full ReplayMetadata on demand from the normal replay list.
    // ------------------------------------------------------------------

    private Path getFavoritesFile(UUID playerUuid) {
        return replayRoot.resolve(playerUuid.toString()).resolve("favorites.yml");
    }

    private Map<String, String> readFavoritesMap(UUID playerUuid) {
        Map<String, String> map = new HashMap<>();
        Path file = getFavoritesFile(playerUuid);
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = unquote(line.substring(0, idx).trim());
                String value = unquote(line.substring(idx + 1).trim());
                if (!key.isEmpty() && !value.isEmpty()) {
                    map.put(key, value);
                }
            }
        } catch (IOException ignored) {
        }
        return map;
    }

    private void writeFavoritesMap(UUID playerUuid, Map<String, String> map) {
        Path file = getFavoritesFile(playerUuid);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create favorites folder for " + playerUuid + ": " + e.getMessage());
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                writer.write(quote(entry.getKey()) + ": " + quote(entry.getValue()) + "\n");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save favorites file for " + playerUuid + ": " + e.getMessage());
        }
    }

    /**
     * Returns the player's favorited replay for this arena, or empty if they don't have one, or if
     * their favorited replay was since deleted (in which case the stale favorite reference is
     * cleared automatically so it doesn't linger as a broken entry).
     */
    public Optional<ReplayMetadata> getFavoriteReplay(UUID playerUuid, String arenaName) {
        if (playerUuid == null || arenaName == null) {
            return Optional.empty();
        }
        Map<String, String> favorites = readFavoritesMap(playerUuid);
        String replayIdText = favorites.get(arenaName);
        if (replayIdText == null) {
            return Optional.empty();
        }
        UUID replayId;
        try {
            replayId = UUID.fromString(replayIdText);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        for (ReplayMetadata metadata : listReplayMetadata(playerUuid, arenaName)) {
            if (replayId.equals(metadata.getReplayId())) {
                return Optional.of(metadata);
            }
        }
        // The favorited replay no longer exists (deleted) - clear the stale reference.
        favorites.remove(arenaName);
        writeFavoritesMap(playerUuid, favorites);
        return Optional.empty();
    }

    public boolean isFavoriteReplay(UUID playerUuid, String arenaName, UUID replayId) {
        if (playerUuid == null || arenaName == null || replayId == null) {
            return false;
        }
        String stored = readFavoritesMap(playerUuid).get(arenaName);
        return stored != null && stored.equalsIgnoreCase(replayId.toString());
    }

    /**
     * Toggles the given replay's favorite status for this player/arena: favorites it if it wasn't
     * already the favorite, or clears the favorite if it was. Returns true if the replay is now
     * favorited, false if it was just un-favorited.
     */
    public boolean toggleFavoriteReplay(UUID playerUuid, String arenaName, UUID replayId) {
        Map<String, String> favorites = readFavoritesMap(playerUuid);
        String current = favorites.get(arenaName);
        boolean nowFavorited;
        if (current != null && current.equalsIgnoreCase(replayId.toString())) {
            favorites.remove(arenaName);
            nowFavorited = false;
        } else {
            favorites.put(arenaName, replayId.toString());
            nowFavorited = true;
        }
        writeFavoritesMap(playerUuid, favorites);
        return nowFavorited;
    }

    /**
     * Resolves the player's current Personal Best replay for this arena using the existing PB
     * relationship (a replay's persisted wasPersonalBest flag plus the live PB time from
     * PlayerStatsManager) rather than a separate/duplicated PB tracking system.
     */
    public Optional<ReplayMetadata> getCurrentPersonalBestReplay(UUID playerUuid, String arenaName) {
        if (playerUuid == null || arenaName == null) {
            return Optional.empty();
        }
        double personalBest = plugin.getStatsManager().getPersonalBest(playerUuid, arenaName);
        if (personalBest <= 0) {
            return Optional.empty();
        }
        ReplayMetadata best = null;
        for (ReplayMetadata metadata : listReplayMetadata(playerUuid, arenaName)) {
            if (!metadata.isWasPersonalBest()) {
                continue;
            }
            if (Math.abs(metadata.getDurationSeconds() - personalBest) < 0.0005) {
                // Prefer the most recently recorded matching replay in the (rare) case of an exact tie.
                if (best == null || metadata.getReplayIndex() > best.getReplayIndex()) {
                    best = metadata;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Returns the most recently recorded replay (highest replayIndex) for a player/arena, used by
     * the "Last Attempt" shortcut slot in the replay menu.
     */
    public Optional<ReplayMetadata> getMostRecentReplay(UUID playerUuid, String arenaName) {
        if (playerUuid == null || arenaName == null) {
            return Optional.empty();
        }
        ReplayMetadata mostRecent = null;
        for (ReplayMetadata metadata : listReplayMetadata(playerUuid, arenaName)) {
            if (mostRecent == null || metadata.getReplayIndex() > mostRecent.getReplayIndex()) {
                mostRecent = metadata;
            }
        }
        return Optional.ofNullable(mostRecent);
    }

    public double getAverageArenaCompletionTime(String arenaName) {
        java.util.List<ReplayMetadata> replays = listArenaReplayMetadata(arenaName);
        if (replays.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (ReplayMetadata metadata : replays) {
            total += metadata.getDurationSeconds();
        }
        return total / replays.size();
    }

    public int getArenaCompletionCount(String arenaName) {
        return listArenaReplayMetadata(arenaName).size();
    }

    private java.util.List<ReplayMetadata> listArenaReplayMetadata(String arenaName) {
        if (arenaName == null) {
            return java.util.List.of();
        }
        java.util.List<ReplayMetadata> entries = new java.util.ArrayList<>();
        try {
            if (!Files.exists(replayRoot) || !Files.isDirectory(replayRoot)) {
                return java.util.List.of();
            }
            Files.list(replayRoot).filter(Files::isDirectory).forEach(playerPath -> {
                try {
                    Files.list(playerPath).filter(Files::isRegularFile).forEach(filePath -> {
                        String fileName = filePath.getFileName().toString();
                        if (fileName.equalsIgnoreCase("failed_replay.yml") || !fileName.endsWith(".yml")) {
                            return;
                        }
                        Optional<ReplayMetadata> metadata = readMetadataFromFile(filePath);
                        metadata.ifPresent(entry -> {
                            if (arenaName.equals(entry.getArenaName())) {
                                entries.add(entry);
                            }
                        });
                    });
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return entries;
    }

    public void saveFailedReplay(UUID playerUuid, String arenaName, String playerName, double durationSeconds, int blocksPlaced, List<ReplayFrame> frames) {
        UUID replayId = UUID.randomUUID();
        long timestamp = Instant.now().toEpochMilli();
        ReplayMetadata metadata = new ReplayMetadata(replayId, arenaName, timestamp, durationSeconds, blocksPlaced, playerUuid, playerName, 0);
        Path arenaFolder = replayRoot.resolve(playerUuid.toString()).resolve(arenaName);
        try {
            Files.createDirectories(arenaFolder);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create replay folder for arena " + arenaName + ": " + e.getMessage());
            return;
        }
        Path failedFile = arenaFolder.resolve("failed_replay.yml");
        try (Writer writer = Files.newBufferedWriter(failedFile, StandardCharsets.UTF_8)) {
            writeYamlReplay(writer, metadata, frames);
            replayFileIndex.put(metadata.getReplayId(), failedFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save failed replay file " + failedFile + ": " + e.getMessage());
        }
    }

    private Optional<ReplayMetadata> readMetadataFromFile(Path filePath) {
        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            UUID replayId = null;
            String arenaName = null;
            String playerName = null;
            UUID playerUuid = null;
            long timestamp = 0L;
            double durationSeconds = 0.0;
            int blocksPlaced = 0;
            int replayIndex = 0;
            boolean wasPersonalBest = false;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("replayId:")) {
                    replayId = UUID.fromString(line.substring("replayId:".length()).trim());
                } else if (line.startsWith("arena:")) {
                    arenaName = unquote(line.substring("arena:".length()).trim());
                } else if (line.startsWith("playerName:")) {
                    playerName = unquote(line.substring("playerName:".length()).trim());
                } else if (line.startsWith("playerUuid:")) {
                    playerUuid = UUID.fromString(line.substring("playerUuid:".length()).trim());
                } else if (line.startsWith("timestamp:")) {
                    timestamp = Long.parseLong(line.substring("timestamp:".length()).trim());
                } else if (line.startsWith("durationSeconds:")) {
                    durationSeconds = Double.parseDouble(line.substring("durationSeconds:".length()).trim());
                } else if (line.startsWith("blocksPlaced:")) {
                    blocksPlaced = Integer.parseInt(line.substring("blocksPlaced:".length()).trim());
                } else if (line.startsWith("replayIndex:")) {
                    replayIndex = Integer.parseInt(line.substring("replayIndex:".length()).trim());
                } else if (line.startsWith("wasPersonalBest:")) {
                    wasPersonalBest = Boolean.parseBoolean(line.substring("wasPersonalBest:".length()).trim());
                }
            }
            if (replayId == null || arenaName == null || playerName == null || playerUuid == null) {
                return Optional.empty();
            }
            return Optional.of(new ReplayMetadata(replayId, arenaName, timestamp, durationSeconds, blocksPlaced, playerUuid, playerName, replayIndex, wasPersonalBest));
        } catch (IOException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("''", "'");
    }

    public void deleteReplay(UUID playerUuid, String arenaName, int replayIndex) {
        Path replayFile = replayRoot.resolve(playerUuid.toString()).resolve(arenaName).resolve(String.format("replay_%06d.yml", replayIndex));
        try {
            Files.deleteIfExists(replayFile);
            replayFileIndex.values().removeIf(indexedPath -> indexedPath.equals(replayFile));
        } catch (IOException ignored) {
        }
    }

    /**
     * Resolves any replay purely by its global replayId (the UUID every replay is stamped with
     * at creation time - see recordReplay/saveFailedReplay and ReplayMetadata) - without needing
     * to already know which player or arena it belongs to. This is what backs "action: replay" /
     * "replayid: ..." items: point one at any replay's ID from any menu and it opens directly,
     * with no other configuration needed.
     * <p>
     * Backed by an in-memory index built lazily (once, on first lookup) from the first line of
     * every replay file under replays/ - cheap, since "replayId:" is always written as line 1 of
     * every replay file - and kept up to date incrementally afterward as replays are recorded
     * (saveReplay/saveFailedReplay) or pruned (deleteReplay above), so a fresh full scan is rarely
     * needed again after the first call.
     */
    public Optional<ReplayMetadata> findReplayById(UUID replayId) {
        if (replayId == null) {
            return Optional.empty();
        }
        Path cached = replayFileIndex.get(replayId);
        if (cached != null) {
            if (Files.exists(cached)) {
                return readMetadataFromFile(cached);
            }
            replayFileIndex.remove(replayId);
        }
        if (!replayIndexFullyBuilt) {
            rebuildReplayIndex();
        }
        Path found = replayFileIndex.get(replayId);
        return found == null ? Optional.empty() : readMetadataFromFile(found);
    }

    private void rebuildReplayIndex() {
        replayFileIndex.clear();
        try {
            if (Files.exists(replayRoot)) {
                try (java.util.stream.Stream<Path> stream = Files.walk(replayRoot)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".yml"))
                            .forEach(this::indexReplayFile);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to index replays for lookup-by-ID: " + e.getMessage());
        }
        replayIndexFullyBuilt = true;
    }

    private void indexReplayFile(Path file) {
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("replayId:")) {
                UUID id = UUID.fromString(firstLine.substring("replayId:".length()).trim());
                replayFileIndex.put(id, file);
            }
        } catch (IOException | IllegalArgumentException ignored) {
        }
    }

    public java.util.List<ReplayFrame> loadReplayFrames(UUID playerUuid, String arenaName, int replayIndex) {
        Path replayFile;
        if (replayIndex == 0) {
            replayFile = replayRoot.resolve(playerUuid.toString()).resolve(arenaName).resolve("failed_replay.yml");
        } else {
            replayFile = replayRoot.resolve(playerUuid.toString()).resolve(arenaName).resolve(String.format("replay_%06d.yml", replayIndex));
        }
        if (!Files.exists(replayFile) || !Files.isRegularFile(replayFile)) {
            return java.util.List.of();
        }

        try {
            java.util.List<String> lines = Files.readAllLines(replayFile, StandardCharsets.UTF_8);
            java.util.List<ReplayFrame> frames = new ArrayList<>();
            ReplayFrame current = null;
            String section = null;
            boolean coordinateModeRelative = false;
            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String trimmed = raw.trim();
                if (trimmed.startsWith("coordinateMode:")) {
                    String val = trimmed.substring("coordinateMode:".length()).trim();
                    if (val.equalsIgnoreCase("relative")) {
                        coordinateModeRelative = true;
                    }
                }
                if (trimmed.startsWith("- tick:" ) || trimmed.startsWith("- tick")) {
                    // start new frame
                    String[] parts = trimmed.split(":" , 2);
                    int tick = 0;
                    if (parts.length > 1) {
                        try { tick = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
                    }
                    current = new ReplayFrame(tick);
                    if (coordinateModeRelative) {
                        current.setCoordinatesRelative(true);
                    }
                    frames.add(current);
                    section = null;
                    continue;
                }
                if (current == null) continue;

                if (section == null && trimmed.startsWith("x:")) {
                    try { current.setPosition(Double.parseDouble(trimmed.substring("x:".length()).trim()), current.getY(), current.getZ()); } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("y:")) {
                    try { current.setPosition(current.getX(), Double.parseDouble(trimmed.substring("y:".length()).trim()), current.getZ()); } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("z:")) {
                    try { current.setPosition(current.getX(), current.getY(), Double.parseDouble(trimmed.substring("z:".length()).trim())); } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("yaw:")) {
                    try { current.setRotation(Float.parseFloat(trimmed.substring("yaw:".length()).trim()), current.getPitch()); } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("pitch:")) {
                    try { current.setRotation(current.getYaw(), Float.parseFloat(trimmed.substring("pitch:".length()).trim())); } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("ping:")) {
                    try {
                        int ping = Integer.parseInt(trimmed.substring("ping:".length()).trim());
                        current.setReplayStats(ping, current.getLeftCps(), current.getRightCps());
                    } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("leftCps:")) {
                    try {
                        int leftCps = Integer.parseInt(trimmed.substring("leftCps:".length()).trim());
                        current.setReplayStats(current.getPing(), leftCps, current.getRightCps());
                    } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("rightCps:")) {
                    try {
                        int rightCps = Integer.parseInt(trimmed.substring("rightCps:".length()).trim());
                        current.setReplayStats(current.getPing(), current.getLeftCps(), rightCps);
                    } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("jumpTicks:")) {
                    try {
                        current.setJumpTicks(Integer.parseInt(trimmed.substring("jumpTicks:".length()).trim()));
                    } catch (NumberFormatException ignored) {}
                } else if (section == null && trimmed.startsWith("heldMaterial:")) {
                    current.setHeldMaterial(unquote(trimmed.substring("heldMaterial:".length()).trim()));
                } else if (trimmed.startsWith("armSwings:")) {
                    section = "armSwings";
                } else if (trimmed.startsWith("placements:")) {
                    section = "placements";
                } else if (trimmed.startsWith("breaks:")) {
                    section = "breaks";
                } else if (section != null && (trimmed.startsWith("- ") || trimmed.startsWith("-"))) {
                    // list item; handle armSwings simple case
                    if (section.equals("armSwings")) {
                        String val = trimmed.substring(trimmed.indexOf('-') + 1).trim();
                        current.addArmSwing(unquote(val));
                    } else if (section.equals("placements") || section.equals("breaks")) {
                        // multi-line map entry: expect world, x, y, z, material lines following
                        // if current map line starts with "- world:" parse next few lines
                        if (trimmed.contains("world:")) {
                            String world = unquote(trimmed.substring(trimmed.indexOf(":") + 1).trim());
                            int px = 0, py = 0, pz = 0;
                            String material = "AIR";
                            // look ahead
                            int j = i + 1;
                            for (; j < lines.size(); j++) {
                                String t2 = lines.get(j).trim();
                                if (t2.startsWith("world:") || t2.startsWith("- tick:" ) || t2.startsWith("- world:" ) ) break;
                                if (t2.startsWith("x:")) { try { px = Integer.parseInt(t2.substring("x:".length()).trim()); } catch (NumberFormatException ignored) {} }
                                if (t2.startsWith("y:")) { try { py = Integer.parseInt(t2.substring("y:".length()).trim()); } catch (NumberFormatException ignored) {} }
                                if (t2.startsWith("z:")) { try { pz = Integer.parseInt(t2.substring("z:".length()).trim()); } catch (NumberFormatException ignored) {} }
                                if (t2.startsWith("material:")) { material = unquote(t2.substring("material:".length()).trim()); }
                            }
                            ReplayBlockEvent ev = new ReplayBlockEvent(world, px, py, pz, material, section.equals("breaks"));
                            if (section.equals("placements")) current.addPlacement(ev); else current.addBreak(ev);
                        }
                    }
                } else {
                    // unknown or end of list
                }
            }
            return frames;
        } catch (IOException e) {
            return java.util.List.of();
        }
    }

    public static String formatTimestamp(long timestamp) {
        return TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }
}
