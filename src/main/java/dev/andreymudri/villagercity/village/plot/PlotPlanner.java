package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Slice 1 plot rule: spiral out from the village center and take the first buildable spot. */
public final class PlotPlanner {
    public static final int SEARCH_MARGIN = 16;
    public static final int STEP = 2;
    public static final int SCAN_UP = 8;
    public static final int SCAN_DOWN = 8;
    public static final int MAX_CANDIDATES = 1500;

    private PlotPlanner() {
    }

    /** Returns the origin (minimum corner, at the first free y above ground) of a buildable plot. */
    public static Optional<BlockPos> find(ServerLevel level, VillageData village, Vec3i size) {
        return find(level, village, size, pos -> true);
    }

    /** As {@link #find(ServerLevel, VillageData, Vec3i)}, skipping buildable plots whose origin fails the predicate. */
    public static Optional<BlockPos> find(ServerLevel level, VillageData village, Vec3i size, Predicate<BlockPos> originAllowed) {
        BlockPos center = village.center();
        int reach = village.radius() + SEARCH_MARGIN;
        List<Footprint> occupied = village.occupiedFootprints();
        Map<Long, Column> cache = new HashMap<>();
        int checked = 0;
        for (int[] offset : PlotRules.spiral(reach, STEP)) {
            if (checked++ >= MAX_CANDIDATES) {
                break;
            }
            int minX = center.getX() + offset[0] - size.getX() / 2;
            int minZ = center.getZ() + offset[1] - size.getZ() / 2;
            Footprint footprint = new Footprint(minX, minZ, minX + size.getX() - 1, minZ + size.getZ() - 1);
            if (!PlotRules.withinReach(footprint, center.getX(), center.getZ(), reach)
                    || PlotRules.overlapsAny(footprint, occupied)) {
                continue;
            }
            Footprint area = footprint.inflate(PlotRules.MARGIN);
            List<Column> columns = new ArrayList<>();
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    final int cx = x;
                    final int cz = z;
                    columns.add(cache.computeIfAbsent(BlockPos.asLong(cx, 0, cz), k -> sample(level, cx, cz, center.getY())));
                }
            }
            if (PlotRules.isBuildable(columns)) {
                BlockPos origin = new BlockPos(minX, PlotRules.buildY(columns), minZ);
                if (originAllowed.test(origin)) {
                    return Optional.of(origin);
                }
            }
        }
        return Optional.empty();
    }

    static Column sample(ServerLevel level, int x, int z, int referenceY) {
        int top = referenceY + SCAN_UP;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        if (!level.isLoaded(pos)) {
            return Column.MISSING;
        }
        for (int y = top; y >= referenceY - SCAN_DOWN; y--) {
            BlockState state = level.getBlockState(pos.setY(y));
            if (!state.getFluidState().isEmpty()) {
                return new Column(y + 1, false, true);
            }
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES)) {
                if (y == top) {
                    return Column.MISSING;
                }
                boolean natural = state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                        || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL);
                return new Column(y + 1, natural, false);
            }
        }
        return Column.MISSING;
    }
}
