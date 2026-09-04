package me.skepi.skepifb.arena;

public class ArenaBoundary {

    private final int left;
    private final int right;
    private final int back;
    private final int down;
    private final int forward;
    private final int up;

    public ArenaBoundary(int left, int right, int back, int down, int forward, int up) {
        this.left = left;
        this.right = right;
        this.back = back;
        this.down = down;
        this.forward = forward;
        this.up = up;
    }

    public int getLeft() {
        return left;
    }

    public int getRight() {
        return right;
    }

    public int getBack() {
        return back;
    }

    public int getDown() {
        return down;
    }

    public int getForward() {
        return forward;
    }

    public int getUp() {
        return up;
    }

    public static ArenaBoundary defaultBoundary(int spacing) {
        return defaultBoundary(spacing, Layout.STRAIGHT);
    }

    /**
     * Layout-aware default boundary used whenever an arena is created/loaded without an explicit
     * boundary of its own. STRAIGHT keeps the old spacing-derived box (so tightly-spaced arenas
     * don't get an oversized boundary). DIAGONAL ("inclined") always gets the fixed 8/8/8/5/-1/-1
     * box regardless of spacing, since the diagonal boundary is rotated 45 degrees at check-time
     * (see TimerManager#isOutOfBounds) and a spacing-derived box there tends to feel far too tight.
     */
    public static ArenaBoundary defaultBoundary(int spacing, Layout layout) {
        if (layout == Layout.DIAGONAL) {
            return new ArenaBoundary(5, 5, 5, 5, -1, -1);
        }
        int horizontalBoundary = Math.max(0, spacing / 2 - 2);
        return new ArenaBoundary(horizontalBoundary, horizontalBoundary, horizontalBoundary, 5, -1, -1);
    }

    public static ArenaBoundary defaultTestBoundary() {
        return new ArenaBoundary(8, 8, 8, 5, -1, -1);
    }
}
