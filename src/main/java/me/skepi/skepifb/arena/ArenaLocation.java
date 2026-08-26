package me.skepi.skepifb.arena;

public class ArenaLocation {

    private final double x;
    private final int y;
    private final double z;
    private final float yaw;
    private final float pitch;

    public ArenaLocation(double x, int y, double z) {
        this(x, y, z, 0.0f, 0.0f);
    }

    public ArenaLocation(double x, int y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public double getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }
}
