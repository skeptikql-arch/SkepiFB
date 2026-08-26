package me.skepi.skepifb.hotbar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IslandMenuLayoutTest {

    @Test
    void calculatesPagesAndNormalizesPageBounds() {
        assertEquals(1, IslandMenuLayout.getPageCount(1));
        assertEquals(2, IslandMenuLayout.getPageCount(22));
        assertEquals(0, IslandMenuLayout.normalizePage(0, 2));
        assertEquals(1, IslandMenuLayout.normalizePage(9, 2));
    }
}
