package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Slice 1 plot rule: spiral out from the village center, at most {@link #MAX_REACH} blocks, and take the first buildable
 * spot. A column's ground is its topmost solid block, accepted within {@link #MAX_VERTICAL} blocks above and below the
 * bell, so a bell on a hill still finds the village's ground below it while caves and overhangs are never used.
 */
public final class PlotPlanner {
    public static final int SEARCH_MARGIN = 16;
    public static final int STEP = 2;
    /** How far above or below the bell a plot's ground may lie. */
    public static final int MAX_VERTICAL = 16;
    /** Farthest a spot's center may lie from the bell (Chebyshev), however large the village grows. */
    public static final int MAX_REACH = 48;

    private PlotPlanner() {
    }

    /** A buildable spot: its origin (minimum corner, at the floor's first free y) and the blocks to cut plus fill to level it. */
    public record Site(BlockPos origin, int earthwork) {
    }

    /** Returns the origin (minimum corner, at the first free y above ground) of a buildable plot. */
    public static Optional<BlockPos> find(ServerLevel level, VillageData village, Vec3i size) {
        return find(level, village, size, pos -> true);
    }

    /** As {@link #find(ServerLevel, VillageData, Vec3i)}, skipping buildable plots whose origin fails the predicate. */
    public static Optional<BlockPos> find(ServerLevel level, VillageData village, Vec3i size, Predicate<BlockPos> originAllowed) {
        return findSite(level, village, size, false, originAllowed).map(Site::origin);
    }

    /**
     * The first spot along the spiral whose footprint plus {@link PlotRules#MARGIN} is natural ground with no fluid and no
     * laid path. Ground varying by at most {@link PlotRules#MAX_HEIGHT_VARIANCE} is built at its highest level with no
     * earthwork. With {@code allowEarthwork}, ground varying by at most {@link Earthwork#MAX_VARIANCE} is also accepted
     * when levelling it at {@link Earthwork#best} takes at most {@link Earthwork#MAX_VOLUME} blocks; its origin sits on
     * that floor.
     */
    public static Optional<Site> findSite(ServerLevel level, VillageData village, Vec3i size, boolean allowEarthwork, Predicate<BlockPos> originAllowed) {
        BlockPos center = village.center();
        int reach = Math.min(village.radius() + SEARCH_MARGIN, MAX_REACH);
        List<Footprint> occupied = village.occupiedFootprints();
        boolean anyPath = !village.pathCells().isEmpty();
        int maxVariance = allowEarthwork ? Earthwork.MAX_VARIANCE : PlotRules.MAX_HEIGHT_VARIANCE;
        int[] groundYs = new int[(size.getX() + 2 * PlotRules.MARGIN) * (size.getZ() + 2 * PlotRules.MARGIN)];
        Long2ObjectMap<Column> cache = new Long2ObjectOpenHashMap<>();
        for (int[] offset : PlotRules.spiral(reach, STEP)) {
            int minX = center.getX() + offset[0] - size.getX() / 2;
            int minZ = center.getZ() + offset[1] - size.getZ() / 2;
            Footprint footprint = new Footprint(minX, minZ, minX + size.getX() - 1, minZ + size.getZ() - 1);
            if (!PlotRules.withinReach(footprint, center.getX(), center.getZ(), reach)
                    || PlotRules.overlapsAny(footprint, occupied)) {
                continue;
            }
            Footprint area = footprint.inflate(PlotRules.MARGIN);
            // The same rules as PlotRules.isBuildable, stopping at the first column that rules the spot out.
            boolean buildable = true;
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            int columns = 0;
            for (int x = area.minX(); x <= area.maxX() && buildable; x++) {
                for (int z = area.minZ(); z <= area.maxZ() && buildable; z++) {
                    if (anyPath && village.isPathColumn(x, z)) {
                        buildable = false;
                        break;
                    }
                    long key = BlockPos.asLong(x, 0, z);
                    Column column = cache.get(key);
                    if (column == null) {
                        column = sample(level, x, z, center.getY());
                        cache.put(key, column);
                    }
                    low = Math.min(low, column.groundY());
                    high = Math.max(high, column.groundY());
                    groundYs[columns++] = column.groundY();
                    buildable = column.present() && column.natural() && !column.fluid() && high - low <= maxVariance;
                }
            }
            if (!buildable) {
                continue;
            }
            Site site;
            if (high - low <= PlotRules.MAX_HEIGHT_VARIANCE) {
                site = new Site(new BlockPos(minX, high, minZ), 0);
            } else {
                Earthwork.Level best = Earthwork.best(groundYs);
                if (best.volume() > Earthwork.MAX_VOLUME) {
                    continue;
                }
                site = new Site(new BlockPos(minX, best.floorY(), minZ), best.volume());
            }
            if (originAllowed.test(site.origin())) {
                return Optional.of(site);
            }
        }
        return Optional.empty();
    }

    /**
     * The column's ground: its topmost block that blocks motion, leaves and barriers aside (GameTest areas have a barrier
     * ceiling). Missing when the column is unloaded or that ground lies more than {@link #MAX_VERTICAL} blocks from the
     * bell, so nothing is ever built in a cave or under an overhang.
     */
    static Column sample(ServerLevel level, int x, int z, int referenceY) {
        if (!level.isLoaded(new BlockPos(x, referenceY, z))) {
            return Column.MISSING;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = surface; y >= referenceY - MAX_VERTICAL; y--) {
            BlockState state = level.getBlockState(pos.setY(y));
            if (y > referenceY + MAX_VERTICAL) {
                if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BARRIER)) {
                    return Column.MISSING;
                }
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return new Column(y + 1, false, true);
            }
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BARRIER)) {
                boolean natural = state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                        || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL);
                return new Column(y + 1, natural, false);
            }
        }
        return Column.MISSING;
    }
}
