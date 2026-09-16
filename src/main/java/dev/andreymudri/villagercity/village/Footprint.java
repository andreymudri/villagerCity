package dev.andreymudri.villagercity.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** Inclusive horizontal rectangle in block coordinates. */
public record Footprint(int minX, int minZ, int maxX, int maxZ) {
    public Footprint {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("inverted footprint: " + minX + "," + minZ + " .. " + maxX + "," + maxZ);
        }
    }

    public static Footprint of(BlockPos origin, Vec3i size) {
        return new Footprint(origin.getX(), origin.getZ(), origin.getX() + size.getX() - 1, origin.getZ() + size.getZ() - 1);
    }

    public boolean intersects(Footprint other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public Footprint inflate(int by) {
        return new Footprint(minX - by, minZ - by, maxX + by, maxZ + by);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
