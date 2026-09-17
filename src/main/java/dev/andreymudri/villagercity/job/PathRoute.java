package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.task.DigStep;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A* search for a walkable route between two points, laid with steps and bridges where the ground is uneven.
 * A state is a column (x, z) plus the height {@code h} a villager stands at there; a route is the ordered list of
 * cells the paver builds, from the start to a cell near the goal or onto an already-laid path.
 */
public final class PathRoute {
    /** Routes longer than this many steps are refused. */
    public static final int MAX_STEPS = 64;
    /** No more than this many states are expanded before the search gives up. */
    public static final int MAX_EXPANDED = 4096;
    /** How far a state's height may sit above or below its own column's ground. */
    private static final int MAX_ABOVE_GROUND = 2;
    /** Chebyshev distance to the goal at which a route counts as arrived. */
    private static final int GOAL_RADIUS = 2;

    /** One built cell of a route: the air cell a villager walks in, and how its support was built. */
    public record Cell(BlockPos surface, Kind kind) {
    }

    /** How a cell's support was built: level with the natural ground, raised, cut into it, or bridged over water. */
    public enum Kind { GROUND, RAISED, CUT, BRIDGE }

    private record State(int x, int z, int h) {
    }

    private record Ground(int y, boolean fluid) {
        static final Ground MISSING = new Ground(Integer.MIN_VALUE, false);

        boolean present() {
            return y != Integer.MIN_VALUE;
        }
    }

    private static final class Node {
        final State state;
        final int g;
        final Kind kind;
        final int depth;
        final @Nullable Node parent;

        Node(State state, int g, Kind kind, int depth, @Nullable Node parent) {
            this.state = state;
            this.g = g;
            this.kind = kind;
            this.depth = depth;
            this.parent = parent;
        }
    }

    private PathRoute() {
    }

    /** Finds a buildable route from {@code start} to within {@link #GOAL_RADIUS} blocks of {@code goal} (or onto an existing path). */
    public static Optional<List<Cell>> find(ServerLevel level, VillageData village, BlockPos start, BlockPos goal) {
        Ground startGround = groundAt(level, start.getX(), start.getZ());
        if (!startGround.present()) {
            return Optional.empty();
        }
        if (blocked(level, start) || blocked(level, start.above())) {
            return Optional.empty();
        }
        List<Footprint> occupied = village.occupiedFootprints();
        Set<Long> ownPrefix = ownPrefixColumns(village, start);
        State startState = new State(start.getX(), start.getZ(), start.getY());
        Node startNode = new Node(startState, 0, kindOf(start.getY(), startGround), 0, null);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(n -> n.g + heuristic(n.state, goal)));
        Map<State, Integer> bestG = new HashMap<>();
        open.add(startNode);
        bestG.put(startState, 0);

        int expanded = 0;
        while (!open.isEmpty()) {
            if (expanded >= MAX_EXPANDED) {
                return Optional.empty();
            }
            Node node = open.poll();
            if (node.g > bestG.getOrDefault(node.state, Integer.MAX_VALUE)) {
                continue;
            }
            expanded++;
            if (reachedGoal(node.state, goal, village, ownPrefix)) {
                return Optional.of(reconstruct(node));
            }
            if (node.depth >= MAX_STEPS) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int nx = node.state.x() + direction.getStepX();
                int nz = node.state.z() + direction.getStepZ();
                if (!(nx == start.getX() && nz == start.getZ()) && insideAny(occupied, nx, nz)) {
                    continue;
                }
                Ground ground = groundAt(level, nx, nz);
                if (!ground.present()) {
                    continue;
                }
                int minH = Math.max(node.state.h() - 1, ground.y() - MAX_ABOVE_GROUND);
                int maxH = Math.min(node.state.h() + 1, ground.y() + MAX_ABOVE_GROUND);
                for (int h = minH; h <= maxH; h++) {
                    BlockPos surface = new BlockPos(nx, h, nz);
                    if (blocked(level, surface) || blocked(level, surface.above())) {
                        continue;
                    }
                    boolean bridge = ground.fluid() || h - ground.y() > MAX_ABOVE_GROUND;
                    int cost = 1 + 2 * Math.abs(h - ground.y()) + (bridge ? 3 : 0);
                    Kind kind = bridge ? Kind.BRIDGE : kindOf(h, ground);
                    State next = new State(nx, nz, h);
                    int g = node.g + cost;
                    if (g < bestG.getOrDefault(next, Integer.MAX_VALUE)) {
                        bestG.put(next, g);
                        open.add(new Node(next, g, kind, node.depth + 1, node));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Kind kindOf(int h, Ground ground) {
        if (h == ground.y()) {
            return Kind.GROUND;
        }
        return h > ground.y() ? Kind.RAISED : Kind.CUT;
    }

    private static int heuristic(State state, BlockPos goal) {
        return Math.abs(state.x() - goal.getX()) + Math.abs(state.z() - goal.getZ());
    }

    /**
     * Reached when within {@link #GOAL_RADIUS} of {@code goal}, or on a path column that is not part of
     * {@code ownPrefix}. Without excluding it, a route recomputed mid-build (a fresh job, or after enough
     * failures re-queue the house) starts on the door cell it already laid, calls that one cell a finished route,
     * and the house leaves the queue with its path unfinished.
     */
    private static boolean reachedGoal(State state, BlockPos goal, VillageData village, Set<Long> ownPrefix) {
        int dx = Math.abs(state.x() - goal.getX());
        int dz = Math.abs(state.z() - goal.getZ());
        if (Math.max(dx, dz) <= GOAL_RADIUS) {
            return true;
        }
        return village.isPathColumn(state.x(), state.z()) && !ownPrefix.contains(BlockPos.asLong(state.x(), 0, state.z()));
    }

    /**
     * The columns of an already-laid path reachable from {@code start} by walking only through other laid path
     * columns: this house's own unfinished work (or empty when {@code start} itself is not yet a path column).
     */
    private static Set<Long> ownPrefixColumns(VillageData village, BlockPos start) {
        Set<Long> visited = new HashSet<>();
        if (!village.isPathColumn(start.getX(), start.getZ())) {
            return visited;
        }
        visited.add(BlockPos.asLong(start.getX(), 0, start.getZ()));
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {start.getX(), start.getZ()});
        while (!queue.isEmpty() && visited.size() <= MAX_EXPANDED) {
            int[] xz = queue.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int nx = xz[0] + direction.getStepX();
                int nz = xz[1] + direction.getStepZ();
                long packed = BlockPos.asLong(nx, 0, nz);
                if (visited.contains(packed) || !village.isPathColumn(nx, nz)) {
                    continue;
                }
                visited.add(packed);
                queue.add(new int[] {nx, nz});
            }
        }
        return visited;
    }

    private static boolean insideAny(List<Footprint> footprints, int x, int z) {
        for (Footprint footprint : footprints) {
            if (footprint.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** A cell blocks the route unless it is open (air, replaceable vegetation) or natural ground the paver can dig. */
    private static boolean blocked(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !DigStep.isDiggable(level, pos) && !state.canBeReplaced();
    }

    /**
     * The column's ground: the first free y above the topmost block that blocks motion, leaves and the GameTest
     * barrier ceiling aside (as {@code PlotPlanner.sample}). A fluid column's ground sits right above the fluid.
     */
    private static Ground groundAt(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        if (!level.isLoaded(pos)) {
            return Ground.MISSING;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int y = surface; y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(pos.setY(y));
            if (!state.getFluidState().isEmpty()) {
                return new Ground(y + 1, true);
            }
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BARRIER)) {
                return new Ground(y + 1, false);
            }
        }
        return Ground.MISSING;
    }

    private static List<Cell> reconstruct(Node goalNode) {
        List<Cell> cells = new ArrayList<>();
        for (Node node = goalNode; node != null; node = node.parent) {
            cells.add(new Cell(new BlockPos(node.state.x(), node.state.h(), node.state.z()), node.kind));
        }
        Collections.reverse(cells);
        return cells;
    }
}
