package me.skepi.skepifb.commands;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaManager;
import me.skepi.skepifb.arena.Layout;
import me.skepi.skepifb.config.ConfigManager;
import me.skepi.skepifb.hotbar.HotbarManager;
import me.skepi.skepifb.inventory.InventoryManager;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.scoreboard.ScoreboardManager;
import me.skepi.skepifb.schematic.SchematicService;
import me.skepi.skepifb.leaderboard.LeaderboardManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class FBCommand implements CommandExecutor {

    private final SkepiFBPlugin plugin;
    private final ArenaManager arenaManager;
    private final PlayerManager playerManager;
    private final ConfigManager configManager;
    private final SchematicService schematicService;
    private final ScoreboardManager scoreboardManager;
    private final InventoryManager inventoryManager;
    private final HotbarManager hotbarManager;
    private final LeaderboardManager leaderboardManager;

    public FBCommand(SkepiFBPlugin plugin, ArenaManager arenaManager, PlayerManager playerManager, SchematicService schematicService, ScoreboardManager scoreboardManager, InventoryManager inventoryManager, HotbarManager hotbarManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.playerManager = playerManager;
        this.configManager = plugin.getConfigManager();
        this.schematicService = schematicService;
        this.scoreboardManager = scoreboardManager;
        this.inventoryManager = inventoryManager;
        this.hotbarManager = hotbarManager;
        this.leaderboardManager = plugin.getLeaderboardManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skepifb.admin") && !sender.isOp()) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            return handleHelp(sender);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "add":
                return handleAdd(sender, args);
            case "remove":
                return handleRemove(sender, args);
            case "list":
                return handleList(sender);
            case "join":
                return handleJoin(sender, args);
            case "xp":
                return handleXp(sender, args);
            case "leave":
                return handleLeave(sender);
            case "menu":
                return handleMenu(sender, args);
            case "reload":
                return handleReload(sender);
            case "replayinfo":
                return handleReplayInfo(sender);
            case "coins":
                return handleCoins(sender, args);
            case "lb":
            case "leaderboard":
                return handleLeaderboard(sender, args);
            case "replays":
                return handleReplays(sender, args);
            case "island":
                return handleIsland(sender, args);
            case "test":
                return handleTest(sender, args);
            case "setfacing":
                return handleSetFacing(sender, args);
            case "help":
                return handleHelp(sender);
            default:
                sender.sendMessage("§cUnknown subcommand. Use /fb help for a command list.");
                return true;
        }
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players may create arenas.");
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage("§cUsage: /fb add <arena> <schematic> <islandCount> [spacing] [straight|diagonal]");
            return true;
        }

        String arenaName = args[1];
        String schematicName = args[2];
        int islandCount;
        int spacing = 20;
        Layout layout = Layout.STRAIGHT;

        try {
            islandCount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid island count. Please enter a whole number greater than zero.");
            return true;
        }

        if (args.length >= 5) {
            try {
                spacing = Integer.parseInt(args[4]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cInvalid spacing. Please enter a whole number greater than zero.");
                return true;
            }
        }

        if (args.length >= 6) {
            layout = Layout.fromString(args[5]);
            if (!args[5].equalsIgnoreCase("straight") && !args[5].equalsIgnoreCase("diagonal")) {
                sender.sendMessage("§cInvalid layout. Use straight or diagonal.");
                return true;
            }
        }

        if (arenaManager.arenaExists(arenaName)) {
            sender.sendMessage("§cArena already exists.");
            return true;
        }

        if (islandCount <= 0) {
            sender.sendMessage("§cIsland count must be greater than zero.");
            return true;
        }

        if (spacing <= 0) {
            sender.sendMessage("§cSpacing must be greater than zero.");
            return true;
        }

        if (!schematicService.isWorldEditAvailable()) {
            sender.sendMessage("§cSchematic creation is disabled because WorldEdit is not available.");
            return true;
        }

        if (!schematicService.schematicExists(schematicName)) {
            sender.sendMessage("§cSchematic file not found: " + schematicName);
            return true;
        }

        Arena arena = arenaManager.createArena(arenaName, schematicName, islandCount, spacing, layout);
        if (arena == null) {
            sender.sendMessage("§cCould not create arena. Try again later.");
            return true;
        }

        sender.sendMessage("§aArena " + arena.getName() + " created with " + arena.getIslandCount() + " islands.");
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("§cUsage: /fb remove <arena>");
            return true;
        }

        String arenaName = args[1];
        if (!arenaManager.arenaExists(arenaName)) {
            sender.sendMessage("§cArena doesn't exist.");
            return true;
        }

        arenaManager.removeArena(arenaName);
        sender.sendMessage("§aArena " + arenaName + " has been removed.");
        return true;
    }

    private boolean handleSetFacing(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players may use this command.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("§cUsage: /fb setfacing <arena>");
            sender.sendMessage("§7Stand where you want players to spawn, face the direction you");
            sender.sendMessage("§7want them to face, then run this command.");
            return true;
        }
        String arenaName = args[1];
        if (!arenaManager.arenaExists(arenaName)) {
            sender.sendMessage("§cArena doesn't exist.");
            return true;
        }
        float yaw = player.getLocation().getYaw();
        float pitch = player.getLocation().getPitch();
        boolean updated = arenaManager.setArenaSpawnFacing(arenaName, yaw, pitch);
        if (!updated) {
            sender.sendMessage("§cCould not update spawn facing for arena " + arenaName + ".");
            return true;
        }
        sender.sendMessage(String.format(Locale.ROOT,
                "§aSpawn facing for arena %s set to yaw %.1f / pitch %.1f. Every island in this arena now spawns/respawns facing this direction.",
                arenaName, yaw, pitch));
        return true;
    }

    private boolean handleIsland(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /fb island <add|remove|list> [mode] [islandName] [schematic]");
            return true;
        }

        String subcommand = args[1].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "add":
                return handleIslandAdd(sender, args);
            case "remove":
                return handleIslandRemove(sender, args);
            case "list":
                return handleIslandList(sender, args);
            default:
                sender.sendMessage("§cUnknown island subcommand. Use /fb island add|remove|list");
                return true;
        }
    }

    private boolean handleTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players may use this command.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage("§eUsage: /fb test <setspawn|start|exit>");
            return true;
        }

        String subcommand = args[1].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "setspawn" -> handleTestSetspawn(player);
            case "start" -> handleTestStart(player);
            case "exit" -> handleTestExit(player);
            default -> {
                player.sendMessage("§cUnknown test subcommand. Use /fb test setspawn|start|exit");
                yield true;
            }
        };
    }

    private boolean handleTestSetspawn(Player player) {
        UUID playerUuid = player.getUniqueId();
        if (playerManager.isInArena(playerUuid)) {
            player.sendMessage("§cYou cannot set a test spawn while inside a FastBuilder arena.");
            return true;
        }

        playerManager.setTemporarySpawn(playerUuid, player.getLocation());
        player.sendMessage("§aTemporary test spawn saved. Use /fb test start to teleport there.");
        return true;
    }

    private boolean handleTestStart(Player player) {
        UUID playerUuid = player.getUniqueId();
        if (playerManager.isInArena(playerUuid)) {
            player.sendMessage("§cYou cannot start test mode while inside a FastBuilder arena.");
            return true;
        }
        if (playerManager.isInTestMode(playerUuid)) {
            player.sendMessage("§cYou are already in FastBuilder test mode.");
            return true;
        }

        if (!playerManager.hasTemporarySpawn(playerUuid)) {
            player.sendMessage("§cNo temporary test spawn set. Use /fb test setspawn first.");
            return true;
        }

        Location spawn = playerManager.getResolvedRespawnLocation(playerUuid);
        if (spawn == null) {
            player.sendMessage("§cUnable to teleport to test spawn.");
            return true;
        }

        playerManager.setTestMode(playerUuid, true);
        plugin.getShopManager().ensureEquippedTool(player);
        player.teleport(spawn);
        inventoryManager.giveArenaBlock(player);
        hotbarManager.giveHotbarToPlayer(player);
        scoreboardManager.showScoreboard(player);
        plugin.getTimerManager().ensureSession(playerUuid);
        player.sendMessage("§aFastBuilder test mode started. Use /fb test exit to leave.");
        return true;
    }

    private boolean handleTestExit(Player player) {
        UUID playerUuid = player.getUniqueId();
        if (!playerManager.isInTestMode(playerUuid)) {
            player.sendMessage("§cYou are not currently in FastBuilder test mode.");
            return true;
        }

        playerManager.leaveTestMode(player);
        player.sendMessage("§aExited FastBuilder test mode.");
        return true;
    }

    private Arena selectTestArena() {
        String defaultArenaName = configManager.getConfiguration().getString("default-arena", "").trim();
        if (!defaultArenaName.isBlank()) {
            Arena defaultArena = arenaManager.getArena(defaultArenaName);
            if (defaultArena != null && defaultArena.findAvailableIsland().isPresent()) {
                return defaultArena;
            }
        }

        for (Arena arena : arenaManager.getArenas()) {
            if (arena.findAvailableIsland().isPresent()) {
                return arena;
            }
        }
        return null;
    }

    private boolean handleIslandAdd(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /fb island add <arena> <islandName> <schematic>");
            return true;
        }

        String arenaName = args[2];
        String cosmeticKey = args[3];
        String schematic = args.length >= 5 ? args[4] : (cosmeticKey + ".schem");

        if (schematic == null || schematic.isBlank()) {
            sender.sendMessage("§cSchematic cannot be empty.");
            return true;
        }

        if (!schematicService.schematicExists(schematic)) {
            sender.sendMessage("§cSchematic file not found: " + schematic);
            return true;
        }

        if (arenaName == null || arenaName.isBlank() || !arenaManager.arenaExists(arenaName)) {
            sender.sendMessage("§cArena does not exist: " + arenaName);
            return true;
        }

        String normalizedMode = normalizeMode(configManager.getArenaStartMode(arenaName));
        if (normalizedMode == null || normalizedMode.isBlank()) {
            sender.sendMessage("§cUnable to determine arena start mode for " + arenaName + ".");
            return true;
        }

        String displayName = cosmeticKey;
        boolean added = configManager.addIslandCosmetic(arenaName, normalizedMode, cosmeticKey, schematic, "GRASS_BLOCK", displayName, 2000, -1);
        if (!added) {
            sender.sendMessage("§cThat island cosmetic already exists or the key is invalid.");
            return true;
        }

        boolean configured = plugin.getShopManager().ensureIslandShopEntry(arenaName, normalizedMode, cosmeticKey, displayName, schematic);
        if (!configured) {
            sender.sendMessage("§eAdded island cosmetic but could not register a shop entry automatically.");
            return true;
        }

        sender.sendMessage("§aAdded island cosmetic '" + cosmeticKey + "' for arena '" + arenaName + "' mode '" + normalizedMode + "'.");
        sender.sendMessage("§7Auto-generated shop defaults: material=GRASS_BLOCK, name=" + displayName + ", price=2000");
        return true;
    }

    private boolean handleIslandRemove(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /fb island remove <arena> <cosmeticKey>");
            return true;
        }

        String arenaName = args[2];
        String cosmeticKey = args[3];
        if (arenaName == null || arenaName.isBlank() || !arenaManager.arenaExists(arenaName)) {
            sender.sendMessage("§cArena does not exist: " + arenaName);
            return true;
        }

        String mode = normalizeMode(configManager.getArenaStartMode(arenaName));
        boolean removed = configManager.removeIslandCosmetic(arenaName, mode, cosmeticKey);
        if (!removed) {
            sender.sendMessage("§cThat island cosmetic does not exist.");
            return true;
        }
        sender.sendMessage("§aRemoved island cosmetic '" + cosmeticKey + "' for arena '" + arenaName + "' mode '" + mode + "'.");
        return true;
    }

    private boolean handleIslandList(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /fb island list <arena>");
            return true;
        }

        String arenaName = args[2];
        if (arenaName == null || arenaName.isBlank() || !arenaManager.arenaExists(arenaName)) {
            sender.sendMessage("§cArena does not exist: " + arenaName);
            return true;
        }

        String mode = normalizeMode(configManager.getArenaStartMode(arenaName));
        sender.sendMessage("§eIsland cosmetics for " + arenaName + " / " + mode + ":");
        for (String key : configManager.getIslandCosmeticKeys(arenaName, mode)) {
            sender.sendMessage("§7- " + key);
        }
        return true;
    }

    private boolean handleMenu(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /fb menu <add|remove|list|open> [menuId]");
            return true;
        }

        String subcommand = args[1].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "add":
                return handleMenuAdd(sender, args);
            case "remove":
                return handleMenuRemove(sender, args);
            case "list":
                return handleMenuList(sender);
            case "open":
                return handleMenuOpen(sender, args);
            default:
                sender.sendMessage("§cUnknown menu subcommand. Use /fb menu add|remove|list|open");
                return true;
        }
    }

    private String normalizeMode(String mode) {
        if (mode == null) {
            return null;
        }
        String normalized = mode.trim();
        if (normalized.isBlank()) {
            return null;
        }
        return ConfigManager.normalizeConfigKey(normalized);
    }

    private String resolveArenaName() {
        String configuredArena = configManager.getConfiguration().getString("default-arena", "");
        if (configuredArena != null && !configuredArena.isBlank() && arenaManager.arenaExists(configuredArena)) {
            return configuredArena;
        }
        List<Arena> arenas = arenaManager.getArenas();
        if (arenas.size() == 1) {
            return arenas.get(0).getName();
        }
        return null;
    }

    private int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean handleMenuAdd(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("§cUsage: /fb menu add <menuId>");
            return true;
        }

        String menuId = normalizeMenuKey(args[2]);
        if (configManager.menuExists(menuId)) {
            sender.sendMessage("§cA menu with that ID already exists.");
            return true;
        }

        boolean created = configManager.addMenu(menuId);
        if (!created) {
            sender.sendMessage("§cFailed to create menu. Ensure the menu ID is valid.");
            return true;
        }

        sender.sendMessage("§aMenu '" + menuId + "' has been created in menu.yml.");
        sender.sendMessage("§eCustomize it in menu.yml and reload the plugin.");
        return true;
    }

    private boolean handleMenuRemove(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("§cUsage: /fb menu remove <menuId>");
            return true;
        }

        String menuId = normalizeMenuKey(args[2]);
        if (!configManager.menuExists(menuId)) {
            sender.sendMessage("§cMenu '" + menuId + "' does not exist.");
            return true;
        }

        if (configManager.isBuiltInMenu(menuId)) {
            sender.sendMessage("§cBuilt-in menus cannot be removed.");
            return true;
        }

        boolean removed = configManager.removeMenu(menuId);
        if (!removed) {
            sender.sendMessage("§cFailed to remove menu '" + menuId + "'.");
            return true;
        }

        sender.sendMessage("§aMenu '" + menuId + "' has been removed.");
        return true;
    }

    private boolean handleMenuList(CommandSender sender) {
        sender.sendMessage("§eConfigured menu IDs:");
        for (String menuKey : configManager.getMenuKeys()) {
            sender.sendMessage("§7- " + menuKey);
        }
        return true;
    }

    private boolean handleMenuOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players may open menus.");
            return true;
        }

        if (args.length != 3) {
            sender.sendMessage("§cUsage: /fb menu open <menuId>");
            return true;
        }

        String menuId = normalizeMenuKey(args[2]);
        if (!configManager.menuExists(menuId)) {
            sender.sendMessage("§cMenu '" + menuId + "' does not exist.");
            return true;
        }

        hotbarManager.openBlankSubmenu(player, menuId);
        return true;
    }

    private boolean handleReplayInfo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players may use this command.");
            return true;
        }
        Player player = (Player) sender;
        String info = plugin.getTimerManager().getReplayDebugString(player.getUniqueId());
        for (String line : info.split("\\n")) {
            player.sendMessage(line);
        }
        return true;
    }

    private boolean handleCoins(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /fb coins <add|remove|set> <player> <amount>");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found online.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid amount.");
            return true;
        }

        switch (action) {
            case "add":
                plugin.getStatsManager().addCoins(target.getUniqueId(), amount);
                break;
            case "remove":
                plugin.getStatsManager().addCoins(target.getUniqueId(), -amount);
                break;
            case "set":
                plugin.getStatsManager().setCoins(target.getUniqueId(), amount);
                break;
            default:
                sender.sendMessage("§cUsage: /fb coins <add|remove|set> <player> <amount>");
                return true;
        }

        sender.sendMessage("§aUpdated coins for " + target.getName() + " to " + plugin.getStatsManager().getCoins(target.getUniqueId()) + ".");
        return true;
    }

    private boolean handleXp(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /fb xp <add|remove|set> <player> <amount>");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found online.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid amount.");
            return true;
        }

        UUID targetUuid = target.getUniqueId();
        switch (action) {
            case "add":
                plugin.getStatsManager().addXp(targetUuid, amount);
                break;
            case "remove":
                plugin.getStatsManager().addXp(targetUuid, -amount);
                break;
            case "set":
                plugin.getStatsManager().setXp(targetUuid, amount);
                break;
            default:
                sender.sendMessage("§cUsage: /fb xp <add|remove|set> <player> <amount>");
                return true;
        }

        sender.sendMessage("§aUpdated XP for " + target.getName() + " to " + plugin.getStatsManager().getXp(targetUuid) + ".");
        plugin.getTimerManager().onPlayerXpChanged(targetUuid);
        return true;
    }

    private boolean handleLeaderboard(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /fb lb <add|remove|list|user> ...");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add":
                return handleLeaderboardAdd(sender, args);
            case "remove":
                return handleLeaderboardRemove(sender, args);
            case "list":
                return handleLeaderboardList(sender, args);
            case "user":
                return handleLeaderboardUser(sender, args);
            default:
                sender.sendMessage("§cUsage: /fb lb <add|remove|list|user> ...");
                return true;
        }
    }

    /**
     * /fb lb add <user> <mode> <time> - adds/updates a player's entry on a mode's leaderboard.
     * "mode" is just an arena name; no separate leaderboard-creation step is needed, a leaderboard
     * exists implicitly for every arena/mode the moment an entry is added to it.
     */
    private boolean handleLeaderboardAdd(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§cUsage: /fb lb add <user> <mode> <time>");
            return true;
        }
        String userArg = args[2];
        String mode = args[3];
        OfflinePlayer target = Bukkit.getOfflinePlayer(userArg);
        if (target == null || target.getUniqueId() == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer not found: " + userArg);
            return true;
        }
        if (arenaManager.getArena(mode) == null) {
            sender.sendMessage("§eWarning: no arena named '" + mode + "' exists, but the leaderboard entry will still be saved under that mode name.");
        }
        double time;
        try {
            time = Double.parseDouble(args[4]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid time: " + args[4]);
            return true;
        }
        if (time < 0 || Double.isNaN(time) || Double.isInfinite(time)) {
            sender.sendMessage("§cTime must be a positive number.");
            return true;
        }

        String playerName = target.getName() != null ? target.getName() : userArg;
        LeaderboardManager.AddResult result = leaderboardManager.addEntry(mode, target.getUniqueId(), playerName, time);
        switch (result) {
            case ADDED -> sender.sendMessage("§aAdded " + playerName + " to the '" + mode + "' leaderboard with a time of " + String.format(Locale.ROOT, "%.3f", time) + ".");
            case UPDATED -> sender.sendMessage("§aUpdated " + playerName + "'s time on the '" + mode + "' leaderboard to " + String.format(Locale.ROOT, "%.3f", time) + ".");
            case NOT_QUALIFIED -> sender.sendMessage("§cThat time does not beat the current top " + LeaderboardManager.MAX_POSITIONS + " for '" + mode + "'.");
            case INVALID -> sender.sendMessage("§cCould not add that entry - check the player, mode, and time.");
        }
        return true;
    }

    /**
     * /fb lb remove <user> <mode> <time> - removes a player's entry from a mode's leaderboard. The
     * time must match what is stored, as a safety check against accidentally removing the wrong
     * entry from a typo.
     */
    private boolean handleLeaderboardRemove(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§cUsage: /fb lb remove <user> <mode> <time>");
            return true;
        }
        String userArg = args[2];
        String mode = args[3];
        OfflinePlayer target = Bukkit.getOfflinePlayer(userArg);
        if (target == null || target.getUniqueId() == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer not found: " + userArg);
            return true;
        }
        double time;
        try {
            time = Double.parseDouble(args[4]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid time: " + args[4]);
            return true;
        }

        String playerName = target.getName() != null ? target.getName() : userArg;
        boolean removed = leaderboardManager.removeEntry(mode, target.getUniqueId(), playerName, time);
        if (removed) {
            sender.sendMessage("§aRemoved " + playerName + " from the '" + mode + "' leaderboard.");
        } else {
            sender.sendMessage("§cNo matching entry found for " + playerName + " on '" + mode + "' with that time.");
        }
        return true;
    }

    /**
     * /fb lb list <arena> - shows the top 10 for that mode, with '-.--' placeholders for any empty
     * slots. Works even if nothing has ever been added to that mode's leaderboard yet.
     */
    private boolean handleLeaderboardList(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /fb lb list <arena>");
            return true;
        }
        String mode = args[2];
        for (String line : leaderboardManager.getLeaderboardLines(mode)) {
            sender.sendMessage(line);
        }
        return true;
    }

    /**
     * /fb lb user <mode> <player> <position> <score> - power-user command to manually force an
     * exact position, bypassing normal sorted add/remove. Unchanged from before.
     */
    private boolean handleLeaderboardUser(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage("§cUsage: /fb lb user <mode> <player> <position> <score>");
            return true;
        }
        String mode = args[2];
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[3]);
        if (target == null || target.getUniqueId() == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage("§cPlayer not found: " + args[3]);
            return true;
        }
        int position;
        double score;
        try {
            position = Integer.parseInt(args[4]);
            score = Double.parseDouble(args[5]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid position or score.");
            return true;
        }
        if (position < 1 || position > LeaderboardManager.MAX_POSITIONS) {
            sender.sendMessage("§cPosition must be between 1 and " + LeaderboardManager.MAX_POSITIONS + ".");
            return true;
        }
        leaderboardManager.setLeaderboardEntry(mode, position, target.getUniqueId(), target.getName() != null ? target.getName() : args[3], score);
        sender.sendMessage("§aLeaderboard entry saved.");
        return true;
    }

    private boolean handleReplays(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players may use this command.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /fb replays view <player>");
            return true;
        }
        if (!"view".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§cUsage: /fb replays view <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (target == null || target.getUniqueId() == null) {
            sender.sendMessage("§cPlayer not found.");
            return true;
        }
        String arenaName = plugin.getPlayerManager().getPlayerArena(player.getUniqueId());
        if (arenaName == null) {
            sender.sendMessage("§cYou must be in an arena to browse replays.");
            return true;
        }
        hotbarManager.openReplayMenuForPlayer(player, target.getUniqueId(), arenaName);
        sender.sendMessage("§aOpened replay list for " + target.getName() + " (offline player). ");
        return true;
    }

    private String normalizeMenuKey(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    private boolean handleList(CommandSender sender) {
        if (!arenaManager.hasArenas()) {
            sender.sendMessage("§eNo arenas have been created yet.");
            return true;
        }

        arenaManager.getArenas().forEach(arena -> {
            sender.sendMessage("§b" + arena.getName());
            sender.sendMessage("§7" + arena.getIslandCount() + " Islands");
            sender.sendMessage("§7" + arena.getLayout().name().substring(0, 1) + arena.getLayout().name().substring(1).toLowerCase());
            sender.sendMessage("§7Spacing: " + arena.getSpacing());
            sender.sendMessage("§7Origin: X=" + arena.getOriginX() + " Z=" + arena.getOriginZ());
        });
        return true;
    }

    private boolean handleHelp(CommandSender sender) {
        // /fb help output is now fully configurable via "help.message" in config.yml (a list of
        // lines, & color codes supported) - falls back to these exact lines if that key is ever
        // missing or empty, so a fresh/unedited install looks identical to before.
        List<String> defaultLines = List.of(
                "&6SkepiFB Commands",
                "&7Staff Commands",
                "&f/fb help &7- Shows this menu",
                "&f/fb add <arena> <schematic> <islandCount> [spacing] [straight|diagonal] &7- Adds a new arena",
                "&f/fb remove <arena> &7- Removes an arena",
                "&f/fb setfacing <arena> &7- Sets the spawn/respawn facing direction for an arena to your current facing",
                "&f/fb list &7- Lists arenas",
                "&f/fb join <arena> &7- Joins a FastBuilder arena",
                "&f/fb leave &7- Leaves your current arena",
                "&f/fb menu <add|remove|open|list> ... &7- Manages custom menus",
                "&f/fb reload &7- Reloads plugin files",
                "&f/fb replayinfo &7- Shows replay information",
                "&f/fb replays view <player> &7- Views another player's replays",
                "&f/fb coins add/remove/set <player> <amount> &7- Edits player coins",
                "&f/fb xp add/remove/set <player> <amount> &7- Edits player XP and updates rank UI",
                "&f/fb lb add <user> <mode> <time> &7- Adds/updates a leaderboard entry",
                "&f/fb lb remove <user> <mode> <time> &7- Removes a leaderboard entry",
                "&f/fb lb list <mode> &7- Shows a mode's leaderboard",
                "&f/fb lb user <mode> <player> <position> <score> &7- Manually sets a position",
                "&f/fb island add/remove/list ... &7- Manages island cosmetics",
                "&f/fb test setspawn/start/exit &7- Runs test mode commands"
        );
        List<String> lines = plugin.getConfigManager().getConfiguration().getStringList("help.message");
        if (lines == null || lines.isEmpty()) {
            lines = defaultLines;
        }
        for (String line : lines) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
        return true;
    }

    private boolean handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players may join arenas.");
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage("§cUsage: /fb join <arena>");
            return true;
        }

        String arenaName = args[1];
        SkepiFBPlugin.ArenaJoinResult result = plugin.joinPlayerToArena(player, arenaName);
        switch (result) {
            case ALREADY_IN_ARENA -> sender.sendMessage("§cYou are already in an arena.");
            case ARENA_NOT_FOUND -> sender.sendMessage("§cArena doesn't exist.");
            case NO_ISLANDS_AVAILABLE -> sender.sendMessage("§eNo islands are currently available.");
            case FAILED -> sender.sendMessage("§cUnable to prepare the island. Please try again in a moment.");
            case SUCCESS -> sender.sendMessage("§aYou joined arena " + arenaName + ".");
        }
        return true;
    }

    private boolean handleLeave(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players may leave arenas.");
            return true;
        }

        Player player = (Player) sender;
        if (!playerManager.isInArena(player.getUniqueId())) {
            sender.sendMessage("§cYou are not in an arena.");
            return true;
        }

        playerManager.leaveArena(player);
        scoreboardManager.hideScoreboard(player);
        sender.sendMessage("§aYou have left the arena.");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.getConfigManager().reload();
        plugin.getArenaManager().reloadArenas();
        hotbarManager.reload();
        scoreboardManager.reload();
        plugin.getShopManager().reload();
        // reload() above only re-reads shop.yml/shop_stats.yml into memory - it does not touch any
        // shop GUI a player already has open, since that inventory's contents were rendered once at
        // open-time and never automatically redraw just because the backing config changed. Without
        // this, every value in shop.yml (prices, names, materials, lore, new items, whole new pages)
        // was in fact being reloaded correctly, but anyone already looking at a shop menu when
        // /fb reload ran would keep seeing the stale, pre-reload version until they closed and
        // reopened it themselves - which read as "reload just doesn't change the menu".
        try {
            plugin.getShopManager().refreshOpenShopMenus();
        } catch (Throwable ignored) {
        }
        try {
            plugin.getStatboardManager().updateAll();
        } catch (Throwable ignored) {
        }
        sender.sendMessage("§aReload complete. Arena, config, hotbar, scoreboard, shop and statboard settings have been refreshed.");
        return true;
    }
}
