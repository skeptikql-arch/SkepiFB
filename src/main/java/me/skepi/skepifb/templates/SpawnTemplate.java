package me.skepi.skepifb.templates;

/**
 * A single saved custom spawn position: dx/dy/dz are offsets (relative, decimal-preserving)
 * from the island's spawn block origin (floor(spawnX), spawnY, floor(spawnZ)) - the same
 * origin/flooring convention used for practice-block templates and the (now fixed) replay
 * hologram relative coordinates - plus the yaw/pitch the player was facing when it was saved.
 */
public class SpawnTemplate {

    public final double dx;
    public final double dy;
    public final double dz;
    public final float yaw;
    public final float pitch;

    public SpawnTemplate(double dx, double dy, double dz, float yaw, float pitch) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.yaw = yaw;
        this.pitch = pitch;
    }
}
