package me.skepi.skepifb.shop;

import me.skepi.skepifb.SkepiFBPlugin;
import me.skepi.skepifb.config.ConfigManager;
import me.skepi.skepifb.player.PlayerManager;
import me.skepi.skepifb.player.PlayerSession;
import me.skepi.skepifb.stats.PlayerStatsManager;
import me.skepi.skepifb.timer.AttemptSession;
import me.skepi.skepifb.arena.Arena;
import me.skepi.skepifb.arena.ArenaIsland;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.Collection;
import java.util.LinkedHashMap;

public class ShopManager implements Listener {

    private static final String SHOP_CONFIG_FILE_NAME = "shop.yml";
    private static final String SHOP_STATS_FILE_NAME = "shop_stats.yml";
    private static final String ISLAND_SHOP_ID = "island_shop";
    private static final String TAG_SHOP_ID = "tag_shop";

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private File shopConfigFile;
    private File shopStatsFile;
    private YamlConfiguration shopConfiguration;
    private YamlConfiguration shopStatsConfiguration;
    private final Map<String, ShopDefinition> shops = new LinkedHashMap<>();

    private void logInfo(String message) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().info(message);
        } else {
            System.out.println(message);
        }
    }

    private void logWarning(String message) {
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().warning(message);
        } else {
            System.out.println("WARNING: " + message);
        }
    }

    public ShopManager(JavaPlugin plugin, ConfigManager configManager) {
        this(plugin, configManager, null);
    }

    public ShopManager(JavaPlugin plugin, ConfigManager configManager, File customShopFile) {
        this.plugin = plugin;
        this.configManager = configManager;
        File dataFolder = plugin.getDataFolder();
        if (customShopFile != null) {
            File parent = customShopFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            this.shopConfigFile = customShopFile;
            this.shopStatsFile = new File((parent != null ? parent : dataFolder), SHOP_STATS_FILE_NAME);
        } else {
            if (dataFolder == null || !dataFolder.exists()) {
                if (dataFolder == null) {
                    dataFolder = plugin.getDataFolder();
                }
                if (dataFolder != null && !dataFolder.exists()) dataFolder.mkdirs();
            }
            this.shopConfigFile = new File(dataFolder, SHOP_CONFIG_FILE_NAME);
            this.shopStatsFile = new File(dataFolder, SHOP_STATS_FILE_NAME);
        }
        createDefaultShopConfigIfMissing();
        createDefaultShopStatsFileIfMissing();
        this.shopConfiguration = YamlConfiguration.loadConfiguration(this.shopConfigFile);
        this.shopStatsConfiguration = YamlConfiguration.loadConfiguration(this.shopStatsFile);
        ensureCustomShops();
        loadShops();
    }

      private void ensureCustomShops() {
        ConfigurationSection shopsSection = this.shopConfiguration.getConfigurationSection("shops");
        if (shopsSection == null) {
          shopsSection = this.shopConfiguration.createSection("shops");
        }
        boolean changed = false;
        changed |= mergeBundledShopDefaultsIfMissing();
        String[] keys = new String[]{"tools_shop", "reset_animation", "firework_color", "practice_shop", "island_shop", "rankups", "tag_shop"};
        for (String key : keys) {
          ConfigurationSection s = shopsSection.getConfigurationSection(key);
          if (s == null) {
            s = shopsSection.createSection(key);
            s.set("title", key.replace('_', ' '));
            int size = 27;
            if ("practice_shop".equals(key) || "island_shop".equals(key)) size = 54;
            if ("tag_shop".equals(key) || "rankups".equals(key)) size = 27;
            s.set("size", size);
            s.set("page-size", 21);
            s.createSection("categories");
            changed = true;
          }
          changed |= ensureDefaultShopStructure(s, key);
        }
        if (changed) {
          try {
            this.shopConfiguration.save(this.shopConfigFile);
          } catch (IOException ex) {
            logWarning("Unable to save updated shop.yml: " + ex.getMessage());
          }
        }
      }

      private boolean mergeBundledShopDefaultsIfMissing() {
        try (InputStream bundledStream = getBundledShopResourceStream()) {
          if (bundledStream == null) {
            return false;
          }
          String bundledYaml = new String(bundledStream.readAllBytes(), StandardCharsets.UTF_8);
          YamlConfiguration bundledConfig = new YamlConfiguration();
          bundledConfig.loadFromString(bundledYaml);
          ConfigurationSection bundledShops = bundledConfig.getConfigurationSection("shops");
          if (bundledShops == null) {
            return false;
          }
          ConfigurationSection shopsSection = this.shopConfiguration.getConfigurationSection("shops");
          if (shopsSection == null) {
            shopsSection = this.shopConfiguration.createSection("shops");
          }
          boolean changed = false;
          for (String shopKey : bundledShops.getKeys(false)) {
            ConfigurationSection bundledShopSection = bundledShops.getConfigurationSection(shopKey);
            if (bundledShopSection == null) {
              continue;
            }
            ConfigurationSection existingShopSection = shopsSection.getConfigurationSection(shopKey);
            if (existingShopSection == null) {
              shopsSection.set(shopKey, bundledShopSection.getValues(true));
              changed = true;
            } else {
              changed |= mergeMissingConfigurationSection(existingShopSection, bundledShopSection);
            }
          }
          return changed;
        } catch (Throwable ex) {
          logWarning("Unable to merge bundled shop defaults: " + ex.getMessage());
          return false;
        }
      }

      private boolean mergeMissingConfigurationSection(ConfigurationSection target, ConfigurationSection source) {
        boolean changed = false;
        for (String key : source.getKeys(false)) {
          if (!source.isConfigurationSection(key)) {
            if (!target.isSet(key)) {
              target.set(key, source.get(key));
              changed = true;
            }
            continue;
          }
          ConfigurationSection sourceSection = source.getConfigurationSection(key);
          if (sourceSection == null) {
            continue;
          }
          ConfigurationSection targetSection = target.getConfigurationSection(key);
          if (targetSection == null) {
            target.set(key, sourceSection.getValues(true));
            changed = true;
            continue;
          }

          boolean targetHasNestedStructure = !targetSection.getKeys(false).isEmpty();
          if (targetHasNestedStructure && ("categories".equals(key) || "items".equals(key))) {
            continue;
          }

          if (targetHasNestedStructure && !"category-selector".equals(key)) {
            for (String nestedKey : sourceSection.getKeys(false)) {
              if (!sourceSection.isConfigurationSection(nestedKey) && !targetSection.isSet(nestedKey)) {
                targetSection.set(nestedKey, sourceSection.get(nestedKey));
                changed = true;
              }
            }
            continue;
          }

          changed |= mergeMissingConfigurationSection(targetSection, sourceSection);
        }
        return changed;
      }

      private InputStream getBundledShopResourceStream() {
        InputStream bundledStream = plugin.getResource(SHOP_CONFIG_FILE_NAME);
        if (bundledStream != null) {
          return bundledStream;
        }
        return getClass().getClassLoader().getResourceAsStream(SHOP_CONFIG_FILE_NAME);
      }

        private boolean ensureDefaultShopStructure(ConfigurationSection shopSection, String shopKey) {
                boolean changed = false;

                if (!shopSection.isSet("category-selector")) {
                        ConfigurationSection selector = shopSection.createSection("category-selector");
                        switch (shopKey) {
                                case "tools_shop" -> {
                                        selector.createSection("tools").set("slot", 0);
                                        selector.getConfigurationSection("tools").set("item", "IRON_PICKAXE");
                                        selector.getConfigurationSection("tools").set("name", "&bTools");
                                        selector.getConfigurationSection("tools").set("lore", List.of("&7Click to view"));
                                        changed = true;
                                }
                                case "reset_animation" -> {
                                        selector.createSection("colors").set("slot", 0);
                                        selector.getConfigurationSection("colors").set("item", "FIREWORK_ROCKET");
                                        selector.getConfigurationSection("colors").set("name", "&6Reset Animation");
                                        selector.getConfigurationSection("colors").set("lore", List.of("&7Click to view"));
                                        changed = true;
                                }
                                case "firework_color" -> {
                                        selector.createSection("colors").set("slot", 0);
                                        selector.getConfigurationSection("colors").set("item", "FIREWORK_STAR");
                                        selector.getConfigurationSection("colors").set("name", "&6Firework Color");
                                        selector.getConfigurationSection("colors").set("lore", List.of("&7Click to view"));
                                        changed = true;
                                }
                                case "practice_shop" -> {
                                        selector.createSection("practice").set("slot", 0);
                                        selector.getConfigurationSection("practice").set("item", "WHITE_TERRACOTTA");
                                        selector.getConfigurationSection("practice").set("name", "&bPractice Blocks");
                                        selector.getConfigurationSection("practice").set("lore", List.of("&7Click to view"));
                                        changed = true;
                                }
                                case "island_shop" -> {
                                        selector.createSection("islands").set("slot", 0);
                                        selector.getConfigurationSection("islands").set("item", "GRASS_BLOCK");
                                        selector.getConfigurationSection("islands").set("name", "&aIslands");
                                        selector.getConfigurationSection("islands").set("lore", List.of("&7Click to view"));
                                        changed = true;
                                }
                                case "tag_shop" -> {
                                        selector.createSection("tags").set("slot", 0);
                                        selector.getConfigurationSection("tags").set("item", "NAME_TAG");
                                        selector.getConfigurationSection("tags").set("name", "&dTags");
                                        selector.getConfigurationSection("tags").set("lore", List.of("&7Click to view"));
                                        changed = true;
                                }
                                case "rankups" -> {
                                        selector.createSection("ranks").set("slot", 0);
                                        selector.getConfigurationSection("ranks").set("item", "EXPERIENCE_BOTTLE");
                                        selector.getConfigurationSection("ranks").set("name", "&eRankups");
                                        selector.getConfigurationSection("ranks").set("lore", List.of("&7Click to view"));
                                        changed = true;
                                }
                        }
                }

                ConfigurationSection categoriesSection = shopSection.getConfigurationSection("categories");
                if (categoriesSection == null) {
                        categoriesSection = shopSection.createSection("categories");
                        changed = true;
                }

                if (!categoriesSection.getKeys(false).isEmpty()) {
                        return changed;
                }

                switch (shopKey) {
                        case "tools_shop" -> {
                                ConfigurationSection category = categoriesSection.createSection("tools");
                                category.set("title", "&bTools");
                                ConfigurationSection items = category.createSection("items");
                                items.createSection("wooden_pickaxe").set("material", "WOODEN_PICKAXE");
                                items.getConfigurationSection("wooden_pickaxe").set("name", "&fWooden Pickaxe");
                                items.getConfigurationSection("wooden_pickaxe").set("price", 0);
                                items.getConfigurationSection("wooden_pickaxe").set("default", true);
                                // (trimmed) add additional tool defaults as before
                                changed = true;
                        }
                        case "reset_animation" -> {
                                ConfigurationSection category = categoriesSection.createSection("colors");
                                category.set("title", "&6Reset Animation");
                                ConfigurationSection items = category.createSection("items");
                                items.createSection("none").set("material", "GRAY_STAINED_GLASS_PANE");
                                items.getConfigurationSection("none").set("name", "&7None");
                                items.getConfigurationSection("none").set("price", 0);
                                items.getConfigurationSection("none").set("default", true);
                                items.getConfigurationSection("none").set("slot", 10);

                                items.createSection("falling").set("material", "SAND");
                                items.getConfigurationSection("falling").set("name", "&fFalling");
                                items.getConfigurationSection("falling").set("price", 0);
                                items.getConfigurationSection("falling").set("slot", 11);

                                items.createSection("ice").set("material", "ICE");
                                items.getConfigurationSection("ice").set("name", "&bIce");
                                items.getConfigurationSection("ice").set("price", 0);
                                items.getConfigurationSection("ice").set("slot", 12);

                                items.createSection("blue").set("material", "BLUE_CONCRETE");
                                items.getConfigurationSection("blue").set("name", "&9Blue");
                                items.getConfigurationSection("blue").set("price", 0);
                                items.getConfigurationSection("blue").set("slot", 13);

                                items.createSection("fire").set("material", "FIRE");
                                items.getConfigurationSection("fire").set("name", "&cFire");
                                items.getConfigurationSection("fire").set("price", 0);
                                items.getConfigurationSection("fire").set("slot", 14);

                                items.createSection("creative_mode").set("material", "GRASS_BLOCK");
                                items.getConfigurationSection("creative_mode").set("name", "&aCreative Mode");
                                items.getConfigurationSection("creative_mode").set("price", 0);
                                items.getConfigurationSection("creative_mode").set("slot", 15);

                                items.createSection("magic_swirl").set("material", "AMETHYST_SHARD");
                                items.getConfigurationSection("magic_swirl").set("name", "&5Magic Swirl");
                                items.getConfigurationSection("magic_swirl").set("price", 0);
                                items.getConfigurationSection("magic_swirl").set("slot", 16);

                                changed = true;
                        }
                        case "firework_color" -> {
                                ConfigurationSection category = categoriesSection.createSection("colors");
                                category.set("title", "&6Firework Color");
                                ConfigurationSection items = category.createSection("items");
                                items.createSection("white_dye").set("material", "WHITE_DYE");
                                items.getConfigurationSection("white_dye").set("name", "&fWhite");
                                items.getConfigurationSection("white_dye").set("price", 0);
                                items.getConfigurationSection("white_dye").set("default", true);
                                items.getConfigurationSection("white_dye").set("slot", 10);
                                String[] fireworkDyes = new String[]{"orange_dye","magenta_dye","light_blue_dye","yellow_dye","lime_dye","pink_dye","gray_dye","light_gray_dye","cyan_dye","purple_dye","blue_dye","brown_dye","green_dye","red_dye","black_dye"};
                                int fwSlot = 11;
                                for (String d : fireworkDyes) {
                                        items.createSection(d).set("material", d.toUpperCase(Locale.ROOT));
                                        items.getConfigurationSection(d).set("name", "&f" + d.replace("_dye", "").replace('_', ' '));
                                        items.getConfigurationSection(d).set("price", 75);
                                        items.getConfigurationSection(d).set("slot", fwSlot);
                                        fwSlot++;
                                }
                                changed = true;
                        }
                        case "practice_shop" -> {
                                ConfigurationSection category = categoriesSection.createSection("practice");
                                category.set("title", "&bPractice Blocks");
                                ConfigurationSection items = category.createSection("items");
                                items.createSection("white_terracotta").set("material", "WHITE_TERRACOTTA");
                                items.getConfigurationSection("white_terracotta").set("name", "&fWhite Terracotta");
                                items.getConfigurationSection("white_terracotta").set("price", 0);
                                items.getConfigurationSection("white_terracotta").set("default", true);
                                changed = true;
                        }
                        case "island_shop" -> {
                                ConfigurationSection category = categoriesSection.createSection("islands");
                                category.set("title", "&aIslands");
                                ConfigurationSection items = category.createSection("items");
                                ConfigurationSection defaultItem = items.createSection("default");
                                defaultItem.set("material", "GRASS_BLOCK");
                                defaultItem.set("name", "&aRestore to Default");
                                defaultItem.set("price", 0);
                                defaultItem.set("default", true);
                                changed = true;
                        }
                        case "tag_shop" -> {
                                ConfigurationSection category = categoriesSection.createSection("tags");
                                category.set("title", "&dTags");
                                category.createSection("items");
                                changed = true;
                        }
                        case "rankups" -> {
                                ConfigurationSection category = categoriesSection.createSection("ranks");
                                category.set("title", "&eRankups");
                                category.createSection("items");
                                changed = true;
                        }
                }

                return changed;
        }

    private void logErrorMissingShop(String shopId) {
        logWarning("ERROR: Shop definition not found: " + shopId);
        logWarning("Available shops: " + String.join(", ", shops.keySet()));
    }

    /**
     * Opens the top-level cosmetics_menu hub (the menu.yml-driven screen that links to every
     * individual cosmetic shop). This is what every cosmetic menu's back button returns to.
     */
    private void openCosmeticsMenu(Player player) {
        try {
            ((SkepiFBPlugin) plugin).getHotbarManager().openBlankSubmenu(player, "cosmetics_menu");
        } catch (Throwable ignored) {
        }
    }

    public void openShopMenu(Player player, String shopId) {
        if (shopId == null || shopId.isBlank()) {
            player.sendMessage(ChatColor.RED + "That shop is not available.");
            return;
        }

        ensureDefaultOwned(player);

        if (ISLAND_SHOP_ID.equalsIgnoreCase(shopId)) {
            openIslandShopPage(player, null, 0);
            return;
        }

        if (TAG_SHOP_ID.equalsIgnoreCase(shopId)) {
            openTagShopPage(player);
            return;
        }

        Optional<ShopDefinition> shopDefinition = getShopDefinition(shopId);
        if (shopDefinition.isEmpty()) {
            logErrorMissingShop(shopId);
            player.sendMessage(ChatColor.RED + "That shop is not available.");
            return;
        }

        ShopDefinition shop = shopDefinition.get();

        // For cosmetics shops that do not have a category selector, open first category directly
        if ("tools_shop".equalsIgnoreCase(shop.getId()) || "reset_animation".equalsIgnoreCase(shop.getId()) || "firework_color".equalsIgnoreCase(shop.getId()) || "practice_shop".equalsIgnoreCase(shop.getId()) || "tag_shop".equalsIgnoreCase(shop.getId()) || "rankups".equalsIgnoreCase(shop.getId())) {
          // open directly into the first category page
          if (!shop.getCategories().isEmpty()) {
            String categoryKey = shop.getCategories().get(0).getKey();
            openCategoryPage(player, shop.getId(), categoryKey, 0);
            return;
          }
        }

        // Category selector menus are always 9x3 (27 slots) - block_shop used to be hard-coded to a
        // 9x1 (size 9) selector here while its shop.yml category-selector slots were configured for
        // a 9x3 layout (categories in row 2, e.g. slot 11) - since the inventory was only 9 slots
        // wide/tall, populateCategorySelectorMenu's own bounds check (slot >= inventory.getSize())
        // silently skipped every category configured at slot 9 or above, so nothing in row 2 or 3
        // ever rendered. There's nothing block_shop-specific about the selector's size; it's just
        // another category-selector inventory like every other shop's.
        int selectorSize = 27;
        Inventory inventory = Bukkit.createInventory(new CategorySelectorInventoryHolder(shop.getId()), selectorSize,
            ChatColor.translateAlternateColorCodes('&', shop.getTitle() + " - Categories"));
        populateCategorySelectorMenu(inventory, shop);
        player.openInventory(inventory);
    }

    public void openCategoryMenu(Player player, String shopId, String categoryKey) {
        Optional<ShopDefinition> shopDefinition = getShopDefinition(shopId);
        if (shopDefinition.isEmpty()) {
            player.sendMessage(ChatColor.RED + "That shop is not available.");
            return;
        }

        ShopDefinition shop = shopDefinition.get();
        ShopCategoryDefinition category = shop.getCategories().stream()
                .filter(c -> c.getKey().equalsIgnoreCase(categoryKey))
                .findFirst()
                .orElse(null);

        if (category == null) {
            player.sendMessage(ChatColor.RED + "That category is not available.");
            return;
        }

        openCategoryPage(player, shop.getId(), category.getKey(), 0);
    }

    private void populateCategorySelectorMenu(Inventory inventory, ShopDefinition shop) {
        // Fill every slot with a gray glass pane first, then place category buttons on top -
        // whatever's left over (anything without a configured category) stays glass.
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName(" ");
            pane.setItemMeta(paneMeta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane);
        }

        ConfigurationSection selectorSection = shopConfiguration.getConfigurationSection("shops." + shop.getId() + ".category-selector");
        if (selectorSection == null) {
            return;
        }

        for (String categoryKey : selectorSection.getKeys(false)) {
          ConfigurationSection categorySection = selectorSection.getConfigurationSection(categoryKey);
          if (categorySection == null) {
            continue;
          }

          int slot = categorySection.getInt("slot", -1);
          if (slot < 0 || slot >= inventory.getSize()) {
            continue;
          }

            String itemMaterialName = categorySection.getString("item", "STONE");
            Material material = Material.matchMaterial(itemMaterialName);
            if (material == null) {
                material = Material.STONE;
            }

            String name = categorySection.getString("name", categoryKey);
            List<String> lore = categorySection.getStringList("lore");
            String targetCategory = categorySection.getString("category", categoryKey);
            String action = categorySection.getString("action", "category");
            String actionKey = "select_category";
            if (!"category".equalsIgnoreCase(action)) {
                actionKey = "select_category";
            }

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                if (!lore.isEmpty()) {
                    List<String> translatedLore = new ArrayList<>();
                    for (String loreLine : lore) {
                        translatedLore.add(ChatColor.translateAlternateColorCodes('&', loreLine));
                    }
                    meta.setLore(translatedLore);
                }
                PersistentDataContainer container = meta.getPersistentDataContainer();
                container.set(NamespacedKey.fromString("skepifb:shop_action"), PersistentDataType.STRING, actionKey);
                container.set(NamespacedKey.fromString("skepifb:shop_target"), PersistentDataType.STRING, shop.getId() + ":" + targetCategory);
                item.setItemMeta(meta);
            }

            inventory.setItem(slot, item);
        }

        inventory.setItem(bottomMiddleSlot(inventory.getSize()), createBackToCosmeticsItem());
    }

    private static final List<Integer> BLOCK_SHOP_PAGE_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );

    private void populateCategoryMenu(Inventory inventory, Player player, ShopDefinition shop, ShopCategoryDefinition category, int page) {
      fillBackground(inventory);

      ItemStack header = createHeaderItem(category, shop, player);
      if (inventory.getSize() > 4) inventory.setItem(4, header);

      List<ShopItemDefinition> items = category.getItems();

      int size = inventory.getSize();
      List<Integer> usable = getInteriorSlots(size);
      if ("block_shop".equalsIgnoreCase(shop.getId())) {
          usable = BLOCK_SHOP_PAGE_SLOTS;
      }

      int pageSize = usable.size();
      if (!"block_shop".equalsIgnoreCase(shop.getId())) {
          pageSize = Math.max(1, Math.min(shop.getPageSize(), usable.size()));
      }
      int pageCount = Math.max(1, (int) Math.ceil(items.size() / (double) pageSize));
      int normalizedPage = Math.max(0, Math.min(page, pageCount - 1));
      int startIndex = normalizedPage * pageSize;
      int endIndex = Math.min(startIndex + pageSize, items.size());
      for (int i = startIndex; i < endIndex; i++) {
        ShopItemDefinition itemDefinition = items.get(i);
        int slot = usable.get(i - startIndex);
        ItemStack builtItem = createShopItem(itemDefinition, player);
        if (slot >= 0 && slot < inventory.getSize()) {
          inventory.setItem(slot, builtItem);
        }
      }

            // If this is the reset_animation shop and a 'none' option exists, force it into slot 34 (configurable default)
            try {
                if ("reset_animation".equalsIgnoreCase(shop.getId())) {
                    for (ShopItemDefinition def : items) {
                        if (def.getKey().equalsIgnoreCase("none")) {
                            if (34 >= 0 && 34 < inventory.getSize()) {
                                inventory.setItem(34, createShopItem(def, player));
                            }
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}

      int prevSlot = Math.max(0, size - 9);
      int nextSlot = size - 1;

      if (normalizedPage > 0) {
        inventory.setItem(prevSlot, createPageItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", "shop_page", shop.getId(), category.getKey(), normalizedPage - 1));
      } else {
        inventory.setItem(prevSlot, createBarrierItem(ChatColor.GRAY + "Previous Page"));
      }

      if (normalizedPage + 1 < pageCount) {
        inventory.setItem(nextSlot, createPageItem(Material.ARROW, ChatColor.YELLOW + "Next Page", "shop_page", shop.getId(), category.getKey(), normalizedPage + 1));
      } else {
        inventory.setItem(nextSlot, createBarrierItem(ChatColor.GRAY + "Next Page"));
      }

      int backSlot = bottomMiddleSlot(size);
      if (backSlot != prevSlot && backSlot != nextSlot) {
        inventory.setItem(backSlot, createBackToCosmeticsItem());
      }
    }


    private int calculatePageSizeForShop(ShopDefinition shop, ShopCategoryDefinition category) {
        int size = Math.max(1, shop.getSize());
        List<Integer> usable = getInteriorSlots(size);
        if ("block_shop".equalsIgnoreCase(shop.getId())) {
            usable = BLOCK_SHOP_PAGE_SLOTS;
        }
        return Math.max(1, Math.min(shop.getPageSize(), usable.size()));
    }

    private Material getShopIconMaterial(ShopDefinition shop) {
        if (shop == null) {
            return Material.BARRIER;
        }
        for (ShopCategoryDefinition category : shop.getCategories()) {
            for (ShopItemDefinition item : category.getItems()) {
                if (item != null && item.getMaterial() != null) {
                    return item.getMaterial();
                }
            }
        }
        return Material.BOOK;
    }

    private int getPageCountForShop(ShopDefinition shop) {
        if (shop == null) {
            return 1;
        }
        int itemsInLargestCategory = 0;
        for (ShopCategoryDefinition category : shop.getCategories()) {
            itemsInLargestCategory = Math.max(itemsInLargestCategory, category.getItems().size());
        }
        int pageSize = Math.max(1, shop.getPageSize());
        return Math.max(1, (int) Math.ceil(itemsInLargestCategory / (double) pageSize));
    }

    private int getPriceForSelectionTarget(ShopDefinition shop, String categoryKey, String itemKey) {
        if (shop == null) {
            return 0;
        }
        for (ShopCategoryDefinition category : shop.getCategories()) {
            if (!category.getKey().equalsIgnoreCase(categoryKey)) {
                continue;
            }
            for (ShopItemDefinition item : category.getItems()) {
                if (item.getKey().equalsIgnoreCase(itemKey)) {
                    return item.getPrice();
                }
            }
        }
        return 0;
    }

    private ItemStack createHeaderItem(ShopCategoryDefinition category, ShopDefinition shop, Player player) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(ChatColor.GOLD + ChatColor.translateAlternateColorCodes('&', category.getTitle()));
        meta.setLore(List.of(
                ChatColor.GRAY + "Shop: " + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', shop.getTitle())),
                ChatColor.GRAY + "Owned items: " + countOwnedItems(player.getUniqueId(), shop),
                ChatColor.YELLOW + "Click an item to purchase or equip it"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createShopItem(ShopItemDefinition itemDefinition, Player player) {
      Material material = getSafeMenuMaterial(itemDefinition.getMaterial());
      ItemStack item = new ItemStack(material, Math.max(1, itemDefinition.getAmount()));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String translatedName = ChatColor.translateAlternateColorCodes('&', itemDefinition.getName());
        boolean owned = isOwned(player.getUniqueId(), itemDefinition);
        boolean permissionLocked = plugin instanceof SkepiFBPlugin sfb
                && !sfb.getPermissionsManager().canPurchaseShopItem(player, itemDefinition.getShopKey(), itemDefinition.getCategoryKey(), itemDefinition.getKey());
        String displayName;
        List<String> lore = new ArrayList<>();
        List<String> configuredLore = itemDefinition.getLore();
        if (configuredLore != null && !configuredLore.isEmpty()) {
            for (String loreLine : configuredLore) {
                lore.add(ChatColor.translateAlternateColorCodes('&', loreLine));
            }
        }

        Optional<String> equipped;
        if (ISLAND_SHOP_ID.equalsIgnoreCase(itemDefinition.getShopKey())) {
            String mode = getCurrentPlayerIslandMode(player);
            equipped = getEquippedShopItem(player.getUniqueId(), itemDefinition.getShopKey(), mode);
        } else {
            equipped = getEquippedShopItem(player.getUniqueId(), itemDefinition.getShopKey());
        }
        boolean isSelected = false;
        if (equipped.isPresent()) {
            isSelected = isSelectedShopItem(itemDefinition.getShopKey(), itemDefinition.getCategoryKey(), itemDefinition.getKey(), equipped.get());
        } else if (itemDefinition.isDefaultItem()) {
            isSelected = true;
        }
        boolean isRankups = "rankups".equalsIgnoreCase(itemDefinition.getShopKey());
        boolean isCurrentRankup = false;
        int currentRankLevel = 0;
        SkepiFBPlugin mainPlugin = plugin instanceof SkepiFBPlugin ? (SkepiFBPlugin) plugin : null;
        if (isRankups && mainPlugin != null) {
            currentRankLevel = mainPlugin.getTimerManager().getLevelForXp(mainPlugin.getStatsManager().getXp(player.getUniqueId()));
            isCurrentRankup = itemDefinition.getKey().equalsIgnoreCase("level_" + currentRankLevel);
            if (isCurrentRankup) {
                isSelected = true;
            }
        }
        if (isSpecialNoneItem(itemDefinition.getShopKey(), itemDefinition.getCategoryKey(), itemDefinition.getKey())) {
            displayName = ChatColor.RED + translatedName;
            if (isSelected) {
                lore.add(ChatColor.GREEN + "Currently Selected");
            } else {
                lore.add(ChatColor.YELLOW + "Click to clear selection");
            }
        } else if (isRankups) {
            displayName = ChatColor.AQUA + translatedName;
            int rankLevel = parseRankupLevel(itemDefinition.getKey());
            if (isCurrentRankup) {
                lore.add(ChatColor.GOLD + "★ Your Current Level");
                lore.add(ChatColor.GREEN + "✓ Unlocked");
            } else if (rankLevel > 0 && rankLevel < currentRankLevel) {
                lore.add(ChatColor.GREEN + "✓ Unlocked");
                lore.add(ChatColor.YELLOW + "Click to equip display");
            } else if (rankLevel > 0) {
                lore.add(ChatColor.GRAY + "Requires Level " + rankLevel);
            } else {
                lore.add(ChatColor.GRAY + "Rankup display item");
            }
        } else {
            displayName = (permissionLocked ? ChatColor.RED : (owned ? ChatColor.GREEN : ChatColor.YELLOW)) + translatedName;
            if (permissionLocked) {
                lore.add(ChatColor.RED + "Requires special permission");
            }
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.GOLD + itemDefinition.getPrice() + " coins");
            if (owned) {
                lore.add(ChatColor.GREEN + "Owned");
                if (isSelected) {
                    lore.add(ChatColor.GREEN + "Currently Selected");
                } else if (!permissionLocked) {
                    lore.add(ChatColor.YELLOW + "Click to equip");
                }
            } else {
                lore.add(ChatColor.RED + "Not owned");
                if (!permissionLocked) {
                    lore.add(ChatColor.YELLOW + "Click to purchase");
                }
            }
            if (itemDefinition.isDefaultItem()) {
                lore.add(ChatColor.AQUA + "Starter item");
            }
        }

        meta.setDisplayName(displayName);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (!"rankups".equalsIgnoreCase(itemDefinition.getShopKey())) {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(NamespacedKey.fromString("skepifb:shop_action"), PersistentDataType.STRING, "purchase");
            container.set(NamespacedKey.fromString("skepifb:shop_target"), PersistentDataType.STRING,
                    itemDefinition.getShopKey() + ":" + itemDefinition.getCategoryKey() + ":" + itemDefinition.getKey());
        }
        NamespacedKey key = NamespacedKey.minecraft("unbreaking");
        Enchantment ench = Enchantment.getByKey(key);
        if (ench != null) {
            if (isSelected) {
                meta.addEnchant(ench, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } else {
                meta.removeEnchant(ench);
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        } else {
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static final List<String> INVALID_SHOP_MATERIAL_TOKENS = List.of(
            "SLAB", "STAIRS", "FENCE", "WALL", "SIGN", "BUTTON", "PRESSURE_PLATE",
            "CARPET", "CORAL", "CANDLE", "CHAIN", "SCAFFOLDING", "TORCH",
            "LANTERN", "CAMPFIRE", "PICKLE", "VINE", "LEAVES", "GATEWAY",
            "PANE", "BED"
    );

    private int parseRankupLevel(String itemKey) {
        if (itemKey == null || !itemKey.startsWith("level_")) {
            return 0;
        }
        try {
            return Integer.parseInt(itemKey.substring("level_".length()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private Material sanitizeMenuMaterial(Material material) {
        if (material == null || !material.isItem() || !material.isBlock()) {
            return null;
        }
        String name = material.name();
        for (String token : INVALID_SHOP_MATERIAL_TOKENS) {
            if (name.contains(token)) {
                return null;
            }
        }
        return material;
    }

    private Material getSafeMenuMaterial(Material material) {
        if (material == null || !material.isItem()) {
            return Material.BARRIER;
        }
        return material;
    }

    private Material parseShopMaterial(String materialName, String source) {
        if (materialName == null || materialName.isBlank()) {
            logWarning("Invalid shop material: missing material for " + source);
            return null;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            logWarning("Invalid shop material: '" + materialName + "' for " + source);
        }
        return material;
    }

    private ItemStack createPageItem(Material material, String name, String action, String shopId, String categoryKey, int page) {
      Material menuMaterial = material != null ? material : Material.ARROW;
      ItemStack item = new ItemStack(menuMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(name);
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(NamespacedKey.fromString("skepifb:shop_action"), PersistentDataType.STRING, action);
        container.set(NamespacedKey.fromString("skepifb:shop_target"), PersistentDataType.STRING, shopId + ":" + categoryKey + ":" + page);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Builds the "back to cosmetics menu" button placed in the bottom-middle slot of every
     * cosmetic-shop GUI (block shop, tools shop, reset animation, firework color, practice
     * blocks, rankups, island shop and tag shop). Clicking it always returns to the top-level
     * cosmetics_menu hub, regardless of how deep the player navigated to get here.
     */
    private ItemStack createBackToCosmeticsItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "\u2190 Back to Cosmetics");
            meta.setLore(List.of(ChatColor.GRAY + "Return to the main cosmetics menu"));
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(NamespacedKey.fromString("skepifb:shop_action"), PersistentDataType.STRING, "back_to_cosmetics");
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * The bottom-middle slot of an inventory whose size is a multiple of 9 - e.g. 22 for a
     * 27-slot menu, 49 for a 54-slot menu. Used to place the back button consistently regardless
     * of which cosmetic shop's GUI size is being rendered.
     */
    private int bottomMiddleSlot(int inventorySize) {
        int size = Math.max(9, inventorySize);
        int lastRowStart = (size / 9 - 1) * 9;
        return lastRowStart + 4;
    }

    private ItemStack createBarrierItem(String name) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBackground(Inventory inventory) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            if (!isBorderSlot(slot, size)) {
                continue;
            }
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, pane);
            }
        }
    }

    private boolean isBorderSlot(int slot, int size) {
        if (size < 9) {
            return true;
        }
        int row = slot / 9;
        int col = slot % 9;
        int rows = size / 9;
        return row == 0 || row == rows - 1 || col == 0 || col == 8;
    }

    private int countOwnedItems(UUID playerUuid, ShopDefinition shop) {
        int owned = 0;
        for (ShopCategoryDefinition category : shop.getCategories()) {
            for (ShopItemDefinition item : category.getItems()) {
                if (isOwned(playerUuid, item)) {
                    owned++;
                }
            }
        }
        return owned;
    }

    private boolean isOwned(UUID playerUuid, ShopItemDefinition itemDefinition) {
        return getOwnedItems(playerUuid, itemDefinition.getShopKey()).contains(itemDefinition.getKey());
    }

    private List<String> getOwnedItems(UUID playerUuid, String shopId) {
        String path = "players." + playerUuid + ".owned." + shopId;
        if (shopStatsConfiguration.isConfigurationSection(path)) {
            ConfigurationSection section = shopStatsConfiguration.getConfigurationSection(path);
            if (section != null) {
                return new ArrayList<>(section.getKeys(false));
            }
        }
        if ("block_shop".equalsIgnoreCase(shopId)) {
            String legacyPath = "players." + playerUuid + ".owned";
            ConfigurationSection legacySection = shopStatsConfiguration.getConfigurationSection(legacyPath);
            if (legacySection != null) {
                return new ArrayList<>(legacySection.getKeys(false));
            }
        }
        return new ArrayList<>();
    }

    public void ensureDefaultOwned(Player player) {
        UUID playerUuid = player.getUniqueId();

        for (ShopDefinition shop : shops.values()) {
            for (ShopCategoryDefinition category : shop.getCategories()) {
                for (ShopItemDefinition item : category.getItems()) {
                    if (item.isDefaultItem() && !isOwned(playerUuid, item)) {
                        grantOwnedItem(playerUuid, item);
                    }
                }
            }
        }
        saveShopStats();
    }

    public void ensureEquippedTool(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();

        Optional<ShopItemDefinition> equippedTool = getEquippedToolDefinition(playerUuid);
        if (equippedTool.isEmpty()) {
            equippedTool = findDefaultShopItem("tools_shop");
            if (equippedTool.isPresent() && !isOwned(playerUuid, equippedTool.get())) {
                grantOwnedItem(playerUuid, equippedTool.get());
            }
        }

        equippedTool.ifPresent(itemDefinition -> equipItem(playerUuid, itemDefinition, player));
    }

    private Optional<ShopItemDefinition> getEquippedToolDefinition(UUID playerUuid) {
        Optional<String> equipped = getEquippedShopItem(playerUuid, "tools_shop");
        if (equipped.isEmpty()) {
            return Optional.empty();
        }
        String equippedValue = equipped.get().trim();
        if (equippedValue.isBlank()) {
            return Optional.empty();
        }
        String[] parts = equippedValue.split(":");
        if (parts.length < 2) {
            return Optional.empty();
        }
        return getItemDefinition("tools_shop", parts[0], parts[1]);
    }

    private Optional<ShopItemDefinition> findDefaultShopItem(String shopId) {
        Optional<ShopDefinition> shop = getShopDefinition(shopId);
        if (shop.isEmpty()) {
            return Optional.empty();
        }
        for (ShopCategoryDefinition category : shop.get().getCategories()) {
            for (ShopItemDefinition item : category.getItems()) {
                if (item.isDefaultItem()) {
                    return Optional.of(item);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<String> getEquippedShopItem(UUID playerUuid) {
        for (String shopId : shops.keySet()) {
            Optional<String> equipped = getEquippedShopItem(playerUuid, shopId);
            if (equipped.isPresent()) {
                return Optional.of(shopId + ":" + equipped.get());
            }
        }
        String legacyValue = shopStatsConfiguration.getString("players." + playerUuid + ".equipped", null);
        return Optional.ofNullable(legacyValue);
    }

    public Optional<String> getEquippedShopItem(UUID playerUuid, String shopId) {
        return getEquippedShopItem(playerUuid, shopId, null);
    }

    public Optional<String> getEquippedShopItem(UUID playerUuid, String shopId, String mode) {
        if (playerUuid == null || shopId == null || shopId.isBlank()) {
            return Optional.empty();
        }

        if (ISLAND_SHOP_ID.equalsIgnoreCase(shopId) && mode != null && !mode.isBlank()) {
            String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
            String path = "players." + playerUuid + ".equipped." + shopId + "." + normalizedMode;
            String value = shopStatsConfiguration.getString(path, null);
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }

        String path = "players." + playerUuid + ".equipped." + shopId;
        String value = shopStatsConfiguration.getString(path, null);
        if (value != null && !value.isBlank()) {
            return Optional.of(value);
        }

        if (ISLAND_SHOP_ID.equalsIgnoreCase(shopId)) {
            String basePath = "players." + playerUuid + ".equipped." + shopId;
            if (shopStatsConfiguration.isConfigurationSection(basePath)) {
                ConfigurationSection section = shopStatsConfiguration.getConfigurationSection(basePath);
                if (section != null) {
                    for (String childKey : section.getKeys(false)) {
                        String childValue = section.getString(childKey, null);
                        if (childValue != null && !childValue.isBlank()) {
                            return Optional.of(childValue);
                        }
                    }
                }
            }
        }

        String legacyValue = shopStatsConfiguration.getString("players." + playerUuid + ".equipped", null);
        if (legacyValue == null || legacyValue.isBlank()) {
            return Optional.empty();
        }
        String[] parts = legacyValue.split(":");
        if (parts.length == 3 && parts[0].equalsIgnoreCase(shopId)) {
            return Optional.of(parts[1] + ":" + parts[2]);
        }
        if (parts.length == 2 && parts[0].equalsIgnoreCase(shopId)) {
            return Optional.of(parts[1]);
        }
        return Optional.empty();
    }

    public Material getEquippedBlockMaterial(UUID playerUuid) {
        return getEquippedMaterialForShop(playerUuid, "block_shop");
    }

    public Material getEquippedToolMaterial(UUID playerUuid) {
        return getEquippedMaterialForShop(playerUuid, "tools_shop");
    }

    public Material getEquippedPracticeBlockMaterial(UUID playerUuid) {
        Material material = getEquippedMaterialForShop(playerUuid, "practice_shop");
        return material != null ? material : configManager.getPracticeBlockMaterial();
    }

    public Material getEquippedResetMaterial(UUID playerUuid) {
        return getEquippedMaterialForShop(playerUuid, "reset_animation");
    }

    private Material getEquippedMaterialForShop(UUID playerUuid, String targetShopId) {
        Optional<String> equipped = getEquippedShopItem(playerUuid, targetShopId);
        if (equipped.isEmpty()) {
            // No explicit equipped item - fall back to the shop's default item if available.
            return findDefaultItemMaterial(targetShopId);
        }
        String equippedValue = equipped.get().trim();
        if (equippedValue.equalsIgnoreCase("none")) {
            // "none" is a deliberate, valid choice for shops like reset_animation/firework_color
            // (the player explicitly picked "off") - unlike the failure paths below, this is not a
            // bug to fall back from, so it stays null on purpose.
            return null;
        }
        String[] parts = equippedValue.split(":");
        if (parts.length < 2) {
            return findDefaultItemMaterial(targetShopId);
        }
        String categoryKey = parts[0];
        String itemKey = parts[1];
        Optional<ShopDefinition> shop = getShopDefinition(targetShopId);
        if (shop.isEmpty()) {
            return findDefaultItemMaterial(targetShopId);
        }
        for (ShopCategoryDefinition category : shop.get().getCategories()) {
            if (!category.getKey().equalsIgnoreCase(categoryKey)) {
                continue;
            }
            for (ShopItemDefinition item : category.getItems()) {
                if (item.getKey().equalsIgnoreCase(itemKey)) {
                    return item.getMaterial();
                }
            }
        }
        // THE ACTUAL BUG behind replay NPCs (and, in principle, the player's own hotbar) silently
        // ending up with no block at all: an equipped value that no longer matches any item in the
        // current shop definition - e.g. the item was renamed/removed from shop.yml after being
        // equipped, or a category key casing mismatch - used to fall straight through to `null`
        // here instead of ever trying the shop's default item. `null` then propagated all the way
        // out to the replay recorder, which stored no heldMaterial for the frame, so the NPC never
        // had anything to display. Falling back to the shop's default item on every failure path
        // (not just "nothing equipped") means this always resolves to a real, visible material.
        return findDefaultItemMaterial(targetShopId);
    }

    /**
     * Finds the material of whichever item in the given shop is marked as the default (fallback)
     * item, or null if the shop has no default item configured. Used as the last-resort fallback
     * whenever a player's equipped selection for that shop can't be resolved to an actual item.
     */
    private Material findDefaultItemMaterial(String targetShopId) {
        Optional<ShopDefinition> shopDef = getShopDefinition(targetShopId);
        if (shopDef.isPresent()) {
            for (ShopCategoryDefinition cat : shopDef.get().getCategories()) {
                for (ShopItemDefinition it : cat.getItems()) {
                    if (it.isDefaultItem()) {
                        return it.getMaterial();
                    }
                }
            }
        }
        return null;
    }

    private String getCurrentPlayerIslandMode(Player player) {
        if (player == null) {
            return null;
        }
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        String arenaName = main.getPlayerManager().getPlayerArena(player.getUniqueId());
        if (arenaName == null || arenaName.isBlank()) {
            return null;
        }
        String mode = configManager.getArenaStartMode(arenaName);
        return mode != null && !mode.isBlank() ? mode.toLowerCase(Locale.ROOT) : null;
    }

    public boolean purchaseOrEquip(Player player, String target) {
        String[] parts = target.split(":");
        if (parts.length < 3) {
            return false;
        }
        String shopId = parts[0];
        String categoryKey = parts[1];
        String itemKey = parts[2];
        Optional<ShopDefinition> shop = getShopDefinition(shopId);
        if (shop.isEmpty()) {
            return false;
        }
        ShopItemDefinition itemDefinition = null;
        for (ShopCategoryDefinition category : shop.get().getCategories()) {
            if (!category.getKey().equalsIgnoreCase(categoryKey)) {
                continue;
            }
            for (ShopItemDefinition item : category.getItems()) {
                if (item.getKey().equalsIgnoreCase(itemKey)) {
                    itemDefinition = item;
                    break;
                }
            }
            if (itemDefinition != null) {
                break;
            }
        }
        if (itemDefinition == null) {
            return false;
        }

        UUID playerUuid = player.getUniqueId();
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        if (isSpecialNoneItem(shopId, categoryKey, itemKey)) {
            equipItem(playerUuid, itemDefinition, player);
            logPlayerRelatedState(player, main, "AFTER purchaseOrEquip (selection cleared)");
            player.sendMessage(ChatColor.YELLOW + "Selection cleared.");
            return true;
        }

        // Optional extra permission gate on top of the normal coin price (permissions.yml
        // "shop-items:" section). Checked for both purchasing AND re-equipping an already-owned
        // item, so revoking a permission actually revokes access to that cosmetic, not just the
        // ability to buy it again.
        if (!main.getPermissionsManager().canPurchaseShopItem(player, shopId, categoryKey, itemKey)) {
            String msg = ChatColor.translateAlternateColorCodes('&',
                    main.getConfigManager().getConfiguration().getString("no-permission-message", "&cYou do not have permission to do that."));
            player.sendMessage(msg);
            return true;
        }

        String translatedName = ChatColor.translateAlternateColorCodes('&', itemDefinition.getName());
        boolean freeItem = itemDefinition.getPrice() <= 0 || itemDefinition.isDefaultItem();
        if (isOwned(playerUuid, itemDefinition) || freeItem) {
            equipItem(playerUuid, itemDefinition, player);
            player.sendMessage(ChatColor.GREEN + "Equipped " + translatedName + ChatColor.GREEN + ".");
            return true;
        }

        PlayerStatsManager statsManager = ((SkepiFBPlugin) plugin).getStatsManager();
        int coins = statsManager.getCoins(playerUuid);
        if (coins < itemDefinition.getPrice()) {
            player.sendMessage(ChatColor.RED + "You need " + itemDefinition.getPrice() + " coins to buy that item.");
            return true;
        }

        statsManager.addCoins(playerUuid, -itemDefinition.getPrice());
        grantOwnedItem(playerUuid, itemDefinition);
        equipItem(playerUuid, itemDefinition, player);
        player.sendMessage(ChatColor.GREEN + "Purchased " + translatedName + ChatColor.GREEN + " for " + itemDefinition.getPrice() + " coins.");
        return true;
    }

    private void grantOwnedItem(UUID playerUuid, ShopItemDefinition itemDefinition) {
        String path = "players." + playerUuid + ".owned." + itemDefinition.getShopKey() + "." + itemDefinition.getKey();
        shopStatsConfiguration.set(path, true);
    }

    private void equipItem(UUID playerUuid, ShopItemDefinition itemDefinition) {
        equipItem(playerUuid, itemDefinition, null);
    }

    private void equipItem(UUID playerUuid, ShopItemDefinition itemDefinition, Player player) {
        String equippedValue = isSpecialNoneItem(itemDefinition.getShopKey(), itemDefinition.getCategoryKey(), itemDefinition.getKey())
                ? "none"
                : itemDefinition.getCategoryKey() + ":" + itemDefinition.getKey();

        if (ISLAND_SHOP_ID.equalsIgnoreCase(itemDefinition.getShopKey()) && player != null) {
            String arenaName = ((SkepiFBPlugin) plugin).getPlayerManager().getPlayerArena(player.getUniqueId());
            String mode = arenaName == null ? null : ((SkepiFBPlugin) plugin).getConfigManager().getArenaStartMode(arenaName);
            if (mode != null && !mode.isBlank()) {
                String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
                String path = "players." + playerUuid + ".equipped." + itemDefinition.getShopKey() + "." + normalizedMode;
                shopStatsConfiguration.set(path, equippedValue);
            } else {
                String path = "players." + playerUuid + ".equipped." + itemDefinition.getShopKey();
                shopStatsConfiguration.set(path, equippedValue);
            }
        } else {
            String path = "players." + playerUuid + ".equipped." + itemDefinition.getShopKey();
            shopStatsConfiguration.set(path, equippedValue);
        }
        saveShopStats();

        // If this was an island cosmetic equip and the player is currently inside an arena,
        // immediately re-paste their current island schematic so the cosmetic applies instantly.
        try {
            if (ISLAND_SHOP_ID.equalsIgnoreCase(itemDefinition.getShopKey()) && player != null && player.isOnline()) {
                SkepiFBPlugin main = (SkepiFBPlugin) plugin;
                String arenaName = main.getPlayerManager().getPlayerArena(player.getUniqueId());
                if (arenaName != null) {
                    Arena arena = main.getArenaManager().getArena(arenaName);
                    if (arena != null) {
                        ArenaIsland island = arena.findIslandByPlayer(player.getUniqueId()).orElse(null);
                        if (island != null) {
                            try {
                                main.getArenaManager().restoreIsland(arena, island);
                                main.getArenaManager().pasteIsland(arena, island, player.getUniqueId());
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // If player is online, immediately update their hotbar slot and refresh shop inventory if open
        try {
            if (player != null && player.isOnline()) {
                ((SkepiFBPlugin) plugin).getInventoryManager().giveArenaBlock(player);
                ((SkepiFBPlugin) plugin).getHotbarManager().giveHotbarToPlayer(player);

                if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
                    InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                    if (holder instanceof ShopInventoryHolder shopHolder) {
                        openCategoryPage(player, shopHolder.getShopId(), shopHolder.getCategoryKey(), shopHolder.getPage());
                    } else if (holder instanceof CategorySelectorInventoryHolder selectorHolder) {
                        openShopMenu(player, selectorHolder.getShopId());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void logPlayerRelatedState(Player player, SkepiFBPlugin main, String stage) {
        // Debug logging removed to reduce console spam during normal gameplay.
    }

    private PlayerSession getPlayerSessionObject(PlayerManager playerManager, UUID playerUuid) {
        if (playerManager == null || playerUuid == null) {
            return null;
        }
        try {
            Field field = PlayerManager.class.getDeclaredField("sessions");
            field.setAccessible(true);
            Map<?, ?> sessions = (Map<?, ?>) field.get(playerManager);
            if (sessions == null) {
                return null;
            }
            return (PlayerSession) sessions.get(playerUuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getPlayerStatsObject(PlayerStatsManager statsManager, UUID playerUuid) {
        if (statsManager == null || playerUuid == null) {
            return null;
        }
        try {
            Field field = PlayerStatsManager.class.getDeclaredField("stats");
            field.setAccessible(true);
            Map<?, ?> stats = (Map<?, ?>) field.get(statsManager);
            if (stats == null) {
                return null;
            }
            return stats.get(playerUuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String describeShopOwned(UUID playerUuid) {
        if (playerUuid == null) {
            return "null";
        }
        String path = "players." + playerUuid + ".owned";
        org.bukkit.configuration.ConfigurationSection ownedSection = shopStatsConfiguration.getConfigurationSection(path);
        if (ownedSection == null) {
            return "none";
        }
        return "ownedCategories=" + ownedSection.getKeys(false).size();
    }

    private String dumpFields(Object obj) {
        if (obj == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(obj.getClass().getSimpleName() + "{");
        Field[] fields = obj.getClass().getDeclaredFields();
        boolean first = true;
        for (Field field : fields) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(obj);
            } catch (Throwable ignored) {
                value = "<error>";
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(field.getName()).append("=").append(formatValue(value));
        }
        sb.append("}");
        return sb.toString();
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof String || value instanceof UUID) {
            return String.valueOf(value);
        }
        if (value.getClass().isEnum()) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            return value.getClass().getSimpleName() + "[size=" + ((Map<?, ?>) value).size() + "]";
        }
        if (value instanceof Collection) {
            return value.getClass().getSimpleName() + "[size=" + ((Collection<?>) value).size() + "]";
        }
        return value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    public Optional<ShopItemDefinition> getItemDefinition(String shopId, String categoryKey, String itemKey) {
      Optional<ShopDefinition> shop = getShopDefinition(shopId);
      if (shop.isEmpty()) return Optional.empty();
      for (ShopCategoryDefinition cat : shop.get().getCategories()) {
        if (!cat.getKey().equalsIgnoreCase(categoryKey)) continue;
        for (ShopItemDefinition it : cat.getItems()) {
          if (it.getKey().equalsIgnoreCase(itemKey)) return Optional.of(it);
        }
      }
      return Optional.empty();
    }

    public ItemStack buildEquippedStackFor(ShopItemDefinition itemDefinition, Player player) {
      if (itemDefinition == null) return null;
      if (isSpecialNoneItem(itemDefinition.getShopKey(), itemDefinition.getCategoryKey(), itemDefinition.getKey())) return null;
      ItemStack item = new ItemStack(itemDefinition.getMaterial());
      try {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
          meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', itemDefinition.getName()));
          item.setItemMeta(meta);
        }
      } catch (Throwable ignored) {}
      return item;
    }

    private void saveShopStats() {
        try {
            shopStatsConfiguration.save(shopStatsFile);
        } catch (IOException ex) {
            logWarning("Unable to save shop_stats.yml: " + ex.getMessage());
        }
    }

    private void createDefaultShopConfigIfMissing() {
        boolean shouldReloadBundledDefaults = false;
        if (shopConfigFile.exists()) {
            FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(shopConfigFile);
            ConfigurationSection shopsSection = currentConfig.getConfigurationSection("shops");
            if (shopsSection != null) {
                boolean hasLegacyBannerShop = shopsSection.contains("banner_shop");
                boolean hasResetAnimation = shopsSection.contains("reset_animation");
                boolean hasFireworkColor = shopsSection.contains("firework_color");
                if (hasLegacyBannerShop || !hasResetAnimation || !hasFireworkColor) {
                    shouldReloadBundledDefaults = true;
                }
            } else {
                shouldReloadBundledDefaults = true;
            }
        }

        if (shopConfigFile.exists() && !shouldReloadBundledDefaults) {
            return;
        }

        try (InputStream bundledStream = getBundledShopResourceStream()) {
            if (bundledStream != null) {
                Files.copy(bundledStream, shopConfigFile.toPath());
                return;
            }
        } catch (IOException ex) {
            logWarning("Unable to copy bundled shop.yml: " + ex.getMessage());
        }

        String defaultContent = """
                shops:
                  block_shop:
                    title: "&dBlock Shop"
                    size: 54
                    page-size: 21
                    category-selector:
                      building:
                        slot: 0
                        item: STONE
                        name: "&6Building Blocks"
                        lore:
                          - "&7Browse essential building blocks"
                      decorative:
                        slot: 2
                        item: PURPLE_STAINED_GLASS
                        name: "&dDecorative Blocks"
                        lore:
                          - "&7Browse decorative blocks"
                      redstone:
                        slot: 4
                        item: REDSTONE_BLOCK
                        name: "&cRedstone Blocks"
                        lore:
                          - "&7Browse redstone and utility blocks"
                    categories:
                      building:
                        title: "&6Building Blocks"
                        items:
                          stone:
                            material: STONE
                            name: "&7Stone"
                            price: 0
                            default: true
                            slot: 10
                            lore:
                              - "&7Basic building block"
                          oak_planks:
                            material: OAK_PLANKS
                            name: "&eOak Planks"
                            price: 25
                            slot: 11
                            lore:
                              - "&7Standard wooden planks"
                          glass:
                            material: GLASS
                            name: "&fGlass"
                            price: 30
                            slot: 12
                            lore:
                              - "&7Transparent building material"
                          cobblestone:
                            material: COBBLESTONE
                            name: "&8Cobblestone"
                            price: 15
                            slot: 13
                            lore:
                              - "&7Rough stone block"
                          stone_bricks:
                            material: STONE_BRICKS
                            name: "&fStone Bricks"
                            price: 50
                            slot: 14
                            lore:
                              - "&7Classic masonry block"
                          quartz_block:
                            material: QUARTZ_BLOCK
                            name: "&fQuartz Block"
                            price: 120
                            slot: 15
                            lore:
                              - "&7Smooth white quartz"
                      decorative:
                        title: "&dDecorative Blocks"
                        items:
                          purple_stained_glass:
                            material: PURPLE_STAINED_GLASS
                            name: "&dPurple Glass"
                            price: 40
                            slot: 10
                            lore:
                              - "&7Decorate with colored glass"
                          sea_lantern:
                            material: SEA_LANTERN
                            name: "&bSea Lantern"
                            price: 75
                            slot: 11
                            lore:
                              - "&7Glowstone alternative"
                          amethyst_block:
                            material: AMETHYST_BLOCK
                            name: "&5Amethyst Block"
                            price: 80
                            slot: 12
                            lore:
                              - "&7Mysterious crystal block"
                          bookshelf:
                            material: BOOKSHELF
                            name: "&6Bookshelf"
                            price: 45
                            slot: 13
                            lore:
                              - "&7Decoration and storage"
                          lantern:
                            material: LANTERN
                            name: "&eLantern"
                            price: 35
                            slot: 14
                            lore:
                              - "&7Soft hanging light"
                          white_carpet:
                            material: WHITE_CARPET
                            name: "&fCarpet"
                            price: 15
                            slot: 15
                            lore:
                              - "&7Floor decoration"
                      redstone:
                        title: "&cRedstone Blocks"
                        items:
                          redstone_block:
                            material: REDSTONE_BLOCK
                            name: "&cRedstone Block"
                            price: 85
                            slot: 10
                            lore:
                              - "&7Redstone power source"
                          note_block:
                            material: NOTE_BLOCK
                            name: "&6Note Block"
                            price: 50
                            slot: 11
                            lore:
                              - "&7Musical block"
                          target:
                            material: TARGET
                            name: "&aTarget"
                            price: 100
                            slot: 12
                            lore:
                              - "&7Precision redstone target"
                          observer:
                            material: OBSERVER
                            name: "&eObserver"
                            price: 110
                            slot: 13
                            lore:
                              - "&7Detects changes"
                          piston:
                            material: PISTON
                            name: "&7Piston"
                            price: 90
                            slot: 14
                            lore:
                              - "&7Mechanical movement"
                  reset_animation:
                    title: "&eAnimations"
                    size: 27
                    page-size: 21
                    categories:
                      colors:
                        title: "&eAnimations"
                        items:
                          none:
                            material: BARRIER
                            name: "&7NONE"
                            price: 0
                            slot: 10
                            default: true
                          falling:
                            material: SAND
                            name: "&6FALLING"
                            price: 100
                            slot: 11
                          ice_shatter:
                            material: BLUE_ICE
                            name: "&bICE SHATTER"
                            price: 150
                            slot: 12
                          fire:
                            material: BLAZE_POWDER
                            name: "&cFIRE"
                            price: 150
                            slot: 13
                          blue_energy:
                            material: LAPIS_BLOCK
                            name: "&9BLUE ENERGY"
                            price: 200
                            slot: 14
                          creative_mode:
                            material: PLAYER_HEAD
                            name: "&dCREATIVE MODE"
                            price: 0
                            slot: 15
                          creative_mode2:
                            material: NETHER_STAR
                            name: "&dMAGIC SWIRL"
                            price: 0
                            slot: 16
                  firework_color:
                    title: "&6Firework Color"
                    size: 27
                    page-size: 21
                    categories:
                      colors:
                        title: "&6Firework Color"
                        items:
                          white_dye:
                            material: WHITE_DYE
                            name: "&fWhite"
                            price: 0
                            slot: 10
                            default: true
                          orange_dye:
                            material: ORANGE_DYE
                            name: "&6Orange"
                            price: 75
                            slot: 11
                          magenta_dye:
                            material: MAGENTA_DYE
                            name: "&dMagenta"
                            price: 75
                            slot: 12
                          light_blue_dye:
                            material: LIGHT_BLUE_DYE
                            name: "&bLight Blue"
                            price: 75
                            slot: 13
                          yellow_dye:
                            material: YELLOW_DYE
                            name: "&eYellow"
                            price: 75
                            slot: 14
                          lime_dye:
                            material: LIME_DYE
                            name: "&aLime"
                            price: 75
                            slot: 15
                          pink_dye:
                            material: PINK_DYE
                            name: "&dPink"
                            price: 75
                            slot: 16
                          red_dye:
                            material: RED_DYE
                            name: "&cRed"
                            price: 75
                            slot: 17
                          black_dye:
                            material: BLACK_DYE
                            name: "&8Black"
                            price: 75
                            slot: 18
                  ranks:
                    title: "&eRankups"
                    items:
                      bronze_rank:
                        material: LEATHER_HELMET
                        name: "&6Bronze Rank"
                        price: 0
                        slot: 10
                        lore:
                          - "&7Rankup progression display"
                      iron_rank:
                        material: IRON_HELMET
                        name: "&7Iron Rank"
                        price: 0
                        slot: 11
                        lore:
                          - "&7Rankup progression display"
                      gold_rank:
                        material: GOLDEN_HELMET
                        name: "&eGold Rank"
                        price: 0
                        slot: 12
                        lore:
                          - "&7Rankup progression display"
                      diamond_rank:
                        material: DIAMOND_HELMET
                        name: "&bDiamond Rank"
                        price: 0
                        slot: 13
                        lore:
                          - "&7Rankup progression display"
                      netherite_rank:
                        material: NETHERITE_HELMET
                        name: "&8Netherite Rank"
                        price: 0
                        slot: 14
                        lore:
                          - "&7Rankup progression display"
                      turtle_rank:
                        material: TURTLE_HELMET
                        name: "&aTurtle Rank"
                        price: 0
                        slot: 15
                        lore:
                          - "&7Rankup progression display"
                      chainmail_rank:
                        material: CHAINMAIL_HELMET
                        name: "&fChainmail Rank"
                        price: 0
                        slot: 16
                        lore:
                          - "&7Rankup progression display"
                """;
        try {
            Files.writeString(shopConfigFile.toPath(), defaultContent, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logWarning("Unable to create default shop.yml: " + ex.getMessage());
        }
    }

    private void createDefaultShopStatsFileIfMissing() {
        if (shopStatsFile.exists()) {
            return;
        }
        try {
            Files.writeString(shopStatsFile.toPath(), "players:\n", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logWarning("Unable to create default shop_stats.yml: " + ex.getMessage());
        }
    }

    private void loadShops() {
        shops.clear();
        ConfigurationSection shopsSection = shopConfiguration.getConfigurationSection("shops");
        if (shopsSection == null) {
            logWarning("Shop config has no 'shops' section.");
            return;
        }
        int totalLoaded = 0;
        for (String shopKey : shopsSection.getKeys(false)) {
            ConfigurationSection shopSection = shopsSection.getConfigurationSection(shopKey);
            if (shopSection == null) {
                logWarning("Found shop key but no section: id=" + shopKey);
                continue;
            }
            ShopDefinition shop = parseShop(shopKey, shopSection);
            if (shop != null) {
                int itemCount = 0;
                for (ShopCategoryDefinition category : shop.getCategories()) {
                    itemCount += category.getItems().size();
                }
                totalLoaded++;
                if (itemCount == 0) {
                    logWarning("Shop " + shopKey + " loaded 0 items");
                }
                shops.put(shop.getId(), shop);
            } else {
                logWarning("Failed to construct ShopDefinition for id=" + shopKey);
            }
        }
    }

    private ShopDefinition parseShop(String shopKey, ConfigurationSection section) {
        String title = section.getString("title", shopKey);
        int size = section.getInt("size", 54);
        int pageSize = section.getInt("page-size", 21);
        List<ShopCategoryDefinition> categories = new ArrayList<>();
        ConfigurationSection categoriesSection = section.getConfigurationSection("categories");
        if (categoriesSection != null) {
            for (String categoryKey : categoriesSection.getKeys(false)) {
                ConfigurationSection categorySection = categoriesSection.getConfigurationSection(categoryKey);
                if (categorySection == null) {
                    continue;
                }
                ShopCategoryDefinition category = parseCategory(shopKey, categoryKey, categorySection);
                if (category != null) {
                    categories.add(category);
                }
            }
        }
        return new ShopDefinition(shopKey.toLowerCase(), title, size, pageSize, categories);
    }

    private ShopCategoryDefinition parseCategory(String shopKey, String categoryKey, ConfigurationSection section) {
        String title = section.getString("title", categoryKey);
        List<ShopItemDefinition> items = new ArrayList<>();
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(itemKey);
                if (itemSection == null) {
                    continue;
                }
                items.add(parseItem(shopKey, categoryKey, itemKey, itemSection));
            }
        }
        return new ShopCategoryDefinition(categoryKey, title, items);
    }

    private ShopItemDefinition parseItem(String shopKey, String categoryKey, String itemKey, ConfigurationSection section) {
        String materialName = section.getString("material", null);
        Material material = parseShopMaterial(materialName, "shop item " + shopKey + "/" + categoryKey + "/" + itemKey);
        if (material == null) {
            material = Material.BARRIER;
        }
        String name = section.getString("name", itemKey);
        int price = section.getInt("price", 0);
        boolean defaultItem = section.getBoolean("default", false);
        int slot = section.getInt("slot", -1);
        int amount = Math.max(1, section.getInt("amount", 1));
        List<String> lore = section.getStringList("lore");
        return new ShopItemDefinition(shopKey, categoryKey, itemKey, material, name, price, defaultItem, slot, amount, lore);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        
        // Handle category selector clicks
        if (event.getView().getTopInventory().getHolder() instanceof CategorySelectorInventoryHolder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
                return;
            }
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }
            ItemMeta meta = clickedItem.getItemMeta();
            if (meta == null) {
                return;
            }
            PersistentDataContainer container = meta.getPersistentDataContainer();
            String action = container.get(NamespacedKey.fromString("skepifb:shop_action"), PersistentDataType.STRING);
            String target = container.get(NamespacedKey.fromString("skepifb:shop_target"), PersistentDataType.STRING);
            if ("back_to_cosmetics".equals(action)) {
                openCosmeticsMenu(player);
                return;
            }
            if ("select_category".equals(action) && target != null) {
                String[] parts = target.split(":");
                if (parts.length >= 2) {
                    openCategoryMenu(player, parts[0], parts[1]);
                }
            }
            return;
        }
        
        // Handle tag shop clicks (standalone GUI, not part of the generic purchase pipeline)
        if (event.getView().getTopInventory().getHolder() instanceof TagShopInventoryHolder) {
            handleTagShopClick(player, event);
            return;
        }

        // Handle purchase menu clicks
        if (event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
                return;
            }
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }
            ItemMeta meta = clickedItem.getItemMeta();
            if (meta == null) {
                return;
            }
            PersistentDataContainer container = meta.getPersistentDataContainer();
            String action = container.get(NamespacedKey.fromString("skepifb:shop_action"), PersistentDataType.STRING);
            String target = container.get(NamespacedKey.fromString("skepifb:shop_target"), PersistentDataType.STRING);
            if ("back_to_cosmetics".equals(action)) {
                openCosmeticsMenu(player);
                return;
            }
            if ("shop_page".equals(action) && target != null) {
                String[] parts = target.split(":");
                if (parts.length >= 3) {
                    openCategoryPage(player, parts[0], parts[1], Integer.parseInt(parts[2]));
                }
                return;
            }
            if ("purchase".equals(action) && target != null) {
                purchaseOrEquip(player, target);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder
                || event.getView().getTopInventory().getHolder() instanceof CategorySelectorInventoryHolder
                || event.getView().getTopInventory().getHolder() instanceof TagShopInventoryHolder) {
            event.setCancelled(true);
        }
    }

    private void openCategoryPage(Player player, String shopId, String categoryKey, int page) {
        if (ISLAND_SHOP_ID.equalsIgnoreCase(shopId)) {
            openIslandShopPage(player, null, page);
            return;
        }
        if (TAG_SHOP_ID.equalsIgnoreCase(shopId)) {
            openTagShopPage(player);
            return;
        }
        Optional<ShopDefinition> shopDefinition = getShopDefinition(shopId);
        if (shopDefinition.isEmpty()) {
            logErrorMissingShop(shopId);
            return;
        }
        ShopDefinition shop = shopDefinition.get();
        ShopCategoryDefinition category = shop.getCategories().stream()
                .filter(candidate -> candidate.getKey().equalsIgnoreCase(categoryKey))
                .findFirst()
                .orElse(shop.getCategories().isEmpty() ? null : shop.getCategories().get(0));
        if (category == null) {
            return;
        }
        int size = shop.getSize() > 0 ? shop.getSize() : 54;
        // ensure inventory size is a positive multiple of 9
        if (size % 9 != 0) {
            size = ((size + 8) / 9) * 9;
        }
        int pageSize = calculatePageSizeForShop(shop, category);
        int pageCount = Math.max(1, (int) Math.ceil(category.getItems().size() / (double) pageSize));
        int normalizedPage = Math.max(0, Math.min(page, pageCount - 1));
        String title = shop.getTitle();
        if (pageCount > 1) {
            title = title + " - Page " + (normalizedPage + 1);
        }
        Inventory inventory = Bukkit.createInventory(new ShopInventoryHolder(shop.getId(), category.getKey(), normalizedPage), size,
          ChatColor.translateAlternateColorCodes('&', title));
        populateCategoryMenu(inventory, player, shop, category, normalizedPage);
        player.openInventory(inventory);
    }

    private void openIslandShopPage(Player player, ShopDefinition fallbackShop, int page) {
        ShopDefinition shop = fallbackShop != null ? fallbackShop : buildIslandShopDefinition(player);
        if (shop == null || shop.getCategories().isEmpty()) {
            return;
        }
        ShopCategoryDefinition category = shop.getCategories().get(0);
        int size = Math.max(54, shop.getSize() > 0 ? shop.getSize() : 54);
        int pageSize = calculatePageSizeForShop(shop, category);
        int pageCount = Math.max(1, (int) Math.ceil(category.getItems().size() / (double) pageSize));
        int normalizedPage = Math.max(0, Math.min(page, pageCount - 1));
        String title = shop.getTitle();
        if (pageCount > 1) {
            title = title + " - Page " + (normalizedPage + 1);
        }
        Inventory inventory = Bukkit.createInventory(new ShopInventoryHolder(shop.getId(), category.getKey(), normalizedPage), size,
                ChatColor.translateAlternateColorCodes('&', title));
        populateCategoryMenu(inventory, player, shop, category, normalizedPage);
        player.openInventory(inventory);
    }

    private ShopDefinition buildIslandShopDefinition(Player player) {
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        String arenaName = main.getPlayerManager().getPlayerArena(player.getUniqueId());
        if (arenaName == null || arenaName.isBlank() || !main.getArenaManager().arenaExists(arenaName)) {
            arenaName = configManager.getConfiguration().getString("default-arena", "").trim();
        }
        if (arenaName == null || arenaName.isBlank() || !main.getArenaManager().arenaExists(arenaName)) {
            if (main.getArenaManager().getArenas().size() == 1) {
                arenaName = main.getArenaManager().getArenas().get(0).getName();
            } else {
                return null;
            }
        }
        String mode = "default";
        String configuredMode = configManager.getArenaStartMode(arenaName);
        if (configuredMode != null && !configuredMode.isBlank()) {
            mode = configuredMode.toLowerCase(Locale.ROOT);
        }

        // If the arena has no island cosmetics configured, don't build an island shop.
        if (!configManager.hasIslandCosmetics(arenaName, mode)) {
          return null;
        }

        ConfigurationSection islandShopSection = shopConfiguration.getConfigurationSection("shops.island_shop.categories.islands.items");
        Map<String, ConfigurationSection> itemSections = new LinkedHashMap<>();
        if (islandShopSection != null) {
            for (String itemKey : islandShopSection.getKeys(false)) {
                ConfigurationSection itemSection = islandShopSection.getConfigurationSection(itemKey);
                if (itemSection != null) {
                    itemSections.put(itemKey, itemSection);
                }
            }
        }

        List<ShopItemDefinition> items = new ArrayList<>();
        List<String> cosmeticKeys = configManager.getIslandCosmeticKeys(arenaName, mode);
        if (cosmeticKeys.isEmpty()) {
            ConfigurationSection defaultSection = itemSections.get("default");
            items.add(new ShopItemDefinition(
                    ISLAND_SHOP_ID,
                    "islands",
                    "default",
                    Material.matchMaterial(defaultSection != null ? defaultSection.getString("material", "GRASS_BLOCK") : "GRASS_BLOCK"),
                    defaultSection != null ? defaultSection.getString("name", "&aRestore to Default") : "&aRestore to Default",
                    defaultSection != null ? defaultSection.getInt("price", 0) : 0,
                    true,
                    defaultSection != null ? defaultSection.getInt("slot", 4) : 4,
                    1,
                    defaultSection != null ? defaultSection.getStringList("lore") : List.of("&7Restore your island to the default look")
            ));
        } else {
            for (String cosmeticKey : cosmeticKeys) {
                String normalizedKey = ConfigManager.normalizeConfigKey(cosmeticKey);
                ConfigurationSection itemSection = itemSections.get(normalizedKey);
                int slot = itemSection != null ? itemSection.getInt("slot", -1) : -1;
                if (slot < 0) {
                    slot = configManager.getIslandCosmeticSlot(arenaName, mode, cosmeticKey, -1);
                }
                // THE ACTUAL CRASH: Material.matchMaterial(String) throws IllegalArgumentException
                // ("Name cannot be null") if passed null directly - it does NOT return null for a
                // null input the way you'd expect from a "match" method. This used to call it
                // directly on itemSection.getString("material", null), which is null whenever no
                // config item exists for this cosmeticKey (itemSection == null) or the item just has
                // no "material" key - so the island shop crashed on open instead of falling back,
                // for any cosmetic entry missing a material. Guarding for null/blank before ever
                // calling matchMaterial fixes it.
                String materialName = itemSection != null ? itemSection.getString("material", null) : null;
                Material material = (materialName == null || materialName.isBlank()) ? null : Material.matchMaterial(materialName);
                if (material == null) {
                    String fallbackMaterialName = configManager.getIslandCosmeticMaterial(arenaName, mode, cosmeticKey, "GRASS_BLOCK");
                    material = (fallbackMaterialName == null || fallbackMaterialName.isBlank()) ? null : Material.matchMaterial(fallbackMaterialName);
                }
                if (material == null) {
                    material = Material.GRASS_BLOCK;
                }
                String displayName = itemSection != null ? itemSection.getString("name", null) : null;
                if (displayName == null || displayName.isBlank()) {
                    displayName = configManager.getIslandCosmeticName(arenaName, mode, cosmeticKey, cosmeticKey);
                }
                int price = itemSection != null ? itemSection.getInt("price", -1) : -1;
                if (price < 0) {
                    price = configManager.getIslandCosmeticPrice(arenaName, mode, cosmeticKey, 0);
                }
                List<String> lore = itemSection != null ? itemSection.getStringList("lore") : List.of();
                if (lore == null || lore.isEmpty()) {
                    lore = configManager.getIslandCosmeticLore(arenaName, mode, cosmeticKey);
                }
                boolean defaultItem = itemSection != null && itemSection.getBoolean("default", false);
                if (!defaultItem && price <= 0) {
                    defaultItem = true;
                }
                items.add(new ShopItemDefinition(
                        ISLAND_SHOP_ID,
                        "islands",
                        normalizedKey,
                        material,
                        displayName,
                        price,
                        defaultItem,
                        slot,
                        1,
                        lore
                ));
            }
        }

        if (items.stream().noneMatch(item -> "default".equalsIgnoreCase(item.getKey()))) {
            ConfigurationSection defaultSection = itemSections.get("default");
            items.add(0, new ShopItemDefinition(
                    ISLAND_SHOP_ID,
                    "islands",
                    "default",
                    Material.matchMaterial(defaultSection != null ? defaultSection.getString("material", "GRASS_BLOCK") : "GRASS_BLOCK"),
                    defaultSection != null ? defaultSection.getString("name", "&aRestore to Default") : "&aRestore to Default",
                    defaultSection != null ? defaultSection.getInt("price", 0) : 0,
                    true,
                    defaultSection != null ? defaultSection.getInt("slot", 49) : 49,
                    1,
                    defaultSection != null ? defaultSection.getStringList("lore") : List.of("&7Restore your island to the default look")
            ));
        }

        ShopCategoryDefinition category = new ShopCategoryDefinition("islands", "&aIslands", items);
        return new ShopDefinition(ISLAND_SHOP_ID, "Island Shop", 54, 21, List.of(category));
    }

    // -- Public compatibility API (restored wrappers) -----------------------------------------------
    /**
     * Called once, on a player's very first join (no existing shop_stats.yml entry at all for
     * them), to apply config.yml's default-shop-selections. For each shop, a configured value of
     * "default" (the out-of-the-box setting) does nothing - the player just falls through to
     * whichever item is marked default: true in shop.yml, exactly like before this feature
     * existed. Any other configured value of the form "<category_key>:<item_key>" is resolved
     * against the shop's current item list and, if found, is force-owned and force-equipped for
     * this player, giving server owners an actual choice over new-player starting cosmetics
     * instead of only ever being able to move shop.yml's own default: true flag around.
     */
    public void applyDefaultShopSelectionsOnFirstJoin(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        if (shopStatsConfiguration.contains("players." + playerUuid)) {
            // Not a first join - never touch an existing player's ownership/equip state.
            return;
        }
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        String[] shopIds = {"block_shop", "tools_shop", "reset_animation", "firework_color", "practice_shop", ISLAND_SHOP_ID};
        boolean changedAny = false;
        for (String shopId : shopIds) {
            String selection = main.getConfigManager().getDefaultShopSelection(shopId);
            if (selection == null || selection.isBlank() || "default".equalsIgnoreCase(selection.trim())) {
                continue;
            }
            String[] parts = selection.trim().split(":", 2);
            if (parts.length < 2) {
                plugin.getLogger().warning("default-shop-selections." + shopId + " (\"" + selection
                        + "\") is not in \"<category>:<item>\" form - ignoring, falling back to shop.yml's default item.");
                continue;
            }
            String categoryKey = parts[0].trim();
            String itemKey = parts[1].trim();
            Optional<ShopDefinition> shopDef = getShopDefinition(shopId);
            if (shopDef.isEmpty()) {
                continue;
            }
            ShopItemDefinition found = null;
            for (ShopCategoryDefinition category : shopDef.get().getCategories()) {
                if (!category.getKey().equalsIgnoreCase(categoryKey)) {
                    continue;
                }
                for (ShopItemDefinition item : category.getItems()) {
                    if (item.getKey().equalsIgnoreCase(itemKey)) {
                        found = item;
                        break;
                    }
                }
            }
            if (found == null) {
                plugin.getLogger().warning("default-shop-selections." + shopId + " (\"" + selection
                        + "\") doesn't match any item currently in shop.yml - ignoring, falling back to shop.yml's default item.");
                continue;
            }
            grantOwnedItem(playerUuid, found);
            equipItem(playerUuid, found);
            changedAny = true;
        }
        if (changedAny) {
            saveShopStats();
        }
    }

    public void reload() {
        // If shop.yml (or shop_stats.yml) was deleted since the server started, regenerate it
        // instead of loading an empty file. Deliberately NOT calling createDefaultShopConfigIfMissing()
        // here - THE ACTUAL BUG this used to cause: that method also runs a one-time "does this file
        // still have the old pre-reset_animation/pre-firework_color layout" migration check, and if
        // so, silently OVERWRITES the entire live shop.yml with the bundled jar default - wiping out
        // every admin customization (added island cosmetics, edited prices, expanded categories,
        // everything) on what looked like an ordinary reload. That migration check belongs at server
        // startup only (see the constructor), never on a live reload. This helper only ever creates
        // the file when it is genuinely, completely absent - it never touches an existing one.
        createDefaultShopConfigIfTrulyMissing();
        createDefaultShopStatsFileIfMissing();
        shops.clear();
        try {
            this.shopConfiguration = YamlConfiguration.loadConfiguration(this.shopConfigFile);
        } catch (Throwable ignored) {}
        try {
            this.shopStatsConfiguration = YamlConfiguration.loadConfiguration(this.shopStatsFile);
        } catch (Throwable ignored) {}
        ensureCustomShops();
        loadShops();
    }

    /**
     * Non-destructive version of createDefaultShopConfigIfMissing() for use anywhere other than
     * the constructor (e.g. /fb reload): creates shop.yml from the bundled default ONLY if the
     * file doesn't exist at all. Never runs the legacy-migration overwrite check, so it can never
     * replace an existing, live-edited shop.yml.
     */
    private void createDefaultShopConfigIfTrulyMissing() {
        if (shopConfigFile.exists()) {
            return;
        }
        try (InputStream bundledStream = getBundledShopResourceStream()) {
            if (bundledStream != null) {
                Files.copy(bundledStream, shopConfigFile.toPath());
                return;
            }
        } catch (IOException ex) {
            logWarning("Unable to copy bundled shop.yml: " + ex.getMessage());
        }
        try {
            Files.writeString(shopConfigFile.toPath(), "shops: {}\n", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logWarning("Unable to create default shop.yml: " + ex.getMessage());
        }
    }

    public void registerListeners() {
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        } catch (Throwable ignored) {}
    }

    public Optional<ShopDefinition> getShopDefinition(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalizedId = id.toLowerCase(Locale.ROOT).trim();
        // Legacy shop IDs: "animations" and "banner_shop" were replaced by
        // "reset_animation" and "firework_color" respectively. Any request for
        // the old IDs (from stale configs, saved data, or old commands) is
        // transparently remapped to the current shop so nothing 404s.
        if ("animations".equals(normalizedId)) {
            normalizedId = "reset_animation";
        } else if ("banner_shop".equals(normalizedId)) {
            normalizedId = "firework_color";
        }
        return Optional.ofNullable(shops.get(normalizedId));
    }

    public void refreshOpenShopMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory() == null || p.getOpenInventory().getTopInventory() == null) continue;
            InventoryHolder holder = p.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof ShopInventoryHolder shopHolder) {
                openCategoryPage(p, shopHolder.getShopId(), shopHolder.getCategoryKey(), shopHolder.getPage());
            } else if (holder instanceof CategorySelectorInventoryHolder selectorHolder) {
                openShopMenu(p, selectorHolder.getShopId());
            }
        }
    }

    public static boolean isSelectedShopItem(String shopId, String categoryKey, String itemKey, String equippedValue) {
        if (itemKey == null || itemKey.isBlank()) return false;
        String normalizedItemKey = itemKey.trim();
        if ("none".equalsIgnoreCase(normalizedItemKey)) {
            if (equippedValue == null || equippedValue.isBlank()) {
                return true;
            }
            String v = equippedValue.trim();
            if (v.equalsIgnoreCase("none")) return true;
            if (v.contains(":")) {
                String[] parts = v.split(":");
                if (parts.length == 2) {
                    return parts[0].equalsIgnoreCase(categoryKey) && "none".equalsIgnoreCase(parts[1]);
                }
                if (parts.length >= 3) {
                    return parts[1].equalsIgnoreCase(categoryKey) && "none".equalsIgnoreCase(parts[2]);
                }
            }
            return false;
        }
        if (equippedValue == null || equippedValue.isBlank()) return false;
        String v = equippedValue.trim();
        if (v.equalsIgnoreCase("none")) return false;
        if (v.contains(":")) {
            String[] parts = v.split(":");
            if (parts.length == 2) {
                return parts[0].equalsIgnoreCase(categoryKey) && parts[1].equalsIgnoreCase(normalizedItemKey);
            }
            if (parts.length >= 3) {
                return parts[1].equalsIgnoreCase(categoryKey) && parts[2].equalsIgnoreCase(normalizedItemKey);
            }
        }
        return v.equalsIgnoreCase(normalizedItemKey);
    }

    private boolean isSpecialNoneItem(String shopId, String categoryKey, String itemKey) {
        return itemKey != null && "none".equalsIgnoreCase(itemKey);
    }

    public boolean ensureIslandShopEntry(String arenaName, String mode, String cosmeticKey, String displayName, String schematicName) {
        if (arenaName == null || arenaName.isBlank() || mode == null || mode.isBlank() || cosmeticKey == null || cosmeticKey.isBlank()) {
            return false;
        }
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        String normalizedKey = ConfigManager.normalizeConfigKey(cosmeticKey);
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return false;
        }

        ConfigurationSection shopsSection = shopConfiguration.getConfigurationSection("shops");
        if (shopsSection == null) {
            shopsSection = shopConfiguration.createSection("shops");
        }
        ConfigurationSection islandShopSection = shopsSection.getConfigurationSection("island_shop");
        if (islandShopSection == null) {
            islandShopSection = shopsSection.createSection("island_shop");
            islandShopSection.set("title", "Island Shop");
            islandShopSection.set("size", 54);
            islandShopSection.set("page-size", 21);
        }
        ConfigurationSection categoriesSection = islandShopSection.getConfigurationSection("categories");
        if (categoriesSection == null) {
            categoriesSection = islandShopSection.createSection("categories");
        }
        ConfigurationSection islandsCategorySection = categoriesSection.getConfigurationSection("islands");
        if (islandsCategorySection == null) {
            islandsCategorySection = categoriesSection.createSection("islands");
            islandsCategorySection.set("title", "&aIslands");
        }
        ConfigurationSection itemsSection = islandsCategorySection.getConfigurationSection("items");
        if (itemsSection == null) {
            itemsSection = islandsCategorySection.createSection("items");
        }
        ConfigurationSection itemSection = itemsSection.getConfigurationSection(normalizedKey);
        if (itemSection == null) {
            itemSection = itemsSection.createSection(normalizedKey);
        }
        int slot = findNextIslandShopSlot(itemsSection);
        itemSection.set("material", "GRASS_BLOCK");
        itemSection.set("name", displayName == null || displayName.isBlank() ? cosmeticKey : displayName);
        itemSection.set("price", 2000);
        itemSection.set("default", false);
        itemSection.set("slot", slot);
        itemSection.set("lore", List.of("&7Island cosmetic"));

        configManager.addIslandCosmetic(arenaName, normalizedMode, cosmeticKey, schematicName, "GRASS_BLOCK", displayName == null || displayName.isBlank() ? cosmeticKey : displayName, 2000, slot);

        try {
            shopConfiguration.save(shopConfigFile);
        } catch (IOException ex) {
            logWarning("Unable to save island shop entry: " + ex.getMessage());
            return false;
        }
        reload();
        return true;
    }

    private int findNextIslandShopSlot(ConfigurationSection itemsSection) {
        List<Integer> usedSlots = new ArrayList<>();
        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }
            int slot = itemSection.getInt("slot", -1);
            if (slot >= 0) {
                usedSlots.add(slot);
            }
        }
        for (int slot : getInteriorSlots(54)) {
            if (!usedSlots.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private int findFirstAvailableSlot(Inventory inventory, List<Integer> usableSlots) {
        for (int slot : usableSlots) {
            if (slot >= 0 && slot < inventory.getSize()) {
                ItemStack existing = inventory.getItem(slot);
                if (existing == null || existing.getType() == Material.AIR) {
                    return slot;
                }
            }
        }
        return -1;
    }

    private List<Integer> getInteriorSlots(int size) {
        List<Integer> slots = new ArrayList<>();
        int rows = Math.max(1, size / 9);
        for (int row = 1; row <= Math.max(1, rows - 2); row++) {
            for (int col = 1; col <= 7; col++) {
                int slot = row * 9 + col;
                if (slot >= 0 && slot < size) {
                    slots.add(slot);
                }
            }
        }
        return slots;
    }

    public static final class ShopDefinition {
        private final String id;
        private final String title;
        private final int size;
        private final int pageSize;
        private final List<ShopCategoryDefinition> categories;

        public ShopDefinition(String id, String title, int size, int pageSize, List<ShopCategoryDefinition> categories) {
            this.id = id;
            this.title = title;
            this.size = size;
            this.pageSize = pageSize;
            this.categories = categories;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public int getSize() {
            return size;
        }

        public int getPageSize() {
            return pageSize;
        }

        public List<ShopCategoryDefinition> getCategories() {
            return categories;
        }
    }

    public static final class ShopCategoryDefinition {
        private final String key;
        private final String title;
        private final List<ShopItemDefinition> items;

        public ShopCategoryDefinition(String key, String title, List<ShopItemDefinition> items) {
            this.key = key;
            this.title = title;
            this.items = items;
        }

        public String getKey() {
            return key;
        }

        public String getTitle() {
            return title;
        }

        public List<ShopItemDefinition> getItems() {
            return items;
        }
    }

    public static final class ShopItemDefinition {
      private final String shopKey;
      private final String categoryKey;
      private final String key;
      private final Material material;
      private final String name;
      private final int price;
      private final boolean defaultItem;
      private final int slot;
      private final int amount;
      private final List<String> lore;

      public ShopItemDefinition(String shopKey, String categoryKey, String key, Material material, String name, int price, boolean defaultItem) {
        this(shopKey, categoryKey, key, material, name, price, defaultItem, -1, 1, List.of());
      }

      public ShopItemDefinition(String shopKey, String categoryKey, String key, Material material, String name, int price, boolean defaultItem, int slot) {
        this(shopKey, categoryKey, key, material, name, price, defaultItem, slot, 1, List.of());
      }

      public ShopItemDefinition(String shopKey, String categoryKey, String key, Material material, String name, int price, boolean defaultItem, int slot, int amount) {
        this(shopKey, categoryKey, key, material, name, price, defaultItem, slot, amount, List.of());
      }

      public ShopItemDefinition(String shopKey, String categoryKey, String key, Material material, String name, int price, boolean defaultItem, int slot, int amount, List<String> lore) {
        this.shopKey = shopKey;
        this.categoryKey = categoryKey;
        this.key = key;
        this.material = material;
        this.name = name;
        this.price = price;
        this.defaultItem = defaultItem;
        this.slot = slot;
        this.amount = Math.max(1, amount);
        this.lore = lore == null ? List.of() : List.copyOf(lore);
      }

        public String getShopKey() {
            return shopKey;
        }

        public String getCategoryKey() {
            return categoryKey;
        }

        public String getKey() {
            return key;
        }

        public Material getMaterial() {
            return material;
        }

        public String getName() {
            return name;
        }

        public int getPrice() {
            return price;
        }

        public boolean isDefaultItem() {
            return defaultItem;
        }

        public int getSlot() {
            return slot;
        }

        public int getAmount() {
          return amount;
        }

        public List<String> getLore() {
          return lore;
        }
    }

    private static final class TagShopInventoryHolder implements InventoryHolder {
        private final int page;

        private TagShopInventoryHolder(int page) {
            this.page = page;
        }

        public int getPage() {
            return page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    // Tags GUI layout: 9x5 (45 slots). Row 1 (0-8) and row 5 (36-44) are a gray stained glass pane
    // border. Rows 2-4 (9-35, 27 slots) hold the paginated tag entries. Row 5's corners (36 and 44)
    // are page-navigation arrows, and its center (40) is the fixed "No Tag" barrier.
    private static final int TAG_SHOP_SIZE = 45;
    private static final int TAG_SHOP_PAGE_CAPACITY = 27;
    private static final int TAG_SHOP_CONTENT_START_SLOT = 9;
    private static final int TAG_SHOP_CONTENT_END_SLOT = 35;
    private static final int TAG_SHOP_PREVIOUS_SLOT = 36;
    private static final int TAG_SHOP_NO_TAG_SLOT = 40;
    private static final int TAG_SHOP_NEXT_SLOT = 44;

    /**
     * Builds and opens the tag shop, a standalone GUI (deliberately NOT built on the generic
     * purchase/ownership ShopItemDefinition pipeline, since tags are earned via leaderboard
     * placement rather than bought - reusing that pipeline would mean bolting price/ownership
     * concepts onto something that has neither). Always shows "No Tag" (fixed at the bottom-center
     * slot) plus one entry per leaderboard-position tag the player currently qualifies for, with the
     * currently-equipped one shown enchanted. Paginated across the middle three rows if the player
     * qualifies for more tags than fit on one page.
     */
    private void openTagShopPage(Player player) {
        openTagShopPage(player, 1);
    }

    private void openTagShopPage(Player player, int page) {
        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        List<String> qualifyingTagKeys = main.getTagManager().getQualifyingTagKeys(player.getUniqueId());
        String equippedKey = main.getTagManager().getEquippedTag(player.getUniqueId());

        int totalPages = Math.max(1, (int) Math.ceil(qualifyingTagKeys.size() / (double) TAG_SHOP_PAGE_CAPACITY));
        int normalizedPage = Math.max(1, Math.min(page, totalPages));

        Inventory inventory = Bukkit.createInventory(new TagShopInventoryHolder(normalizedPage), TAG_SHOP_SIZE,
                ChatColor.translateAlternateColorCodes('&', "&dTags"));

        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName(" ");
            pane.setItemMeta(paneMeta);
        }
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, pane);
        }
        for (int slot = 36; slot < TAG_SHOP_SIZE; slot++) {
            inventory.setItem(slot, pane);
        }

        int startIndex = (normalizedPage - 1) * TAG_SHOP_PAGE_CAPACITY;
        int slot = TAG_SHOP_CONTENT_START_SLOT;
        for (int i = startIndex; i < qualifyingTagKeys.size() && slot <= TAG_SHOP_CONTENT_END_SLOT; i++) {
            String tagKey = qualifyingTagKeys.get(i);
            String display = main.getTagManager().getTagDisplayName(tagKey);
            boolean isEquipped = tagKey.equalsIgnoreCase(equippedKey);
            inventory.setItem(slot, buildTagShopItem(Material.NAME_TAG, display,
                    List.of("&7Click to equip this tag"), tagKey, isEquipped));
            slot++;
        }

        inventory.setItem(TAG_SHOP_NO_TAG_SLOT, buildTagShopItem(Material.BARRIER, "&cNo Tag",
                List.of("&7Removes your equipped leaderboard tag"), "none", "none".equalsIgnoreCase(equippedKey)));

        boolean hasPrevious = normalizedPage > 1;
        boolean hasNext = normalizedPage < totalPages;
        inventory.setItem(TAG_SHOP_PREVIOUS_SLOT, createTagPageItem(true, hasPrevious, normalizedPage - 1));
        inventory.setItem(TAG_SHOP_NEXT_SLOT, createTagPageItem(false, hasNext, normalizedPage + 1));

        // The tag shop's bottom-middle slot (40) is permanently occupied by the fixed "No Tag"
        // option, so the back button goes in the top-middle slot (4) instead - the only other
        // border slot free on every page regardless of tag count.
        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.YELLOW + "\u2190 Back to Cosmetics");
            backMeta.setLore(List.of(ChatColor.GRAY + "Return to the main cosmetics menu"));
            PersistentDataContainer backContainer = backMeta.getPersistentDataContainer();
            backContainer.set(NamespacedKey.fromString("skepifb:tag_shop_action"), PersistentDataType.STRING, "back_to_cosmetics");
            backContainer.set(NamespacedKey.fromString("skepifb:tag_shop_target"), PersistentDataType.STRING, "back_to_cosmetics");
            backItem.setItemMeta(backMeta);
        }
        inventory.setItem(4, backItem);

        player.openInventory(inventory);
    }

    /**
     * Builds a page-navigation item for the tags GUI: an arrow that jumps to targetPage when a
     * previous/next page actually exists, or a plain stick (inert placeholder, no click action
     * attached) when it doesn't.
     */
    private ItemStack createTagPageItem(boolean previous, boolean enabled, int targetPage) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String label = enabled
                    ? (previous ? "&ePrevious Page" : "&eNext Page")
                    : (previous ? "&7No Previous Page" : "&7No Next Page");
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', label));
            if (enabled) {
                PersistentDataContainer container = meta.getPersistentDataContainer();
                container.set(NamespacedKey.fromString("skepifb:tag_shop_action"), PersistentDataType.STRING, "tag_page");
                container.set(NamespacedKey.fromString("skepifb:tag_shop_target"), PersistentDataType.STRING, String.valueOf(targetPage));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildTagShopItem(Material material, String name, List<String> lore, String tagKey, boolean equipped) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            coloredLore.add(equipped ? ChatColor.GREEN + "Equipped" : ChatColor.GRAY + "Not equipped");
            meta.setLore(coloredLore);
            if (equipped) {
                meta.addEnchant(Enchantment.getByKey(NamespacedKey.minecraft("unbreaking")), 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(NamespacedKey.fromString("skepifb:tag_shop_action"), PersistentDataType.STRING, "equip_tag");
            container.set(NamespacedKey.fromString("skepifb:tag_shop_target"), PersistentDataType.STRING, tagKey);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void handleTagShopClick(Player player, InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int currentPage = 1;
        if (event.getView().getTopInventory().getHolder() instanceof TagShopInventoryHolder holder) {
            currentPage = holder.getPage();
        }
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String action = container.get(NamespacedKey.fromString("skepifb:tag_shop_action"), PersistentDataType.STRING);
        String target = container.get(NamespacedKey.fromString("skepifb:tag_shop_target"), PersistentDataType.STRING);
        if (action == null || target == null) {
            return;
        }

        if ("back_to_cosmetics".equals(action)) {
            openCosmeticsMenu(player);
            return;
        }

        if ("tag_page".equals(action)) {
            try {
                openTagShopPage(player, Integer.parseInt(target));
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        if (!"equip_tag".equals(action)) {
            return;
        }

        SkepiFBPlugin main = (SkepiFBPlugin) plugin;
        main.getTagManager().setEquippedTag(player.getUniqueId(), target);
        if ("none".equalsIgnoreCase(target)) {
            player.sendMessage(ChatColor.GREEN + "Tag removed.");
        } else {
            player.sendMessage(ChatColor.GREEN + "Tag equipped: " + ChatColor.translateAlternateColorCodes('&', main.getTagManager().getTagDisplayName(target)));
        }
        // Re-render in place, on the same page, so the enchant highlight moves to the newly-equipped tag.
        openTagShopPage(player, currentPage);
    }

    private static final class ShopInventoryHolder implements InventoryHolder {
        private final String shopId;
        private final String categoryKey;
        private final int page;

        private ShopInventoryHolder(String shopId, String categoryKey, int page) {
            this.shopId = shopId;
            this.categoryKey = categoryKey;
            this.page = page;
        }

        public String getShopId() {
            return shopId;
        }

        public String getCategoryKey() {
            return categoryKey;
        }

        public int getPage() {
            return page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class CategorySelectorInventoryHolder implements InventoryHolder {
        private final String shopId;

        private CategorySelectorInventoryHolder(String shopId) {
            this.shopId = shopId;
        }

        public String getShopId() {
            return shopId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
