package me.skepi.skepifb.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.skepi.skepifb.replay.ReplayBlockEvent;

public final class ReplayFrame {
    private final int tick;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean sneaking;
    private boolean sprinting;
    private final List<String> armSwings = new ArrayList<>();
    private final List<ReplayBlockEvent> placements = new ArrayList<>();
    private final List<ReplayBlockEvent> breaks = new ArrayList<>();
    private String heldMaterial;
    private boolean coordinatesRelative = false;

    public ReplayFrame(int tick) {
        this.tick = tick;
    }

    public int getTick() {
        return tick;
    }

    public double getX() {
        return x;
    }

    public double getY() {
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

    public boolean isSneaking() {
        return sneaking;
    }

    public String getHeldMaterial() {
        return heldMaterial;
    }

    public void setHeldMaterial(String heldMaterial) {
        this.heldMaterial = heldMaterial;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public List<String> getArmSwings() {
        return Collections.unmodifiableList(armSwings);
    }

    public List<ReplayBlockEvent> getPlacements() {
        return Collections.unmodifiableList(placements);
    }

    public List<ReplayBlockEvent> getBreaks() {
        return Collections.unmodifiableList(breaks);
    }

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    public void addArmSwing(String armSwing) {
        if (armSwing != null && !armSwing.isEmpty()) {
            armSwings.add(armSwing);
        }
    }

    public void addPlacement(ReplayBlockEvent event) {
        if (event != null && !event.isBreakEvent()) {
            placements.add(event);
        }
    }

    public void addBreak(ReplayBlockEvent event) {
        if (event != null && event.isBreakEvent()) {
            breaks.add(event);
        }
    }

    public boolean isCoordinatesRelative() {
        return coordinatesRelative;
    }

    public void setCoordinatesRelative(boolean coordinatesRelative) {
        this.coordinatesRelative = coordinatesRelative;
    }
}
