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
import net.minecraft.world.phys.shapes.VoxelShape;

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

    /** Where the search actually begins: {@code start} itself, or the first of its neighbours that can be used instead. */
    private record StartPoint(BlockPos pos, Kind kind) {
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

    /** Finds a buildable route from {@code start} (or a neighbour of it, see {@link #resolveStart}) to within
     * {@link #GOAL_RADIUS} blocks of {@code goal} (or onto an existing path). */
    public static Optional<List<Cell>> find(ServerLevel level, VillageData village, BlockPos start, BlockPos goal) {
        List<Footprint> occupied = village.occupiedFootprints();
        Optional<StartPoint> resolvedStart = resolveStart(level, occupied, start);
        if (resolvedStart.isEmpty()) {
            return Optional.empty();
        }
        BlockPos routeStart = resolvedStart.get().pos();
        Set<Long> ownPrefix = ownPrefixColumns(village, routeStart);
        State startState = new State(routeStart.getX(), routeStart.getZ(), routeStart.getY());
        Node startNode = new Node(startState, 0, resolvedStart.get().kind(), 0, null);

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
                if (!(nx == routeStart.getX() && nz == routeStart.getZ()) && insideAny(occupied, nx, nz)) {
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
                    boolean bridge = ground.fluid() || h - ground.y() > MAX_ABOVE_GROUND;
                    Kind kind = bridge ? Kind.BRIDGE : kindOf(h, ground);
                    if (blocked(level, surface) || blocked(level, surface.above())) {
                        continue;
                    }
                    // GROUND cells stand right on top of whatever the column's topmost solid block already is,
                    // untouched either way (see planCell): a foreign one (a player's cobblestone terrace, say) is a
                    // perfectly fine surface to cross, so only RAISED/CUT/BRIDGE - which do place or dig at the
                    // support - need it to be paveable rather than a foreign obstruction (see supportBlocked). But
                    // "whatever the topmost solid block is" must still be something a villager can actually stand
                    // on (see standableSupport) - groundAt only asks blocksMotion(), which a fence, wall, fence gate
                    // or cactus all satisfy despite none of them being a real, flat, safe floor.
                    if (kind == Kind.GROUND) {
                        if (!standableSupport(level, surface.below())) {
                            continue;
                        }
                    } else if (supportBlocked(level, surface.below())) {
                        continue;
                    }
                    int cost = 1 + 2 * Math.abs(h - ground.y()) + (bridge ? 3 : 0);
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

    /**
     * {@code start} itself, if it can be stood on, or else the first of its horizontal neighbours (at the same
     * height) that can. {@code start} is exactly the cell {@code doorOutside} returns and a route can never detour
     * around it the way it can any other cell: without a fallback, one foreign block under the doorstep (a player's
     * cobblestone step, say, with the same {@code groundAt} height as the rest of the flat ground around it) would
     * refuse the whole route outright, permanently losing the house's path to the bell. A GROUND-kind start (see the
     * neighbour loop in {@link #find}) is fine to stand on regardless of what its support is made of.
     */
    private static Optional<StartPoint> resolveStart(ServerLevel level, List<Footprint> occupied, BlockPos start) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(start);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(start.relative(direction));
        }
        for (BlockPos candidate : candidates) {
            if (!candidate.equals(start) && insideAny(occupied, candidate.getX(), candidate.getZ())) {
                continue;
            }
            Ground ground = groundAt(level, candidate.getX(), candidate.getZ());
            if (!ground.present()) {
                continue;
            }
            if (blocked(level, candidate) || blocked(level, candidate.above())) {
                continue;
            }
            // Classified exactly as find()'s own neighbour loop does: a fluid column is always BRIDGE, whatever
            // height happens to be asked for, never GROUND just because the two heights happen to match. Without
            // this, a neighbour whose ground is water can be classified as a GROUND start - no planks placed, no
            // MakePath run, nothing built - and the route begins standing in open water.
            boolean bridge = ground.fluid() || candidate.getY() - ground.y() > MAX_ABOVE_GROUND;
            Kind kind = bridge ? Kind.BRIDGE : kindOf(candidate.getY(), ground);
            if (kind == Kind.GROUND) {
                if (!standableSupport(level, candidate.below())) {
                    continue;
                }
            } else if (supportBlocked(level, candidate.below())) {
                continue;
            }
            return Optional.of(new StartPoint(candidate, kind));
        }
        return Optional.empty();
    }

    /** A cell blocks the route unless it is open (air, replaceable vegetation) or natural ground the paver can dig. */
    private static boolean blocked(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !DigStep.isDiggable(level, pos) && !state.canBeReplaced();
    }

    /**
     * A RAISED, CUT or BRIDGE cell's support blocks the route only if it is a foreign object: not open ground a
     * RAISED or BRIDGE cell would place its own support into, not what building has already put there (dirt still
     * to pave, a finished dirt path, or a bridge's oak planks), and not natural ground a CUT simply leaves exposed.
     * Without this, a block dropped on a support cell after its route was cached (a player's block, say) has the
     * same {@code groundAt} height and passes every other check, so a route recomputed around it lands right back
     * on it and {@code planCell} is asked to build over something it never should. Never called for a GROUND cell
     * (see {@link #find} and {@link #resolveStart}): a GROUND cell's support is never touched either way, so a
     * foreign one there is simply a fine surface to cross, not something to detour around or bury under new dirt.
     */
    private static boolean supportBlocked(ServerLevel level, BlockPos support) {
        BlockState state = level.getBlockState(support);
        return !state.canBeReplaced() && !state.is(BlockTags.DIRT) && !state.is(Blocks.DIRT_PATH)
                && !state.is(Blocks.OAK_PLANKS) && !DigStep.isDiggable(level, support);
    }

    /**
     * Whether a GROUND cell's support is something a villager can actually stand on, not merely something that
     * satisfies {@code groundAt}'s {@code blocksMotion()} check. A fence, wall, fence gate or cactus all block
     * motion, so the heightmap and {@code groundAt} both report a walkable surface right on top of them, but none
     * of the four is a flat, safe floor: a fence/wall/gate's collision shape reaches well above a full block, so a
     * "surface" cell resting directly on top of one still clips into it, and a cactus is dangerous regardless of
     * its own shape. The generic {@code VoxelShape} check below is a second, tag-independent net for the same
     * class of problem (and, defensively, also rejects an empty/fluid shape - a support {@code find}/{@code
     * resolveStart} should never see once the fluid-to-BRIDGE promotion has already run, but that must never be
     * this method's job to enforce silently).
     */
    private static boolean standableSupport(ServerLevel level, BlockPos support) {
        BlockState state = level.getBlockState(support);
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)
                || state.is(Blocks.CACTUS)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, support);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
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
