package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Finds village ground dark enough for monsters to spawn. It walks the square {@code center ± radius} a few columns
 * per {@link #scan} call, in a fixed row order that wraps around, and remembers the feet position of each dark column
 * it finds. Nothing here is saved.
 */
public final class DarkSpots {
    private final LongSet spots = new LongOpenHashSet();
    private @Nullable UUID villageId;
    private @Nullable VillageData village;
    private int cursor;
    private boolean passFoundDark;
    private boolean fullPassClean;

    /** Tests the next {@code columns} columns of the village square, adding dark ones and dropping lit ones. */
    public void scan(ServerLevel level, VillageData village, int columns) {
        if (!village.id().equals(villageId)) {
            spots.clear();
            villageId = village.id();
            resetPass();
        }
        this.village = village;
        int radius = village.radius();
        int side = 2 * radius + 1;
        int total = side * side;
        if (cursor >= total) {
            cursor = 0;
            passFoundDark = false;
        }
        List<Footprint> occupied = village.occupiedFootprints();
        for (int i = 0; i < Math.min(columns, total); i++) {
            int x = village.center().getX() - radius + cursor % side;
            int z = village.center().getZ() - radius + cursor / side;
            if (retest(level, village, occupied, x, z)) {
                passFoundDark = true;
                fullPassClean = false;
            }
            if (++cursor >= total) {
                cursor = 0;
                fullPassClean = !passFoundDark;
                passFoundDark = false;
            }
        }
    }

    /** True after a complete pass over the village square that found no dark column. */
    public boolean fullPassClean() {
        return fullPassClean;
    }

    /** Starts a new pass from the first column; {@link #fullPassClean} stays false until that pass completes clean. */
    public void resetPass() {
        cursor = 0;
        passFoundDark = false;
        fullPassClean = false;
    }

    public int size() {
        return spots.size();
    }

    public Optional<BlockPos> nearest(BlockPos from) {
        return nearest(from, spot -> false);
    }

    /** The known dark spot nearest to {@code from}, ignoring the spots {@code skip} accepts. */
    public Optional<BlockPos> nearest(BlockPos from, Predicate<BlockPos> skip) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LongIterator it = spots.iterator(); it.hasNext(); ) {
            BlockPos spot = BlockPos.of(it.nextLong());
            double distance = spot.distSqr(from);
            if (distance < bestDistance && !skip.test(spot)) {
                best = spot;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Re-tests the columns of the known spots within {@code radius} blocks (horizontally) of {@code pos}, such as around a new torch. */
    public void recheckAround(ServerLevel level, BlockPos pos, int radius) {
        if (village == null) {
            return;
        }
        List<Footprint> occupied = village.occupiedFootprints();
        long[] known = spots.toLongArray();
        for (long packed : known) {
            BlockPos spot = BlockPos.of(packed);
            if (Math.abs(spot.getX() - pos.getX()) <= radius && Math.abs(spot.getZ() - pos.getZ()) <= radius) {
                spots.remove(packed);
                retest(level, village, occupied, spot.getX(), spot.getZ());
            }
        }
    }

    /** Tests one column and records the result; true when it is dark. */
    private boolean retest(ServerLevel level, VillageData village, List<Footprint> occupied, int x, int z) {
        BlockPos probe = new BlockPos(x, village.center().getY(), z);
        if (!level.isLoaded(probe)) {
            return false;
        }
        BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
        spots.removeIf(packed -> BlockPos.getX(packed) == x && BlockPos.getZ(packed) == z && packed != feet.asLong());
        if (isDark(level, occupied, feet)) {
            spots.add(feet.asLong());
            return true;
        }
        spots.remove(feet.asLong());
        return false;
    }

    /** A spawnable, open, unlit cell standing on ground outside every village footprint. */
    static boolean isDark(ServerLevel level, List<Footprint> occupied, BlockPos feet) {
        if (occupied.stream().anyMatch(footprint -> footprint.contains(feet.getX(), feet.getZ()))) {
            return false;
        }
        BlockPos ground = feet.below();
        return level.getBlockState(ground).isValidSpawn(level, ground, EntityType.ZOMBIE)
                && isOpen(level, feet)
                && isOpen(level, feet.above())
                && level.getBrightness(LightLayer.BLOCK, feet) == 0;
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }
}
