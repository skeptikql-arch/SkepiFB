package me.skepi.skepifb.leaderboard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class LeaderboardManager {

    public static final int MAX_POSITIONS = 10;

    /**
     * Which leaderboard a given call is reading/writing. VERIFIED is the original, admin-curated
     * board ("/fb lb add/remove/user" - a staff member manually places someone). UNVERIFIED is the
     * automatic board - a player's own qualifying run times get placed on it with no staff
     * involvement, via addEntry(UNVERIFIED, ...) called from TimerManager on finish.
     *
     * The two boards are completely separate per mode (separate config roots below), so a player
     * can hold a slot on both, either, or neither for the same mode at the same time.
     */
    public enum LeaderboardType {
        VERIFIED("leaderboards", "Verified"),
        UNVERIFIED("unverifiedLeaderboards", "Unverified");

        private final String rootKey;
        private final String label;

        LeaderboardType(String rootKey, String label) {
            this.rootKey = rootKey;
            this.label = label;
        }

        public String getRootKey() {
            return rootKey;
        }

        public String getLabel() {
            return label;
        }

        /**
         * Parses a user-typed "verified"/"unverified" argument (e.g. from "/fb lb list <mode>
         * [verified|unverified]"). Returns null if it does not match either, so the caller can show
         * a usage error instead of silently guessing.
         */
        public static LeaderboardType fromArgument(String text) {
            if (text == null) {
                return null;
            }
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "verified", "v" -> VERIFIED;
                case "unverified", "u" -> UNVERIFIED;
                default -> null;
            };
        }
    }

    /**
     * Result of an add-entry attempt, used purely for admin-facing messaging - the caller decides
     * exactly what to say for each case.
     */
    public enum AddResult {
        ADDED,
        UPDATED,
        NOT_QUALIFIED,
        INVALID
    }

    private final JavaPlugin plugin;
    private final File leaderboardFile;
    private FileConfiguration configuration;

    public LeaderboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.leaderboardFile = new File(dataFolder, "leaderboard.yml");
        createDefaultLeaderboardFileIfMissing();
        this.configuration = YamlConfiguration.loadConfiguration(leaderboardFile);
        createDefaultPositionTagsSectionIfMissing();
    }

    private void createDefaultLeaderboardFileIfMissing() {
        if (leaderboardFile.exists()) {
            return;
        }
        // "unverifiedLeaderboards" is intentionally omitted here - it is created lazily the first
        // time a player auto-qualifies for one, same as "leaderboards" always worked for mode
        // sections. YamlConfiguration.getConfigurationSection() returning null for a missing root
        // is already handled everywhere that reads it.
        String defaultContent = "leaderboards: {}\n";
        try {
            Files.write(leaderboardFile.toPath(), defaultContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to create default leaderboard.yml: " + ex.getMessage());
        }
    }

    /**
     * Simple in-memory representation of one occupied leaderboard slot, used while re-sorting.
     */
    private static final class Entry {
        final UUID playerUuid;
        final String playerName;
        final double score;

        Entry(UUID playerUuid, String playerName, double score) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.score = score;
        }
    }

    /**
     * Public, read-only view of a single occupied leaderboard slot - what getEntryAtPosition()
     * below hands back to callers outside this class (currently just LeaderboardMenuManager, for
     * building each top-10 player head) instead of exposing the private Entry type directly.
     */
    public static final class LeaderboardEntry {
        private final UUID playerUuid;
        private final String playerName;
        private final double score;

        private LeaderboardEntry(UUID playerUuid, String playerName, double score) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.score = score;
        }

        public UUID getPlayerUuid() {
            return playerUuid;
        }

        public String getPlayerName() {
            return playerName;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * The occupant of a single leaderboard slot (1..MAX_POSITIONS), or null if that position is
     * currently empty. Used by LeaderboardMenuManager to render each of the ten slots in the
     * graphical leaderboard menu (/lb, /leaderboard) as either a player head or an empty barrier.
     */
    public LeaderboardEntry getEntryAtPosition(LeaderboardType type, String mode, int position) {
        if (type == null || mode == null || mode.isBlank() || position < 1 || position > MAX_POSITIONS) {
            return null;
        }
        List<Entry> entries = readEntries(type, normalizeMode(mode));
        int index = position - 1;
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        Entry entry = entries.get(index);
        return new LeaderboardEntry(entry.playerUuid, entry.playerName, entry.score);
    }

    /**
     * Ensures the given mode has a leaderboard section to write into, under the given board's root.
     * Modes are not pre-registered anywhere else - any arena/mode name works the first time an
     * entry is added to it, which is what lets a leaderboard exist "for every FastBuilder mode"
     * without a separate creation step. This applies independently to each of VERIFIED and
     * UNVERIFIED - a mode can have one, the other, both, or neither at any given time.
     */
    private void ensureLeaderboardSection(LeaderboardType type, String normalizedMode, String displayName) {
        String root = type.getRootKey();
        if (!configuration.isConfigurationSection(root + "." + normalizedMode)) {
            configuration.set(root + "." + normalizedMode + ".name", displayName == null ? normalizedMode : displayName);
        }
        // Position tags (used by the tag shop) only ever apply to the VERIFIED board - an
        // unverified/automatic placement was never reviewed by staff, so it should not unlock a
        // position-based cosmetic tag on its own.
        if (type == LeaderboardType.VERIFIED) {
            ensurePositionTagsAutoGenerated(normalizedMode, displayName);
        }
    }

    /**
     * Auto-generates a default positionTags entry for every position (1..MAX_POSITIONS) on this
     * mode, the first time anyone appears on its (verified) leaderboard - so an admin never has to
     * hand-write "dune.1" through "dune.10" just to make placements on a new mode taggable. Only
     * fills in positions that don't already have a mapping, so any manual customization (renamed
     * display text) is never overwritten by this.
     */
    private void ensurePositionTagsAutoGenerated(String normalizedMode, String displayName) {
        String label = (displayName == null || displayName.isBlank()) ? normalizedMode : displayName.trim();
        boolean changedAny = false;
        for (int position = 1; position <= MAX_POSITIONS; position++) {
            String base = "positionTags." + normalizedMode + "." + position;
            if (configuration.isConfigurationSection(base)) {
                continue;
            }
            configuration.set(base + ".display", "&b#" + position + " " + capitalize(label));
            changedAny = true;
        }
        if (changedAny) {
            save();
        }
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public boolean leaderboardExists(String mode) {
        return leaderboardExists(LeaderboardType.VERIFIED, mode);
    }

    public boolean leaderboardExists(LeaderboardType type, String mode) {
        if (mode == null || mode.isBlank()) {
            return false;
        }
        return configuration.isConfigurationSection(type.getRootKey() + "." + normalizeMode(mode));
    }

    public Set<String> getLeaderboards() {
        return getLeaderboards(LeaderboardType.VERIFIED);
    }

    public Set<String> getLeaderboards(LeaderboardType type) {
        ConfigurationSection leaderboardsSection = configuration.getConfigurationSection(type.getRootKey());
        Set<String> modes = new LinkedHashSet<>();
        if (leaderboardsSection == null) {
            return modes;
        }
        for (String mode : leaderboardsSection.getKeys(false)) {
            modes.add(mode);
        }
        return modes;
    }

    public String getLeaderboardDisplayName(String mode) {
        return getLeaderboardDisplayName(LeaderboardType.VERIFIED, mode);
    }

    public String getLeaderboardDisplayName(LeaderboardType type, String mode) {
        if (mode == null || mode.isBlank()) {
            return "";
        }
        String normalizedMode = normalizeMode(mode);
        return configuration.getString(type.getRootKey() + "." + normalizedMode + ".name", mode.trim());
    }

    /**
     * Reads every currently-occupied slot for a mode into a mutable, sortable list. Empty slots are
     * simply absent from this list - callers re-derive placeholder slots separately when rendering.
     */
    private List<Entry> readEntries(LeaderboardType type, String normalizedMode) {
        List<Entry> entries = new ArrayList<>();
        if (!leaderboardExists(type, normalizedMode)) {
            return entries;
        }
        String root = type.getRootKey();
        for (int position = 1; position <= MAX_POSITIONS; position++) {
            ConfigurationSection positionSection = configuration.getConfigurationSection(root + "." + normalizedMode + ".positions." + position);
            if (positionSection == null) {
                continue;
            }
            String player = positionSection.getString("player", null);
            if (player == null || player.isBlank()) {
                continue;
            }
            String uuidText = positionSection.getString("playerUuid", null);
            UUID uuid = null;
            if (uuidText != null) {
                try {
                    uuid = UUID.fromString(uuidText);
                } catch (IllegalArgumentException ignored) {
                }
            }
            double score = positionSection.getDouble("score", 0.0d);
            entries.add(new Entry(uuid, player, score));
        }
        return entries;
    }

    /**
     * Rewrites positions 1-10 from a (already sorted, already truncated) entry list, clearing
     * anything past the given list's size back to empty placeholders.
     */
    private void writeEntries(LeaderboardType type, String normalizedMode, List<Entry> sortedEntries) {
        String root = type.getRootKey();
        for (int i = 0; i < MAX_POSITIONS; i++) {
            int position = i + 1;
            String base = root + "." + normalizedMode + ".positions." + position;
            if (i < sortedEntries.size()) {
                Entry entry = sortedEntries.get(i);
                configuration.set(base + ".player", entry.playerName);
                configuration.set(base + ".playerUuid", entry.playerUuid == null ? null : entry.playerUuid.toString());
                configuration.set(base + ".score", entry.score);
            } else {
                configuration.set(base + ".player", null);
                configuration.set(base + ".playerUuid", null);
                configuration.set(base + ".score", 0.0d);
            }
        }
        save();
    }

    /**
     * Adds (or updates, if the player already has a slot on this leaderboard) an entry with the
     * given time, keeping the board sorted fastest-first and capped at MAX_POSITIONS. If the board
     * is already full and this time does not beat the current slowest entry, nothing is written and
     * NOT_QUALIFIED is returned so the caller can tell why nothing happened. Defaults to the
     * VERIFIED board (the original, admin-facing behavior via "/fb lb add").
     */
    public AddResult addEntry(String mode, UUID playerUuid, String playerName, double time) {
        return addEntry(LeaderboardType.VERIFIED, mode, playerUuid, playerName, time);
    }

    /**
     * Same as above but against a specific board. This is what auto-qualification uses: on every
     * completed run, TimerManager calls addEntry(UNVERIFIED, arenaName, uuid, name, finishTime) - if
     * the time is fast enough to place in the top MAX_POSITIONS for that mode's unverified board, the
     * player is inserted at the highest slot their time earns (existing worse entries shift down and
     * anything past position 10 falls off); if not, NOT_QUALIFIED is returned and nothing changes.
     */
    public AddResult addEntry(LeaderboardType type, String mode, UUID playerUuid, String playerName, double time) {
        if (type == null || mode == null || mode.isBlank() || playerName == null || playerName.isBlank() || time < 0 || Double.isNaN(time) || Double.isInfinite(time)) {
            return AddResult.INVALID;
        }
        String normalizedMode = normalizeMode(mode);
        ensureLeaderboardSection(type, normalizedMode, mode.trim());

        List<Entry> entries = readEntries(type, normalizedMode);
        boolean isUpdate = false;

        // A player only ever occupies one slot on a given mode's leaderboard - adding again just
        // replaces their previous time rather than creating a second entry for the same person.
        for (int i = 0; i < entries.size(); i++) {
            Entry existing = entries.get(i);
            boolean samePlayer = (playerUuid != null && playerUuid.equals(existing.playerUuid))
                    || (playerUuid == null && existing.playerUuid == null && playerName.equalsIgnoreCase(existing.playerName));
            if (samePlayer) {
                // For an automatic (UNVERIFIED) update, only replace the existing slot if the new
                // time is actually faster - a slower repeat run should never bump a player's own
                // best time off their existing slot. VERIFIED keeps its original behavior (an admin
                // explicitly running "/fb lb add" again always overwrites, since that is a deliberate
                // correction, not an automatic re-submission).
                if (type == LeaderboardType.UNVERIFIED && time >= existing.score) {
                    return AddResult.NOT_QUALIFIED;
                }
                entries.remove(i);
                isUpdate = true;
                break;
            }
        }

        if (!isUpdate && entries.size() >= MAX_POSITIONS) {
            double slowestCurrent = entries.stream().mapToDouble(e -> e.score).max().orElse(Double.MAX_VALUE);
            if (time >= slowestCurrent) {
                return AddResult.NOT_QUALIFIED;
            }
        }

        entries.add(new Entry(playerUuid, playerName, time));
        entries.sort(Comparator.comparingDouble(e -> e.score));
        if (entries.size() > MAX_POSITIONS) {
            entries = new ArrayList<>(entries.subList(0, MAX_POSITIONS));
        }
        writeEntries(type, normalizedMode, entries);
        return isUpdate ? AddResult.UPDATED : AddResult.ADDED;
    }

    /**
     * Removes the given player's entry from a mode's leaderboard, if present, and compacts the
     * remaining entries so there is no gap in the middle of the list. The time parameter is used to
     * confirm the correct entry is being removed (protects against a typo removing the wrong
     * player's record silently) - if it does not match the stored time, nothing is removed. Defaults
     * to the VERIFIED board.
     */
    public boolean removeEntry(String mode, UUID playerUuid, String playerName, Double time) {
        return removeEntry(LeaderboardType.VERIFIED, mode, playerUuid, playerName, time);
    }

    public boolean removeEntry(LeaderboardType type, String mode, UUID playerUuid, String playerName, Double time) {
        if (type == null || mode == null || mode.isBlank() || playerName == null || playerName.isBlank()) {
            return false;
        }
        String normalizedMode = normalizeMode(mode);
        List<Entry> entries = readEntries(type, normalizedMode);
        if (entries.isEmpty()) {
            return false;
        }

        int foundIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry existing = entries.get(i);
            boolean samePlayer = (playerUuid != null && playerUuid.equals(existing.playerUuid))
                    || (playerUuid == null && existing.playerUuid == null && playerName.equalsIgnoreCase(existing.playerName))
                    || playerName.equalsIgnoreCase(existing.playerName);
            if (!samePlayer) {
                continue;
            }
            if (time != null && Math.abs(existing.score - time) > 0.0005) {
                // Same player, but the time given does not match what is stored - do not remove,
                // since this is very likely a typo rather than an intentional removal.
                continue;
            }
            foundIndex = i;
            break;
        }

        if (foundIndex < 0) {
            return false;
        }

        entries.remove(foundIndex);
        entries.sort(Comparator.comparingDouble(e -> e.score));
        writeEntries(type, normalizedMode, entries);
        return true;
    }

    /**
     * Manually sets an exact position on a leaderboard, bypassing the normal sorted add/remove
     * flow. Kept for power-user/admin correction use; addEntry/removeEntry are the normal path.
     * Defaults to the VERIFIED board.
     */
    public boolean setLeaderboardEntry(String mode, int position, UUID playerUuid, String playerName, double score) {
        return setLeaderboardEntry(LeaderboardType.VERIFIED, mode, position, playerUuid, playerName, score);
    }

    public boolean setLeaderboardEntry(LeaderboardType type, String mode, int position, UUID playerUuid, String playerName, double score) {
        if (type == null || mode == null || mode.isBlank() || position < 1 || position > MAX_POSITIONS) {
            return false;
        }
        String normalizedMode = normalizeMode(mode);
        ensureLeaderboardSection(type, normalizedMode, mode.trim());
        String root = type.getRootKey();
        configuration.set(root + "." + normalizedMode + ".positions." + position + ".player", playerName == null ? "" : playerName);
        configuration.set(root + "." + normalizedMode + ".positions." + position + ".playerUuid", playerUuid == null ? null : playerUuid.toString());
        configuration.set(root + "." + normalizedMode + ".positions." + position + ".score", score);
        save();
        return true;
    }

    /**
     * Renders a mode's leaderboard as display lines, one per position 1..MAX_POSITIONS. This works
     * even for a mode that has never had an entry added - it simply renders every slot as the empty
     * placeholder, so every arena/mode can be listed without a separate "create leaderboard" step.
     * Defaults to the VERIFIED board (original behavior of "/fb lb list <mode>").
     */
    public List<String> getLeaderboardLines(String mode) {
        return getLeaderboardLines(LeaderboardType.VERIFIED, mode);
    }

    /**
     * Same rendering as above, for either board. Identical layout to the verified board - only the
     * top line differs, tagging whether this is the "(Verified)" (admin-placed) or "(Unverified)"
     * (auto-qualified) leaderboard for the mode.
     */
    public List<String> getLeaderboardLines(LeaderboardType type, String mode) {
        List<String> lines = new ArrayList<>();
        if (type == null || mode == null || mode.isBlank()) {
            return lines;
        }
        String normalizedMode = normalizeMode(mode);
        lines.add("§eMode: " + getLeaderboardDisplayName(type, mode) + " §7(" + type.getLabel() + ")");
        List<Entry> entries = readEntries(type, normalizedMode);
        for (int position = 1; position <= MAX_POSITIONS; position++) {
            int index = position - 1;
            if (index < entries.size()) {
                Entry entry = entries.get(index);
                // Color the player's name using their LuckPerms rank prefix color, if LuckPerms is
                // installed and they have one configured. Falls back to plain white if not.
                String nameColor = entry.playerUuid != null ? me.skepi.skepifb.util.LuckPermsUtil.getRankColor(entry.playerUuid) : null;
                String coloredName = (nameColor != null ? nameColor : "§f") + entry.playerName;
                lines.add("§7" + position + ". " + coloredName + " §7- §f" + String.format(Locale.ROOT, "%.3f", entry.score));
            } else {
                lines.add("§7" + position + ". §8-.--");
            }
        }
        return lines;
    }

    /**
     * A single leaderboard placement held by one player, used to render the "leaderboard section"
     * of the island statboard hologram (see StatboardManager) - one of these per mode the player
     * currently holds a position on, regardless of which arena/mode they're actually standing on.
     */
    public static final class PlayerLeaderboardEntry {
        private final String mode;
        private final String modeDisplayName;
        private final int position;
        private final double score;

        private PlayerLeaderboardEntry(String mode, String modeDisplayName, int position, double score) {
            this.mode = mode;
            this.modeDisplayName = modeDisplayName;
            this.position = position;
            this.score = score;
        }

        public String getMode() {
            return mode;
        }

        public String getModeDisplayName() {
            return modeDisplayName;
        }

        public int getPosition() {
            return position;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * Every leaderboard placement the given player currently holds, across every mode that has a
     * leaderboard at all - not just their current arena. Sorted by position (best placements
     * first), then by mode display name for a stable order among equal positions. Empty if the
     * player is not on any leaderboard. Defaults to the VERIFIED board (original behavior, used by
     * the statboard hologram to show only staff-verified placements).
     */
    public List<PlayerLeaderboardEntry> getPlayerLeaderboardEntries(UUID playerUuid) {
        return getPlayerLeaderboardEntries(LeaderboardType.VERIFIED, playerUuid);
    }

    public List<PlayerLeaderboardEntry> getPlayerLeaderboardEntries(LeaderboardType type, UUID playerUuid) {
        List<PlayerLeaderboardEntry> results = new ArrayList<>();
        if (type == null || playerUuid == null) {
            return results;
        }
        for (String mode : getLeaderboards(type)) {
            String normalizedMode = normalizeMode(mode);
            int position = getPlayerPosition(type, mode, playerUuid);
            if (position < 1) {
                continue;
            }
            List<Entry> entries = readEntries(type, normalizedMode);
            int index = position - 1;
            if (index < 0 || index >= entries.size()) {
                continue;
            }
            Entry entry = entries.get(index);
            results.add(new PlayerLeaderboardEntry(mode, getLeaderboardDisplayName(type, mode), position, entry.score));
        }
        results.sort(Comparator.<PlayerLeaderboardEntry>comparingInt(PlayerLeaderboardEntry::getPosition)
                .thenComparing(PlayerLeaderboardEntry::getModeDisplayName, String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    private String normalizeMode(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Position tags: links a specific VERIFIED leaderboard placement (e.g. "3rd on Dune") to a
    // display name, stored under a separate "positionTags" section of this same leaderboard.yml so
    // it lives alongside the leaderboard data it references. Used by the tag shop to figure out
    // which position-based tags a given player currently qualifies for. Equipping a tag applies its
    // display text directly as a LuckPerms prefix (see TagManager) - no LuckPerms group,
    // permission, or parent is involved at all, so this never conflicts with any other parent/group
    // a player already has. Deliberately VERIFIED-only - see ensureLeaderboardSection.
    // ------------------------------------------------------------------

    /**
     * Returns the position (1..MAX_POSITIONS) the given player currently holds on a mode's
     * (verified) leaderboard, or -1 if they are not currently on it.
     */
    public int getPlayerPosition(String mode, UUID playerUuid) {
        return getPlayerPosition(LeaderboardType.VERIFIED, mode, playerUuid);
    }

    public int getPlayerPosition(LeaderboardType type, String mode, UUID playerUuid) {
        if (type == null || mode == null || mode.isBlank() || playerUuid == null) {
            return -1;
        }
        String normalizedMode = normalizeMode(mode);
        List<Entry> entries = readEntries(type, normalizedMode);
        for (int i = 0; i < entries.size(); i++) {
            if (playerUuid.equals(entries.get(i).playerUuid)) {
                return i + 1;
            }
        }
        return -1;
    }

    public boolean setPositionTag(String mode, int position, String display) {
        if (mode == null || mode.isBlank() || position < 1 || position > MAX_POSITIONS || display == null || display.isBlank()) {
            return false;
        }
        String normalizedMode = normalizeMode(mode);
        String base = "positionTags." + normalizedMode + "." + position;
        configuration.set(base + ".display", display.trim());
        save();
        return true;
    }

    public boolean removePositionTag(String mode, int position) {
        if (mode == null || mode.isBlank() || position < 1 || position > MAX_POSITIONS) {
            return false;
        }
        String normalizedMode = normalizeMode(mode);
        String base = "positionTags." + normalizedMode + "." + position;
        if (!configuration.isConfigurationSection(base)) {
            return false;
        }
        configuration.set(base, null);
        save();
        return true;
    }

    public String getPositionTagDisplay(String mode, int position) {
        if (mode == null || mode.isBlank() || position < 1 || position > MAX_POSITIONS) {
            return null;
        }
        return configuration.getString("positionTags." + normalizeMode(mode) + "." + position + ".display", null);
    }

    /**
     * Every mode name that has at least one position-tag configured, so callers (the tag shop) only
     * need to check a player's placement on modes that actually matter instead of every arena.
     */
    public Set<String> getModesWithPositionTags() {
        Set<String> modes = new LinkedHashSet<>();
        ConfigurationSection section = configuration.getConfigurationSection("positionTags");
        if (section == null) {
            return modes;
        }
        modes.addAll(section.getKeys(false));
        return modes;
    }

    private void createDefaultPositionTagsSectionIfMissing() {
        if (configuration.isConfigurationSection("positionTags")) {
            return;
        }
        // Nothing to seed anymore - positionTags entries are now auto-generated per mode the first
        // time anyone is added to that mode's leaderboard (see ensurePositionTagsAutoGenerated).
        // This just guarantees the section exists so getModesWithPositionTags() never sees null.
        configuration.createSection("positionTags");
        save();
    }

    private void save() {
        try {
            configuration.save(leaderboardFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save leaderboard.yml: " + ex.getMessage());
        }
    }
}
