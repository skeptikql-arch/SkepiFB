package me.skepi.skepifb.chat;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Every "periodic-messages.interval-seconds" seconds (600 by default), broadcasts ONE
 * randomly-chosen message to every online player: one of the (up to 3) messages configured in
 * config.yml under "periodic-messages.messages", or the 4th, fixed SkepiFB credit/advertisement
 * message below. That 4th message is deliberately hardcoded here rather than read from
 * config.yml - it is not configurable, not listed in config.yml, and always takes part in the
 * random rotation alongside whatever the server owner has configured, so it can never be edited
 * out.
 * <p>
 * Mirrors StatboardManager's scheduleUpdater()/cancelUpdater() start/stop pattern (a stored task
 * ID, restartable) so this behaves the same way as every other periodic task in this plugin across
 * /fb reload and onDisable. Because the interval is read fresh from config.yml every time start()
 * runs, changing "interval-seconds" and running /fb reload takes effect immediately.
 */
public class PeriodicMessageManager {

    // The required SkepiFB credit/advertisement, pre-colored as a small multi-line banner. Never
    // read from config.yml, never editable, never listed anywhere in config.yml - always one of
    // the candidates in the random rotation below.
    private static final List<String> SKEPIFB_ADVERTISEMENT = List.of(
            "&8&m&l                                                            ",
            " &b&lSkepiFB &8» &7Running &b&lSkepiFB&7, made by &d&lSkepi",
            " &7Grab it here &8- &b&nhttps://github.com/skeptikql-arch/SkepiFB",
            "&8&m&l                                                            "
    );

    private final SkepiFBPlugin plugin;
    private final ConfigManager configManager;
    private final Random random = new Random();
    private int taskId = -1;

    public PeriodicMessageManager(SkepiFBPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * Starts (or restarts, if already running) the broadcast timer, using whatever
     * "periodic-messages.interval-seconds" is currently set to in config.yml. Safe to call again
     * on /fb reload - stop() is called first so a reload never ends up with two timers stacked on
     * top of each other, and the interval is re-read each time so a changed value takes effect
     * right away.
     */
    public void start() {
        stop();
        long intervalTicks = 20L * Math.max(1, configManager.getPeriodicMessageIntervalSeconds());
        taskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::broadcastRandomMessage, intervalTicks, intervalTicks)
                .getTaskId();
    }

    public void stop() {
        if (taskId > -1) {
            try {
                Bukkit.getScheduler().cancelTask(taskId);
            } catch (Throwable ignored) {
            }
            taskId = -1;
        }
    }

    private void broadcastRandomMessage() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }

        List<List<String>> pool = new ArrayList<>();
        for (String configured : configManager.getPeriodicMessages()) {
            if (configured == null) {
                continue;
            }
            String trimmed = configured.trim();
            if (trimmed.isEmpty() || "none".equalsIgnoreCase(trimmed)) {
                continue;
            }
            pool.add(List.of(configured));
        }
        // Always in the pool, regardless of what's configured (or not) above.
        pool.add(SKEPIFB_ADVERTISEMENT);

        List<String> chosen = pool.get(random.nextInt(pool.size()));
        for (String line : chosen) {
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }
}
