package me.skepi.skepifb.templates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single saved practice-block layout: every block is stored as an INTEGER offset relative to
 * the island's spawn block (floor(spawnX), spawnY, floor(spawnZ)) - not absolute world
 * coordinates - so the exact same template can be loaded on any island of the same
 * arena/mode (islands of one arena are just copies of each other at different X offsets, see
 * Arena#calculateSpawn) regardless of which specific island the player is currently on.
 */
public class PracticeTemplate {

    /**
     * One block in the template. dx/dy/dz are block-integer offsets from the island spawn
     * origin; material is the exact block type that was placed (practice blocks can be placed
     * with whatever material the player had selected on their practice block item), preserved so
     * loading the template reproduces the layout exactly, not just its shape.
     */
    public static final class BlockEntry {
        public final int dx;
        public final int dy;
        public final int dz;
        public final String material;

        public BlockEntry(int dx, int dy, int dz, String material) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.material = material == null || material.isBlank() ? "STONE" : material;
        }
    }

    private final List<BlockEntry> blocks;

    public PracticeTemplate(List<BlockEntry> blocks) {
        this.blocks = blocks == null ? new ArrayList<>() : new ArrayList<>(blocks);
    }

    public List<BlockEntry> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public int size() {
        return blocks.size();
    }
}
