package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

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

    /**
     * True when the position lies inside a piece of a generated structure (a village house, a swamp hut). Only
     * structure starts already in memory are read, so the check never loads a chunk; a structure whose start chunk is
     * not available counts as covering the position, which only postpones felling there.
     */
    public static boolean insideStructure(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = level.getChunkSource().getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.STRUCTURE_REFERENCES, false);
        if (chunk == null) {
            return true;
        }
        for (Map.Entry<Structure, LongSet> references : chunk.getAllReferences().entrySet()) {
            for (long reference : references.getValue()) {
                ChunkPos startPos = new ChunkPos(reference);
                ChunkAccess startChunk = level.getChunkSource().getChunk(startPos.x, startPos.z, ChunkStatus.STRUCTURE_STARTS, false);
                if (startChunk == null) {
                    return true;
                }
                StructureStart start = startChunk.getStartForStructure(references.getKey());
                if (start != null && start.isValid() && level.structureManager().structureHasPieceAt(pos, start)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One tree. {@code base} is its westmost-then-northmost log on the ground layer, the same whichever trunk column
     * was scanned. {@code logs} run bottom to top. {@code natural} is false when the tree lacks a log above the scanned
     * column or its canopy, which only a tree whose felling already started may lack.
     */
    public record Tree(BlockPos base, List<BlockPos> logs, Block logBlock, boolean natural) {
    }

    private TreeFinder() {
    }

    public static Optional<Tree> findNearest(ServerLevel level, BlockPos from, VillageData village) {
        return findNearest(level, from, village, base -> false);
    }

    /**
     * As {@link #findNearest(ServerLevel, BlockPos, VillageData)}, ignoring every tree whose base the predicate accepts.
     * A tree whose felling the village remembers is accepted without the log above and the canopy, which top-down
     * felling removes first.
     */
    public static Optional<Tree> findNearest(ServerLevel level, BlockPos from, VillageData village, Predicate<BlockPos> skipBase) {
        int villageReach = village.radius() + VILLAGE_MARGIN;
        Set<BlockPos> walked = new HashSet<>();
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
                // Another column of a tree walked in this search, or a corner of a skipped or remembered 2x2 trunk.
                if (walked.contains(pos) || nearBase(pos, skipBase) || (!nearBase(pos, village::isFelling) && !hasLogAbove(level, pos))) {
                    break;
                }
                Optional<Tree> tree = shape(level, pos);
                if (tree.isPresent()) {
                    walked.addAll(tree.get().logs());
                    if (!skipBase.test(tree.get().base()) && (tree.get().natural() || village.isFelling(tree.get().base()))) {
                        return tree;
                    }
                }
                break;
            }
        }
        return Optional.empty();
    }

    /** A base is its trunk's westmost-then-northmost ground log, so a 2x2 trunk column finds it west, north or north-west. */
    private static boolean nearBase(BlockPos pos, Predicate<BlockPos> isBase) {
        return isBase.test(pos) || isBase.test(pos.west()) || isBase.test(pos.north()) || isBase.test(pos.west().north());
    }

    /** The natural tree whose trunk stands on {@code base}: {@link #shape} with a log above the base and a canopy. */
    public static Optional<Tree> trunk(ServerLevel level, BlockPos base) {
        return shape(level, base).filter(Tree::natural);
    }

    private static boolean hasLogAbove(ServerLevel level, BlockPos base) {
        Block log = level.getBlockState(base).getBlock();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getBlockState(base.offset(dx, 1, dz)).is(log)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Collects the logs of the base's kind that form one tree, walking layer by layer upward from the base, sideways
     * and diagonally up. Logs farther than {@link #MAX_SPREAD} blocks sideways (Chebyshev) from the tree's ground layer are
     * not walked, so every trunk column walks the same tree. A log
     * standing on a log of its kind outside the tree belongs to another trunk (a neighbouring tree), so neither it
     * nor anything reached only through it is part of this tree; the layers below are complete before a layer is
     * walked, so one pass decides this. Empty when:
     * <ul>
     *   <li>the base is not a log;</li>
     *   <li>the tree has more than {@link #MAX_TRUNK} logs;</li>
     *   <li>the tree's logs on the base's own layer do not fit in a 2x2, so a trunk is one column or a 2x2 while a log
     *       wall on the ground, or two trunks grown flush against each other, are not;</li>
     *   <li>any tree log touches a log of any kind placed by a player or citizen ({@link PlacedLogs}), or lies inside
     *       a generated structure piece, so builds, village houses and trees a build hangs on are never felled;</li>
     *   <li>a tree log other than the base rests on a block no tree grows over (planks, cobblestone, bricks): a log
     *       build on a foundation, placed before the mod tracked it, touching a tree.</li>
     * </ul>
     * The tree is {@code natural} when a log of its kind stands in the 3x3 directly above {@code base} (acacias lean
     * from their first log) and at least {@link #MIN_LEAVES} distinct natural (non-persistent) leaves touch it.
     */
    public static Optional<Tree> shape(ServerLevel level, BlockPos base) {
        BlockState baseState = level.getBlockState(base);
        if (!baseState.is(BlockTags.LOGS)) {
            return Optional.empty();
        }
        Block log = baseState.getBlock();
        PlacedLogs placed = PlacedLogs.get(level);
        Set<BlockPos> tree = new HashSet<>();
        List<BlockPos> ordered = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> layer = new ArrayDeque<>();
        List<BlockPos> nextLayer = new ArrayList<>();
        BlockPos start = base.immutable();
        layer.add(start);
        seen.add(start);
        int minX = base.getX(), maxX = base.getX(), minZ = base.getZ(), maxZ = base.getZ();
        while (!layer.isEmpty()) {
            while (!layer.isEmpty()) {
                BlockPos pos = layer.poll();
                BlockState below = level.getBlockState(pos.below());
                if (!pos.equals(start) && (below.is(log) && !tree.contains(pos.below())
                        || Math.max(outside(pos.getX(), minX, maxX), outside(pos.getZ(), minZ, maxZ)) > MAX_SPREAD)) {
                    continue;
                }
                if ((!pos.equals(start) && !naturalSupport(below)) || touchesPlacedLog(level, placed, pos) || insideStructure(level, pos)) {
                    return Optional.empty();
                }
                tree.add(pos);
                ordered.add(pos);
                if (tree.size() > MAX_TRUNK) {
                    return Optional.empty();
                }
                if (pos.getY() == base.getY()) {
                    minX = Math.min(minX, pos.getX());
                    maxX = Math.max(maxX, pos.getX());
                    minZ = Math.min(minZ, pos.getZ());
                    maxZ = Math.max(maxZ, pos.getZ());
                    if (maxX - minX > 1 || maxZ - minZ > 1) {
                        return Optional.empty();
                    }
                }
                forEachUpperNeighbour(pos, neighbour -> {
                    if (!seen.contains(neighbour) && level.getBlockState(neighbour).is(log)) {
                        BlockPos next = neighbour.immutable();
                        seen.add(next);
                        (next.getY() == pos.getY() ? layer : nextLayer).add(next);
                    }
                });
            }
            layer.addAll(nextLayer);
            nextLayer.clear();
        }
        Set<BlockPos> leaves = new HashSet<>();
        BlockPos canonical = start;
        for (BlockPos pos : tree) {
            if (pos.getY() == base.getY() && (pos.getX() < canonical.getX() || (pos.getX() == canonical.getX() && pos.getZ() < canonical.getZ()))) {
                canonical = pos;
            }
            forEachUpperNeighbour(pos, next -> {
                BlockState state = level.getBlockState(next);
                if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
                    leaves.add(next.immutable());
                }
            });
        }
        boolean natural = hasLogAbove(level, base) && leaves.size() >= MIN_LEAVES;
        return Optional.of(new Tree(canonical, List.copyOf(ordered), log, natural));
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

    /** How far the coordinate lies outside [min, max]. */
    private static int outside(int value, int min, int max) {
        return Math.max(0, Math.max(min - value, value - max));
    }

    /** True when the log or any log touching it (of any kind, stripped or not) was placed by a player or citizen. */
    private static boolean touchesPlacedLog(ServerLevel level, PlacedLogs placed, BlockPos pos) {
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            if (placed.contains(near) && level.getBlockState(near).is(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a natural tree log can stand on: a log, leaves, the ground a tree grows over, or nothing solid. A log of the
     * tree resting on planks, cobblestone or bricks is part of a build.
     */
    private static boolean naturalSupport(BlockState below) {
        return !below.blocksMotion() || below.is(BlockTags.LOGS) || below.is(BlockTags.LEAVES) || below.is(BlockTags.DIRT)
                || below.is(BlockTags.SAND) || below.is(BlockTags.BASE_STONE_OVERWORLD) || below.is(BlockTags.SNOW)
                || below.is(BlockTags.TERRACOTTA) || below.is(Blocks.GRAVEL) || below.is(Blocks.CLAY) || below.is(Blocks.ICE)
                || below.is(Blocks.PACKED_ICE) || below.is(Blocks.MOSSY_COBBLESTONE) || below.is(Blocks.COCOA) || below.is(Blocks.BEE_NEST);
    }
}
