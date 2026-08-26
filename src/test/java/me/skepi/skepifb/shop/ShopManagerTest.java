package me.skepi.skepifb.shop;

import me.skepi.skepifb.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopManagerTest {

    @Test
    void loadsBlockShopItemsFromConfigurationFile() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Path tempDir = Files.createTempDirectory("skepifb-shop-test");
        File dataFolder = tempDir.toFile();
        when(plugin.getDataFolder()).thenReturn(dataFolder);

        File shopFile = new File(dataFolder, "shop.yml");
        Files.writeString(shopFile.toPath(), """
                shops:
                  block_shop:
                    title: "&dBlock Shop"
                    size: 54
                    page-size: 21
                    categories:
                      blocks:
                        title: "&6Blocks"
                        items:
                          0:
                            material: STONE
                            name: "&7Stone"
                            price: 100
                            default: true
                """);

        ShopManager shopManager = new ShopManager(plugin, mock(ConfigManager.class), shopFile);

        assertTrue(shopManager.getShopDefinition("block_shop").isPresent());
        assertEquals(1, shopManager.getShopDefinition("block_shop").get().getCategories().size());
    }
}
