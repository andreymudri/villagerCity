package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Where the next house goes ({@link #findSite}): beside the village's streets once it has any, and before that the first
 * buildable spot spiralling out from the bell, at most {@link #MAX_REACH} blocks, trying the spots nearest the houses
 * and storehouse the village already has before the rest ({@link #candidates}). A column's ground is its topmost solid block, accepted within {@link #verticalReach} blocks above and below
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

    /** Returns the origin (minimum corner, at the first free y above ground) of a plot needing no earthwork ({@link #findSite} without a paver). */
    public static Optional<BlockPos> find(ServerLevel level, VillageData village, Vec3i size) {
        return find(level, village, size, pos -> true);
    }

    /** As {@link #find(ServerLevel, VillageData, Vec3i)}, skipping buildable plots whose origin fails the predicate. */
    public static Optional<BlockPos> find(ServerLevel level, VillageData village, Vec3i size, Predicate<BlockPos> originAllowed) {
        return findSite(level, village, size, false, originAllowed).map(Site::origin);
    }

    /**
     * A buildable house pad, or empty. Every pad must lie within the search reach, keep {@link PlotRules#MARGIN} off
     * whatever the village has built, keep {@link PlotRules#HOUSE_GAP} open columns off every house and plot, and have a
     * footprint plus margin of natural ground with no fluid and no laid path. Its floor must need earthwork the village
     * can do ({@link PlotRules#earthworkAllowed}): with {@code allowEarthwork} (a paver) up to the paver's budget, without
     * it none at all, which still allows ground the floor spans ({@link Earthwork#needsNone}).
     * <p>
     * A village with street cells only builds beside its streets: a pad must touch one ({@link #touchingStreets}), its
     * floor is that street cell's y, one pad in {@link PlotRules#SKIP_ONE_IN} is left empty ({@link PlotRules#skipped}),
     * and the pads are tried fewest hops first, then least earthwork, then nearest the bell. A village with no street
     * cells tries the spots in {@link #candidates} order, as before it had streets, and takes the first ({@link #floor}).
     */
    public static Optional<Site> findSite(ServerLevel level, VillageData village, Vec3i size, boolean allowEarthwork, Predicate<BlockPos> originAllowed) {
        BlockPos center = village.center();
        int reach = searchReach(village);
        List<Footprint> occupied = village.occupiedFootprints();
        List<Footprint> houses = houseFootprints(village);
        boolean anyPath = !village.pathCells().isEmpty();
        boolean onStreets = !village.streets().isEmpty();
        Long2ObjectMap<List<StreetCell>> streets = streetColumns(village);
        int[] groundYs = new int[(size.getX() + 2 * PlotRules.MARGIN) * (size.getZ() + 2 * PlotRules.MARGIN)];
        // Ground spanning more than twice the column step has no floor within the step of every column, and ground
        // spanning more than FLOOR_SPAN none that needs no earthwork.
        int maxRange = allowEarthwork ? 2 * Earthwork.MAX_COLUMN_STEP : Earthwork.FLOOR_SPAN;
        Long2ObjectMap<Column> cache = new Long2ObjectOpenHashMap<>();
        List<int[]> order = onStreets ? PlotRules.spiral(reach, STEP) : candidates(village, reach);
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            int[] offset = order.get(i);
            int minX = center.getX() + offset[0] - size.getX() / 2;
            int minZ = center.getZ() + offset[1] - size.getZ() / 2;
            Footprint footprint = new Footprint(minX, minZ, minX + size.getX() - 1, minZ + size.getZ() - 1);
            if (!PlotRules.withinReach(footprint, center.getX(), center.getZ(), reach)
                    || PlotRules.overlapsAny(footprint, occupied)
                    || PlotRules.tooCloseToAHouse(footprint, houses)) {
                continue;
            }
            Footprint area = footprint.inflate(PlotRules.MARGIN);
            List<StreetCell> touching = List.of();
            if (onStreets) {
                touching = touchingStreets(village, streets, area);
                if (touching.isEmpty()) {
                    continue;
                }
            }
            // Natural ground, no fluid, no path, stopping at the first column that rules the spot out.
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
                    buildable = column.present() && column.natural() && !column.fluid() && high - low <= maxRange;
                }
            }
            if (!buildable) {
                continue;
            }
            Floor floor = floor(groundYs, touching, allowEarthwork);
            if (!floor.accepted()) {
                continue;
            }
            Site site = new Site(new BlockPos(minX, floor.y(), minZ), floor.earthwork());
            if (!onStreets) {
                if (originAllowed.test(site.origin())) {
                    return Optional.of(site);
                }
                continue;
            }
            if (!PlotRules.skipped(site.origin())) {
                ranked.add(new Ranked(site, floor.street().hops(), Math.max(Math.abs(offset[0]), Math.abs(offset[1])), i));
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::hops)
                .thenComparingInt(entry -> entry.site().earthwork())
                .thenComparingInt(Ranked::ring)
                .thenComparingInt(Ranked::index));
        for (Ranked entry : ranked) {
            if (originAllowed.test(entry.site().origin())) {
                return Optional.of(entry.site());
            }
        }
        return Optional.empty();
    }

    /** A street pad that passed every rule, with the keys it is ordered by. */
    private record Ranked(Site site, int hops, int ring, int index) {
    }

    /** How far from the bell (Chebyshev) a spot's footprint may reach: the village's radius plus {@link #SEARCH_MARGIN}, capped. */
    public static int searchReach(VillageData village) {
        return Math.min(village.radius() + SEARCH_MARGIN, MAX_REACH);
    }

    /** The footprints a pad keeps {@link PlotRules#HOUSE_GAP} columns off: every house and plot, and nothing else. */
    public static List<Footprint> houseFootprints(VillageData village) {
        List<Footprint> houses = new ArrayList<>();
        village.houses().forEach(house -> houses.add(house.footprint()));
        village.plots().forEach(plot -> houses.add(plot.footprint()));
        return houses;
    }

    /**
     * The floor a pad is levelled to, how much earthwork that takes, the worst column's step, the street cell it opens
     * onto (null off the streets), and whether the village can do that work.
     */
    public record Floor(int y, int earthwork, int columnStep, @Nullable StreetCell street, boolean accepted) {
    }

    /**
     * The floor for these ground columns. With no {@code touching} street cells it is the highest column when the floor
     * there needs no earthwork ({@link Earthwork#needsNone}), else {@link Earthwork#best}. Otherwise it
     * is one of those cells' y, choosing a floor the village can level before one it cannot, then the fewest hops, then
     * the least earthwork.
     */
    public static Floor floor(int[] groundYs, List<StreetCell> touching, boolean allowEarthwork) {
        if (touching.isEmpty()) {
            int high = Arrays.stream(groundYs).max().orElseThrow();
            if (Earthwork.needsNone(groundYs, high)) {
                return at(groundYs, high, null, allowEarthwork);
            }
            return at(groundYs, Earthwork.best(groundYs).floorY(), null, allowEarthwork);
        }
        Floor chosen = null;
        for (StreetCell street : touching) {
            Floor floor = at(groundYs, street.pos().getY(), street, allowEarthwork);
            if (chosen == null || better(floor, chosen)) {
                chosen = floor;
            }
        }
        return chosen;
    }

    /** The floor at {@code y}: no earthwork when the floor covers the ground as it lies, else the blocks to cut plus fill. */
    private static Floor at(int[] groundYs, int y, @Nullable StreetCell street, boolean allowEarthwork) {
        int earthwork = Earthwork.needsNone(groundYs, y) ? 0 : Earthwork.volume(groundYs, y);
        int step = Earthwork.maxColumnStep(groundYs, y);
        return new Floor(y, earthwork, step, street, PlotRules.earthworkAllowed(earthwork, step, allowEarthwork));
    }

    private static boolean better(Floor a, Floor b) {
        if (a.accepted() != b.accepted()) {
            return a.accepted();
        }
        if (a.street().hops() != b.street().hops()) {
            return a.street().hops() < b.street().hops();
        }
        return a.earthwork() < b.earthwork();
    }

    /**
     * The street cells a pad opens onto: those in a column right beside its footprint plus margin ({@code area}), and
     * those right beside a laid path cell that is. A street's side cells are path cells but not street cells, so a pad
     * beside a wide street reaches its centre line through them.
     */
    public static List<StreetCell> touchingStreets(VillageData village, Footprint area) {
        return touchingStreets(village, streetColumns(village), area);
    }

    private static List<StreetCell> touchingStreets(VillageData village, Long2ObjectMap<List<StreetCell>> streets, Footprint area) {
        List<StreetCell> touching = new ArrayList<>();
        Footprint ring = area.inflate(1);
        for (int x = ring.minX(); x <= ring.maxX(); x++) {
            for (int z = ring.minZ(); z <= ring.maxZ(); z++) {
                if (area.contains(x, z)) {
                    continue;
                }
                List<StreetCell> here = streets.get(BlockPos.asLong(x, 0, z));
                if (here != null) {
                    addNew(touching, here);
                } else if (village.isPathColumn(x, z)) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            List<StreetCell> beside = streets.get(BlockPos.asLong(x + dx, 0, z + dz));
                            if (beside != null) {
                                addNew(touching, beside);
                            }
                        }
                    }
                }
            }
        }
        return touching;
    }

    private static void addNew(List<StreetCell> into, List<StreetCell> cells) {
        for (StreetCell cell : cells) {
            if (!into.contains(cell)) {
                into.add(cell);
            }
        }
    }

    /** The village's street cells by column (x and z only). */
    private static Long2ObjectMap<List<StreetCell>> streetColumns(VillageData village) {
        Long2ObjectMap<List<StreetCell>> columns = new Long2ObjectOpenHashMap<>();
        for (StreetCell cell : village.streets()) {
            columns.computeIfAbsent(BlockPos.asLong(cell.pos().getX(), 0, cell.pos().getZ()), key -> new ArrayList<>()).add(cell);
        }
        return columns;
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
    public static Column sample(ServerLevel level, int x, int z, int referenceY, int verticalReach) {
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
