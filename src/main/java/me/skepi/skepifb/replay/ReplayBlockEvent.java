package me.skepi.skepifb.replay;

public final class ReplayBlockEvent {
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String material;
    private final boolean breakEvent;

    public ReplayBlockEvent(String world, int x, int y, int z, String material, boolean breakEvent) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.material = material;
        this.breakEvent = breakEvent;
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public String getMaterial() {
        return material;
    }

    public boolean isBreakEvent() {
        return breakEvent;
    }
}
