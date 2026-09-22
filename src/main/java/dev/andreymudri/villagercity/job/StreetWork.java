package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/**
 * The paver's street work: grows the village's street graph one straight run at a time. A run starts at a street end
 * ({@link VillageData#streetEnds}), or at the bell while the graph is empty, goes away from the bell, and lays up to
 * {@link #RUN_LENGTH} centre cells, each with a side cell on either hand at the centre's own height, so a street is
 * {@link #WIDTH} cells wide. Every cell is classified by {@link PathRoute#cellAt} and built by
 * {@link PathWork#planCell}. The three cells of one slice across the street are recorded together once all three are
 * built: the sides as path cells, the centre as a street cell one hop deeper than the end the run started from. A run
 * ends before a slice that a house or plot recorded since the run was laid out now covers.
 *
 * <p>The run being laid, and the cells this job gave up on after {@link PathWork#MAX_CONSECUTIVE_FAILURES} failed
 * tasks, are held only in memory: a fresh job starts a new run from whichever end the graph now has.
 */
public final class StreetWork implements Job {
    /** Most centre cells one run lays. */
    public static final int RUN_LENGTH = 8;
    /** Most a centre cell may sit above or below the one before it. */
    public static final int MAX_STEP = 1;
    /** Farthest a centre cell may lie from the bell, counted as the larger of the x and z distances. */
    public static final int REACH = 64;
    /** Cells across a street: the centre and one side cell on either hand. */
    public static final int WIDTH = 3;
    /** Most a side cell's ground may sit above or below the centre it is levelled to. */
    public static final int SIDE_STEP = 2;
    /** Street ends this many hops from the bell are never extended. */
    public static final int MAX_HOPS = 6;

    /** Where a run starts and which way it goes. */
    public record Run(StreetCell end, Direction direction) {
    }

    /** One slice across a street: the centre cell, which goes into the graph, and the side cells beside it. */
    public record Slice(PathRoute.Cell centre, List<PathRoute.Cell> sides) {
        List<PathRoute.Cell> cells() {
            List<PathRoute.Cell> cells = new ArrayList<>(WIDTH);
            cells.add(centre);
            cells.addAll(sides);
            return cells;
        }
    }

    /** Columns, packed with {@code BlockPos.asLong(x, 0, z)}, this job gave up building on. */
    private final Set<Long> refused = new HashSet<>();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private @Nullable Run run;
    private List<Slice> slices = List.of();
    private @Nullable BlockPos building;
    private @Nullable String waitingFor;

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        waitingFor = null;
        building = null;
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        PathWork.retireHouseToBellPaths(level, village);
        if (run == null) {
            Optional<Run> next = nextRun(level, village, refused);
            if (next.isEmpty()) {
                waitingFor = "room to grow";
                return null;
            }
            run = next.get();
            slices = layout(level, village, run, refused, RUN_LENGTH);
        }
        int hops = run.end().hops() + 1;
        List<Footprint> occupied = village.occupiedFootprints();
        for (Slice slice : slices) {
            if (covered(slice, occupied)) {
                // A plot claimed, or a house recorded, since the run was laid out: the run ends before it.
                break;
            }
            for (PathRoute.Cell cell : slice.cells()) {
                PathWork.CellStep step = PathWork.planCell(ctx, village, cell, this::dropRun);
                if (step.task() != null) {
                    building = step.building() ? cell.surface() : null;
                    return step.task();
                }
                if (step.waitingFor() != null) {
                    waitingFor = step.waitingFor();
                    return null;
                }
            }
            slice.sides().forEach(side -> village.addPathCell(side.surface()));
            village.addStreetCell(slice.centre().surface(), hops);
            VillageRegistry.get(level).setDirty();
        }
        run = null;
        slices = List.of();
        return null;
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        BlockPos cell = building;
        if (cell == null || run == null) {
            // Nothing was being built, or the run was dropped because the cell turned out obstructed: that is not
            // one more failure of the MAX_CONSECUTIVE_FAILURES kind.
            return;
        }
        if (status != Task.Status.FAILED) {
            failures.remove(cell);
            return;
        }
        if (failures.merge(cell, 1, Integer::sum) >= PathWork.MAX_CONSECUTIVE_FAILURES) {
            VillagerCity.LOGGER.info("giving up on the street cell at {} after {} failed tasks", cell, PathWork.MAX_CONSECUTIVE_FAILURES);
            failures.remove(cell);
            refused.add(column(cell.getX(), cell.getZ()));
            dropRun(cell);
        }
    }

    /** Whether any cell of {@code slice} lies on one of the {@code occupied} footprints. */
    private static boolean covered(Slice slice, List<Footprint> occupied) {
        for (PathRoute.Cell cell : slice.cells()) {
            BlockPos pos = cell.surface();
            if (occupied.stream().anyMatch(footprint -> footprint.contains(pos.getX(), pos.getZ()))) {
                return true;
            }
        }
        return false;
    }

    /** Forgets the run being laid, so the next {@link #plan} starts a new one from the graph as it now is. */
    private void dropRun(BlockPos obstruction) {
        if (run != null) {
            VillagerCity.LOGGER.info("a block at {} stops the street run from {}; planning a new run", obstruction, run.end().pos());
        }
        run = null;
        slices = List.of();
    }

    /**
     * The run to lay next: from the street end with the fewest hops (below {@link #MAX_HOPS}), preferring an end level
     * with the cell it grew from, in the direction that takes it farthest from the bell among those whose first slice
     * can be laid. Empty when no end can grow. An empty graph grows from the bell: its own cell is the root end, at hop 0.
     */
    public static Optional<Run> nextRun(ServerLevel level, VillageData village) {
        return nextRun(level, village, Set.of());
    }

    private static Optional<Run> nextRun(ServerLevel level, VillageData village, Set<Long> refused) {
        List<StreetCell> streets = village.streets();
        BlockPos bell = village.center();
        List<StreetCell> ends = new ArrayList<>(village.streetEnds().stream().filter(end -> end.hops() < MAX_HOPS).toList());
        ends.sort(Comparator.comparingInt(StreetCell::hops).thenComparing(end -> !levelWithParent(streets, end)));
        if (streets.isEmpty()) {
            // The first run starts at the bell: its own cell is the root, at hop 0, though it is never recorded.
            ends.add(new StreetCell(bell, 0));
        }
        for (StreetCell end : ends) {
            for (Direction direction : awayFromBell(end.pos(), bell)) {
                Run candidate = new Run(end, direction);
                if (!layout(level, village, candidate, refused, 1).isEmpty()) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The slices of {@code run}, at most {@code limit} of them, stopping before the first one that cannot be laid: a
     * centre beyond {@link #REACH}, more than {@link #MAX_STEP} above or below the previous centre, on a fluid other
     * than water, or on a column {@link PathRoute#cellAt} refuses; a side cell whose ground is more than {@link
     * #SIDE_STEP} off the centre, or that {@link PathRoute#cellAt} refuses at the centre's height; or any of the three
     * on a house, plot, storehouse, the bell, a street cell, or a path cell that is not one of the end's own side cells.
     */
    public static List<Slice> layout(ServerLevel level, VillageData village, Run run) {
        return layout(level, village, run, Set.of(), RUN_LENGTH);
    }

    private static List<Slice> layout(ServerLevel level, VillageData village, Run run, Set<Long> refused, int limit) {
        List<Footprint> occupied = village.occupiedFootprints();
        Set<Long> graph = new HashSet<>();
        village.streets().forEach(cell -> graph.add(column(cell.pos().getX(), cell.pos().getZ())));
        BlockPos end = run.end().pos();
        BlockPos bell = village.center();
        Direction forward = run.direction();
        Direction across = forward.getClockWise();
        List<Slice> slices = new ArrayList<>();
        int previousY = end.getY();
        for (int i = 1; i <= limit; i++) {
            int x = end.getX() + forward.getStepX() * i;
            int z = end.getZ() + forward.getStepZ() * i;
            if (Math.max(Math.abs(x - bell.getX()), Math.abs(z - bell.getZ())) > REACH) {
                break;
            }
            if (!free(village, occupied, graph, refused, end, x, z)) {
                break;
            }
            Optional<PathRoute.Cell> centre = centreCell(level, x, z, previousY);
            if (centre.isEmpty()) {
                break;
            }
            int h = centre.get().surface().getY();
            List<PathRoute.Cell> sides = new ArrayList<>(WIDTH - 1);
            for (int side : new int[] {-1, 1}) {
                int sx = x + across.getStepX() * side;
                int sz = z + across.getStepZ() * side;
                if (!free(village, occupied, graph, refused, end, sx, sz)) {
                    break;
                }
                Optional<PathRoute.Cell> cell = sideCell(level, sx, sz, h);
                if (cell.isEmpty()) {
                    break;
                }
                sides.add(cell.get());
            }
            if (sides.size() < WIDTH - 1) {
                break;
            }
            slices.add(new Slice(centre.get(), List.copyOf(sides)));
            previousY = h;
        }
        return slices;
    }

    /** A centre cell on the column's own ground, or empty when that is too far a step or a fluid it cannot bridge. */
    private static Optional<PathRoute.Cell> centreCell(ServerLevel level, int x, int z, int previousY) {
        PathRoute.Ground ground = PathRoute.groundAt(level, x, z);
        if (!ground.present() || Math.abs(ground.y() - previousY) > MAX_STEP) {
            return Optional.empty();
        }
        if (ground.fluid() && !isWater(level, x, ground.y() - 1, z)) {
            return Optional.empty();
        }
        return PathRoute.cellAt(level, x, z, ground.y());
    }

    /** A side cell levelled to the centre's height {@code h}, or empty when its ground is too far off or unbridgeable. */
    private static Optional<PathRoute.Cell> sideCell(ServerLevel level, int x, int z, int h) {
        PathRoute.Ground ground = PathRoute.groundAt(level, x, z);
        if (!ground.present()) {
            return Optional.empty();
        }
        if (ground.fluid() ? !isWater(level, x, ground.y() - 1, z) : Math.abs(ground.y() - h) > SIDE_STEP) {
            return Optional.empty();
        }
        return PathRoute.cellAt(level, x, z, h);
    }

    private static boolean isWater(ServerLevel level, int x, int y, int z) {
        return level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER);
    }

    /**
     * Whether a run from {@code end} may lay a cell on column ({@code x}, {@code z}): not refused, not on any occupied
     * footprint, not on a street cell, and not on a path cell unless it is one of the end's own side cells, which a
     * run turning off the end's street has to cross.
     */
    private static boolean free(VillageData village, List<Footprint> occupied, Set<Long> graph, Set<Long> refused, BlockPos end, int x, int z) {
        long column = column(x, z);
        if (refused.contains(column) || graph.contains(column)) {
            return false;
        }
        for (Footprint footprint : occupied) {
            if (footprint.contains(x, z)) {
                return false;
            }
        }
        boolean besideEnd = Math.max(Math.abs(x - end.getX()), Math.abs(z - end.getZ())) <= 1;
        return besideEnd || !village.isPathColumn(x, z);
    }

    /** The horizontal directions that take a step from {@code pos} farther from the bell, farthest first. */
    private static List<Direction> awayFromBell(BlockPos pos, BlockPos bell) {
        long here = distanceSqr(pos.getX(), pos.getZ(), bell);
        List<Direction> directions = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (distanceSqr(pos.getX() + direction.getStepX(), pos.getZ() + direction.getStepZ(), bell) > here) {
                directions.add(direction);
            }
        }
        directions.sort(Comparator.comparingLong((Direction d) -> distanceSqr(pos.getX() + d.getStepX(), pos.getZ() + d.getStepZ(), bell)).reversed());
        return directions;
    }

    private static long distanceSqr(int x, int z, BlockPos bell) {
        long dx = x - bell.getX();
        long dz = z - bell.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Whether {@code end} stands at the same height as the cell it grew from: the latest cell recorded before it that
     * lies within one block of it. A cell nothing grew into, a root, counts as level.
     */
    private static boolean levelWithParent(List<StreetCell> streets, StreetCell end) {
        int index = streets.indexOf(end);
        for (int i = index - 1; i >= 0; i--) {
            BlockPos parent = streets.get(i).pos();
            BlockPos pos = end.pos();
            if (Math.abs(parent.getX() - pos.getX()) <= 1 && Math.abs(parent.getY() - pos.getY()) <= 1
                    && Math.abs(parent.getZ() - pos.getZ()) <= 1) {
                return parent.getY() == pos.getY();
            }
        }
        return true;
    }

    private static long column(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }
}
