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
    /** Logs this many blocks above the base or lower belong to the trunk and may only be the base's 2x2 neighbours. */
    public static final int GROUND_LAYERS = 1;
    public static final int GROUND_SPREAD = 1;
    public static final int MIN_LEAVES = 4;
    private static final List<int[]> SPIRAL = PlotRules.spiral(SEARCH_RADIUS, 1);

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
                Optional<Tree> tree = trunk(level, pos);
                if (tree.isPresent()) {
                    return tree;
                }
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Collects up to {@link #MAX_TRUNK} connected logs of the base's kind, upward and sideways. Empty when the base is
     * not a log, or when it breaks one of four rules:
     * <ul>
     *   <li>logs at most {@link #GROUND_LAYERS} above the base stay within {@link #GROUND_SPREAD} block of it
     *       (Chebyshev), so a trunk is one column or a 2x2 while a log wall on the ground is not;</li>
     *   <li>higher logs (branches) reach at most {@link #MAX_SPREAD} blocks sideways; vanilla cherry and mega jungle
     *       branches reach 5;</li>
     *   <li>every log standing on a log of its kind stands on one of the tree's own logs, so a raised beam or ring
     *       that rests on other posts (a player's frame) is not part of a tree, and the walk must not run past
     *       {@link #MAX_TRUNK} logs;</li>
     *   <li>at least {@link #MIN_LEAVES} distinct natural (non-persistent) leaves touch the logs;</li>
     *   <li>the base has a log of its kind directly above it.</li>
     * </ul>
     */
    public static Optional<Tree> trunk(ServerLevel level, BlockPos base) {
        BlockState baseState = level.getBlockState(base);
        if (!baseState.is(BlockTags.LOGS) || !level.getBlockState(base.above()).is(baseState.getBlock())) {
            return Optional.empty();
        }
        Block log = baseState.getBlock();
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base.immutable());
        seen.add(base.immutable());
        Set<BlockPos> leaves = new HashSet<>();
        while (!queue.isEmpty() && logs.size() < MAX_TRUNK) {
            BlockPos pos = queue.poll();
            logs.add(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!seen.add(next)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(next);
                        if (state.is(log)) {
                            int spread = Math.max(Math.abs(next.getX() - base.getX()), Math.abs(next.getZ() - base.getZ()));
                            if (spread > (next.getY() - base.getY() <= GROUND_LAYERS ? GROUND_SPREAD : MAX_SPREAD)) {
                                return Optional.empty();
                            }
                            queue.add(next);
                        } else if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
                            leaves.add(next);
                        }
                    }
                }
            }
        }
        if (!queue.isEmpty() || leaves.size() < MIN_LEAVES) {
            return Optional.empty();
        }
        Set<BlockPos> tree = new HashSet<>(logs);
        for (BlockPos pos : logs) {
            if (!pos.equals(base) && level.getBlockState(pos.below()).is(log) && !tree.contains(pos.below())) {
                return Optional.empty();
            }
        }
        logs.sort(Comparator.comparingInt(BlockPos::getY));
        return Optional.of(new Tree(base.immutable(), List.copyOf(logs), log));
    }
}
