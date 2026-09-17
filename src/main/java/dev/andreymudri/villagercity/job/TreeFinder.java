package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Finds natural trees: a log standing on dirt whose trunk passes {@link #trunk}. */
public final class TreeFinder {
    public static final int SEARCH_RADIUS = 24;
    public static final int VILLAGE_MARGIN = 32;
    /** Enough for the largest sapling trees (a mega jungle tree has about 130 logs). */
    public static final int MAX_TRUNK = 256;
    public static final int SCAN_DOWN = 6;
    public static final int SCAN_UP = 12;
    public static final int MAX_SPREAD = 6;
    public static final int MIN_LEAVES = 4;
    private static final List<int[]> SPIRAL = PlotRules.spiral(SEARCH_RADIUS, 1);

    /** True when the position lies inside a piece of a generated structure (a village house, a swamp hut). */
    public static boolean insideStructure(ServerLevel level, BlockPos pos) {
        return level.structureManager().hasAnyStructureAt(pos)
                && level.structureManager().getStructureWithPieceAt(pos, structure -> true).isValid();
    }

    public record Tree(BlockPos base, List<BlockPos> logs, Block logBlock) {
    }

    private TreeFinder() {
    }

    public static Optional<Tree> findNearest(ServerLevel level, BlockPos from, VillageData village) {
        return findNearest(level, from, village, base -> false);
    }

    /** As {@link #findNearest(ServerLevel, BlockPos, VillageData)}, ignoring every column whose base the predicate accepts. */
    public static Optional<Tree> findNearest(ServerLevel level, BlockPos from, VillageData village, Predicate<BlockPos> skipBase) {
        int villageReach = village.radius() + VILLAGE_MARGIN;
        for (int[] offset : SPIRAL) {
            int x = from.getX() + offset[0];
            int z = from.getZ() + offset[1];
            if (Math.abs(x - village.center().getX()) > villageReach || Math.abs(z - village.center().getZ()) > villageReach) {
                continue;
            }
            if (!level.isLoaded(new BlockPos(x, from.getY(), z))) {
                continue;
            }
            for (int y = from.getY() - SCAN_DOWN; y <= from.getY() + SCAN_UP; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).is(BlockTags.LOGS) || !level.getBlockState(pos.below()).is(BlockTags.DIRT)) {
                    continue;
                }
                if (skipBase.test(pos)) {
                    break;
                }
                Optional<Tree> tree = trunk(level, pos, !village.isFelling(pos));
                if (tree.isPresent()) {
                    return tree;
                }
                break;
            }
        }
        return Optional.empty();
    }

    /** As {@link #trunk(ServerLevel, BlockPos, boolean)}, requiring the canopy. */
    public static Optional<Tree> trunk(ServerLevel level, BlockPos base) {
        return trunk(level, base, true);
    }

    /**
     * Collects the logs of the base's kind that form one tree, walking upward and sideways from the base. Logs
     * farther than {@link #MAX_SPREAD} blocks sideways (Chebyshev) are not walked. A walked log standing on a log of
     * its kind that the walk did not reach belongs to another trunk (a neighbouring tree, or a post), so it is dropped,
     * together with every log reachable from the base only through it. Empty when:
     * <ul>
     *   <li>the base is not a log, or no log of its kind stands in the 3x3 directly above it (acacias lean from their
     *       first log);</li>
     *   <li>the walk runs past {@link #MAX_TRUNK} logs;</li>
     *   <li>the tree's logs on the base's own layer do not fit in a 2x2, so a trunk is one column or a 2x2 while a log
     *       wall on the ground, or two trunks grown flush against each other, are not;</li>
     *   <li>any tree log was placed by a player or citizen ({@link PlacedLogs}) or lies inside a generated structure
     *       piece, so builds and village houses are never felled;</li>
     *   <li>{@code requireCanopy} and fewer than {@link #MIN_LEAVES} distinct natural (non-persistent) leaves touch the
     *       tree. A tree whose felling already started has lost its canopy first, so its base is looked up without.</li>
     * </ul>
     */
    public static Optional<Tree> trunk(ServerLevel level, BlockPos base, boolean requireCanopy) {
        BlockState baseState = level.getBlockState(base);
        if (!baseState.is(BlockTags.LOGS)) {
            return Optional.empty();
        }
        Block log = baseState.getBlock();
        boolean logAbove = false;
        for (int dx = -1; dx <= 1 && !logAbove; dx++) {
            for (int dz = -1; dz <= 1 && !logAbove; dz++) {
                logAbove = level.getBlockState(base.offset(dx, 1, dz)).is(log);
            }
        }
        if (!logAbove) {
            return Optional.empty();
        }
        Set<BlockPos> walked = connected(base.immutable(), pos -> level.getBlockState(pos).is(log) && spread(base, pos) <= MAX_SPREAD, MAX_TRUNK + 1);
        if (walked.size() > MAX_TRUNK) {
            return Optional.empty();
        }
        Set<BlockPos> tree = walked;
        while (true) {
            Set<BlockPos> current = tree;
            Set<BlockPos> foreign = new HashSet<>();
            for (BlockPos pos : current) {
                if (!pos.equals(base) && level.getBlockState(pos.below()).is(log) && !current.contains(pos.below())) {
                    foreign.add(pos);
                }
            }
            if (foreign.isEmpty()) {
                break;
            }
            tree = connected(base.immutable(), pos -> current.contains(pos) && !foreign.contains(pos), MAX_TRUNK + 1);
        }
        PlacedLogs placed = PlacedLogs.get(level);
        Set<BlockPos> leaves = new HashSet<>();
        int minX = base.getX(), maxX = base.getX(), minZ = base.getZ(), maxZ = base.getZ();
        for (BlockPos pos : tree) {
            if (pos.getY() == base.getY()) {
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
                if (maxX - minX > 1 || maxZ - minZ > 1) {
                    return Optional.empty();
                }
            }
            if (placed.contains(pos) || insideStructure(level, pos)) {
                return Optional.empty();
            }
            forEachUpperNeighbour(pos, next -> {
                BlockState state = level.getBlockState(next);
                if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
                    leaves.add(next.immutable());
                }
            });
        }
        if (requireCanopy && leaves.size() < MIN_LEAVES) {
            return Optional.empty();
        }
        List<BlockPos> logs = new ArrayList<>(tree);
        logs.sort(Comparator.comparingInt(BlockPos::getY));
        return Optional.of(new Tree(base.immutable(), List.copyOf(logs), log));
    }

    /** Positions reachable from the base through accepted neighbours (sideways and upward), stopping once {@code limit} are found. */
    private static Set<BlockPos> connected(BlockPos base, Predicate<BlockPos> accept, int limit) {
        Set<BlockPos> found = new HashSet<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        seen.add(base);
        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos pos = queue.poll();
            found.add(pos);
            forEachUpperNeighbour(pos, next -> {
                BlockPos immutable = next.immutable();
                if (seen.add(immutable) && accept.test(immutable)) {
                    queue.add(immutable);
                }
            });
        }
        return found;
    }

    private static void forEachUpperNeighbour(BlockPos pos, Consumer<BlockPos> action) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        action.accept(pos.offset(dx, dy, dz));
                    }
                }
            }
        }
    }

    private static int spread(BlockPos base, BlockPos pos) {
        return Math.max(Math.abs(pos.getX() - base.getX()), Math.abs(pos.getZ() - base.getZ()));
    }
}
