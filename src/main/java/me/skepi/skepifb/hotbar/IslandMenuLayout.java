package me.skepi.skepifb.hotbar;

public final class IslandMenuLayout {

    public static final int ITEMS_PER_PAGE = 21;

    private IslandMenuLayout() {
    }

    public static int getPageCount(int islandCount) {
        return Math.max(1, (int) Math.ceil((double) islandCount / ITEMS_PER_PAGE));
    }

    public static int normalizePage(int page, int pageCount) {
        if (pageCount <= 1) {
            return 0;
        }
        return Math.max(0, Math.min(page, pageCount - 1));
    }
}
