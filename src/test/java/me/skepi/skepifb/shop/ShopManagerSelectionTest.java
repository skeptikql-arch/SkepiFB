package me.skepi.skepifb.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopManagerSelectionTest {

    @Test
    void noneOptionIsSelectedWhenTheSelectionIsUnset() {
        assertTrue(ShopManager.isSelectedShopItem("reset_animation", "colors", "none", null));
        assertTrue(ShopManager.isSelectedShopItem("reset_animation", "colors", "none", "none"));
    }

    @Test
    void noneOptionDoesNotMatchAnEquippedColor() {
        assertFalse(ShopManager.isSelectedShopItem("reset_animation", "colors", "none", "colors:red_dye"));
        assertTrue(ShopManager.isSelectedShopItem("tools_shop", "tools", "wooden_pickaxe", "tools:wooden_pickaxe"));
    }
}
