package me.skepi.skepifb.timer;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;

public final class BlockTracker {

    private final LinkedHashSet<BlockEntry> blocks = new LinkedHashSet<>();

    public void track(int x, int y, int z, String worldName, java.util.UUID ownerUuid, java.util.UUID sessionId) {
        blocks.remove(new BlockEntry(worldName, x, y, z, null, null));
        blocks.add(new BlockEntry(worldName, x, y, z, ownerUuid, sessionId));
    }

    public void removeTrackedBlocks(World world) {
        if (world == null) {
            clear();
            return;
        }

        for (BlockEntry entry : new ArrayList<>(blocks)) {
            if (!world.getName().equals(entry.worldName)) {
                continue;
            }
            world.getBlockAt(entry.x, entry.y, entry.z).setType(org.bukkit.Material.AIR);
            blocks.remove(entry);
        }
    }

    public void removeSingleTrackedBlock(World world, int x, int y, int z) {
        if (world == null) return;
        blocks.remove(new BlockEntry(world.getName(), x, y, z, null, null));
    }

    public boolean isOwnedBy(World world, int x, int y, int z, java.util.UUID ownerUuid, java.util.UUID sessionId) {
        if (world == null) return false;
        for (BlockEntry entry : blocks) {
            if (!world.getName().equals(entry.worldName)) continue;
            if (entry.x == x && entry.y == y && entry.z == z) {
                if (entry.ownerUuid == null) return false;
                if (!entry.ownerUuid.equals(ownerUuid)) return false;
                if (entry.sessionId == null) return false;
                return entry.sessionId.equals(sessionId);
            }
        }
        return false;
    }

    public void clear() {
        blocks.clear();
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public boolean hasTrackedBlocks() {
        return !blocks.isEmpty();
    }

    public void updateTrackedBlocksSessionId(java.util.UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        java.util.Set<BlockEntry> updated = new java.util.LinkedHashSet<>();
        for (BlockEntry entry : blocks) {
            if (entry.sessionId == null || !entry.sessionId.equals(sessionId)) {
                updated.add(new BlockEntry(entry.worldName, entry.x, entry.y, entry.z, entry.ownerUuid, sessionId));
            } else {
                updated.add(entry);
            }
        }
        blocks.clear();
        blocks.addAll(updated);
    }

    public java.util.List<org.bukkit.Location> snapshotTrackedLocations(World world) {
        java.util.List<org.bukkit.Location> locations = new java.util.ArrayList<>();
        if (world == null) {
            return locations;
        }
        for (BlockEntry entry : blocks) {
            if (!world.getName().equals(entry.worldName)) {
                continue;
            }
            locations.add(new org.bukkit.Location(world, entry.x, entry.y, entry.z));
        }
        return locations;
    }

    private static final class BlockEntry {
        final String worldName;
        final int x;
        final int y;
        final int z;
        final java.util.UUID ownerUuid;
        final java.util.UUID sessionId;

        BlockEntry(String worldName, int x, int y, int z, java.util.UUID ownerUuid, java.util.UUID sessionId) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ownerUuid = ownerUuid;
            this.sessionId = sessionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockEntry that = (BlockEntry) o;
            return x == that.x && y == that.y && z == that.z && java.util.Objects.equals(worldName, that.worldName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(worldName, x, y, z);
        }
    }
}
