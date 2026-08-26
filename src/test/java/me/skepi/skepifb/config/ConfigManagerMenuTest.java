package me.skepi.skepifb.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ConfigManagerMenuTest {

    @Test
    void loadsMenuItemsByConfiguredSlot() throws Exception {
        File tempDir = Files.createTempDirectory("skepifb-menu-test").toFile();
        tempDir.deleteOnExit();

        File menuFile = new File(tempDir, "menu.yml");
        Files.writeString(menuFile.toPath(), """
                settings_menu:
                  title: "&aSettings"
                  size: 27
                  items:
                    11:
                      material: CLOCK
                      name: "&eMode"
                      action: mode_changer_menu
                    13:
                      material: ENDER_CHEST
                      name: "&dCosmetics"
                      action: menu
                      menu: cosmetics_menu
                """, StandardCharsets.UTF_8);

        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir);
        when(plugin.getLogger()).thenCallRealMethod();
        ConfigManager configManager = new ConfigManager(plugin);

        Map<Integer, org.bukkit.configuration.ConfigurationSection> items = configManager.getMenuItemsBySlot("settings_menu");

        assertEquals(2, items.size());
        assertTrue(items.containsKey(11));
        assertEquals("CLOCK", items.get(11).getString("material"));
        assertEquals("mode_changer_menu", items.get(11).getString("action"));
    }

    private static final class TestPlugin extends JavaPlugin {
        private final File dataFolder;

        private TestPlugin(File dataFolder) {
            this.dataFolder = dataFolder;
        }

    }
}
