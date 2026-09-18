package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Slice 1 plot rule: spiral out from the village center, at most {@link #MAX_REACH} blocks, and take the first buildable
 * spot, trying the spots nearest the houses and storehouse the village already has before the rest ({@link #candidates}). A column's ground is its topmost solid block, accepted within {@link #verticalReach} blocks above and below
 * the bell, so a bell on a hill still finds the village's ground below it while caves and overhangs are never used.
 */
public final class PlotPlanner {
    public static final int SEARCH_MARGIN = 16;
    public static final int STEP = 2;
    /** Least a plot's ground may lie above or below the bell, however young the village. */
    public static final int MIN_VERTICAL = 24;
    /** Most a plot's ground may ever lie above or below the bell. */
    public static final int MAX_VERTICAL = 48;
    /** Farthest a spot's center may lie from the bell (Chebyshev), however large the village grows. */
    public static final int MAX_REACH = 48;

    private PlotPlanner() {
    }

    /**
     * How far above or below the bell a plot's ground may lie: the village's own radius, never under
     * {@link #MIN_VERTICAL} and never over {@link #MAX_VERTICAL}. A young village stays gathered around its bell, while
     * one that has already grown outwards may climb the mountainside and drop into the valley as far as it reaches on
     * the flat, since {@link #MAX_VERTICAL} equals {@link #MAX_REACH}: the ground it may use is at most a cube.
     */
    public static int verticalReach(int radius) {
        return Mth.clamp(radius, MIN_VERTICAL, MAX_VERTICAL);
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
     * The first spot in {@link #candidates} order whose footprint plus {@link PlotRules#MARGIN} is natural ground with no
     * fluid and no laid path. Ground varying by at most {@link PlotRules#MAX_HEIGHT_VARIANCE} is built at its highest level with no
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
        for (int[] offset : candidates(village, reach)) {
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
                        column = sample(level, x, z, center.getY(), verticalReach(village.radius()));
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
     * The spots to try, ordered by how near they lie to what the village has already built: nearest anchor first, then
     * nearest the bell, then the spiral's own order, so the search stays deterministic. A village grows along the
     * ground that already worked for it instead of in rings around the bell, re-testing the same steep spots.
     * <p>
     * The offsets are scored once per search, not per column, so a large village pays one pass over the spiral.
     */
    static List<int[]> candidates(VillageData village, int reach) {
        BlockPos center = village.center();
        List<BlockPos> anchors = anchors(village);
        List<int[]> spiral = PlotRules.spiral(reach, STEP);
        int[][] scored = new int[spiral.size()][];
        for (int i = 0; i < spiral.size(); i++) {
            int[] offset = spiral.get(i);
            int x = center.getX() + offset[0];
            int z = center.getZ() + offset[1];
            int nearest = Integer.MAX_VALUE;
            for (BlockPos anchor : anchors) {
                nearest = Math.min(nearest, Math.max(Math.abs(x - anchor.getX()), Math.abs(z - anchor.getZ())));
            }
            // {dx, dz} first, so the search reads the entry as it read a spiral offset; then the sort keys.
            scored[i] = new int[] {offset[0], offset[1], nearest, Math.max(Math.abs(offset[0]), Math.abs(offset[1])), i};
        }
        Arrays.sort(scored, Comparator.<int[]>comparingInt(entry -> entry[2])
                .thenComparingInt(entry -> entry[3])
                .thenComparingInt(entry -> entry[4]));
        return Arrays.asList(scored);
    }

    /** What the village grows from: its finished houses and its storehouse, or the bell alone while it has neither. */
    private static List<BlockPos> anchors(VillageData village) {
        List<BlockPos> anchors = new ArrayList<>();
        for (BuildingRecord house : village.houses()) {
            Footprint footprint = house.footprint();
            anchors.add(new BlockPos((footprint.minX() + footprint.maxX()) / 2, house.origin().getY(), (footprint.minZ() + footprint.maxZ()) / 2));
        }
        if (village.storehousePos() != null) {
            anchors.add(village.storehousePos());
        }
        return anchors.isEmpty() ? List.of(village.center()) : anchors;
    }

    /**
     * The column's ground: its topmost block that blocks motion, leaves and barriers aside (GameTest areas have a barrier
     * ceiling). Missing when the column is unloaded or that ground lies more than {@code verticalReach} blocks from the
     * bell, so nothing is ever built in a cave or under an overhang.
     */
    static Column sample(ServerLevel level, int x, int z, int referenceY, int verticalReach) {
        if (!level.isLoaded(new BlockPos(x, referenceY, z))) {
            return Column.MISSING;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = surface; y >= referenceY - verticalReach; y--) {
            BlockState state = level.getBlockState(pos.setY(y));
            if (y > referenceY + verticalReach) {
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
