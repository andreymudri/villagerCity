package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.VillagerCity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Positions of logs placed in one level (by a player, a citizen, or a modded block placer), saved as data/villagercity_placed_logs.dat.
 * The lumberjack never fells a tree touching one. Placements are recorded after every other listener had its chance
 * to cancel them, and pistons carry an entry along with its log. A tree grown with bone meal is not a placement, and
 * clears any entry it grows over. An entry is forgotten only once its position no longer holds a log: at the end of
 * the tick in which a break was attempted (a cancelled break leaves the log, so the entry stays), and by a sweep
 * every {@link #SWEEP_TICKS} for removals no event reports (fire, explosions, commands).
 */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class PlacedLogs extends SavedData {
    public static final String NAME = "villagercity_placed_logs";
    public static final int SWEEP_TICKS = 200;
    /** A piston moves at most 12 blocks, so no moved block starts farther than this from the piston. */
    private static final int PISTON_REACH = 14;
    private static final SavedData.Factory<PlacedLogs> FACTORY = new SavedData.Factory<>(PlacedLogs::new, PlacedLogs::load, null);

    private final LongSet positions = new LongOpenHashSet();
    /** The same positions grouped by chunk, so a piston only looks at the chunks it can reach. */
    private final Long2ObjectMap<LongSet> byChunk = new Long2ObjectOpenHashMap<>();
    private final LongSet toCheck = new LongOpenHashSet();
    private @Nullable PendingPush pendingPush;
    private int ticksSinceSweep;

    private record PendingPush(BlockPos piston, Direction motion, List<BlockPos> from) {
    }

    public static PlacedLogs get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static PlacedLogs load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacedLogs logs = new PlacedLogs();
        for (long pos : tag.getLongArray("positions")) {
            logs.add(BlockPos.of(pos));
        }
        logs.setDirty(false);
        return logs;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("positions", positions.toLongArray());
        return tag;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        // Any placement counts, also one with no entity (a modded block placer): only growth is not a build.
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PlacedLogs logs = get(level);
        List<BlockSnapshot> snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        if (snapshots.stream().anyMatch(snapshot -> isGrowth(snapshot.getState()))) {
            snapshots.forEach(snapshot -> logs.remove(snapshot.getPos()));
            return;
        }
        for (BlockSnapshot snapshot : snapshots) {
            // For non-player entities NeoForge reports the snapshot's old state as the placed block, so read the level.
            if (level.getBlockState(snapshot.getPos()).is(BlockTags.LOGS)) {
                logs.add(snapshot.getPos());
            } else {
                logs.remove(snapshot.getPos());
            }
        }
    }

    /** A sapling or fungus replaced by the placement: bone meal grew a tree, nobody built it. */
    private static boolean isGrowth(BlockState replaced) {
        return replaced.is(BlockTags.SAPLINGS) || replaced.getBlock() instanceof FungusBlock;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PlacedLogs logs = get(level);
            if (logs.contains(event.getPos())) {
                logs.toCheck.add(event.getPos().asLong());
            }
        }
    }

    /** Remembers the entries a piston might move: logs within reach of the largest pushable structure. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PlacedLogs logs = get(level);
        logs.pendingPush = null;
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos piston = event.getPos();
        for (int cx = SectionPos.blockToSectionCoord(piston.getX() - PISTON_REACH); cx <= SectionPos.blockToSectionCoord(piston.getX() + PISTON_REACH); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(piston.getZ() - PISTON_REACH); cz <= SectionPos.blockToSectionCoord(piston.getZ() + PISTON_REACH); cz++) {
                LongSet inChunk = logs.byChunk.get(ChunkPos.asLong(cx, cz));
                if (inChunk == null) {
                    continue;
                }
                for (long packed : inChunk) {
                    BlockPos pos = BlockPos.of(packed);
                    if (Math.abs(pos.getX() - piston.getX()) <= PISTON_REACH && Math.abs(pos.getY() - piston.getY()) <= PISTON_REACH
                            && Math.abs(pos.getZ() - piston.getZ()) <= PISTON_REACH && level.getBlockState(pos).is(BlockTags.LOGS)) {
                        candidates.add(pos);
                    }
                }
            }
        }
        if (!candidates.isEmpty()) {
            Direction motion = event.getPistonMoveType().isExtend ? event.getDirection() : event.getDirection().getOpposite();
            logs.pendingPush = new PendingPush(event.getPos().immutable(), motion, candidates);
        }
    }

    /**
     * Moves the entry of every noted log the piston actually carried one block along its motion: its position
     * no longer holds a log, and the next one holds a moving block carrying a log along that motion.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPistonPost(PistonEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PlacedLogs logs = get(level);
        PendingPush push = logs.pendingPush;
        logs.pendingPush = null;
        if (push == null || !push.piston().equals(event.getPos())) {
            return;
        }
        List<BlockPos> moved = new ArrayList<>();
        for (BlockPos from : push.from()) {
            BlockPos to = from.relative(push.motion());
            // Another piston firing in the same tick may have moved some other log next to this one: only a log that left
            // its position, carried along this piston's motion, is this one.
            if (!level.getBlockState(from).is(BlockTags.LOGS)
                    && level.getBlockEntity(to) instanceof PistonMovingBlockEntity moving
                    && !moving.isSourcePiston()
                    && moving.getMovementDirection() == push.motion()
                    && moving.getMovedState().is(BlockTags.LOGS)) {
                logs.remove(from);
                moved.add(to);
            }
        }
        moved.forEach(logs::add);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PlacedLogs logs = get(level);
        if (!logs.toCheck.isEmpty()) {
            long[] check = logs.toCheck.toLongArray();
            logs.toCheck.clear();
            for (long pos : check) {
                logs.forgetIfGone(level, BlockPos.of(pos));
            }
        }
        if (++logs.ticksSinceSweep >= SWEEP_TICKS) {
            logs.ticksSinceSweep = 0;
            logs.sweep(level);
        }
    }

    /**
     * A chunk is written to disk when it unloads, but this data only with the level. Saving it too when a chunk
     * holding remembered logs unloads keeps a crash before the next autosave from leaving those logs unremembered.
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PlacedLogs logs = get(level);
        if (logs.isDirty() && logs.byChunk.containsKey(event.getChunk().getPos().toLong())) {
            Path file = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data").resolve(NAME + ".dat");
            logs.save(file.toFile(), level.registryAccess());
        }
    }

    /** Forgets every entry in a loaded chunk whose position no longer holds a log (or a log being pushed). */
    public void sweep(ServerLevel level) {
        for (long pos : positions.toLongArray()) {
            forgetIfGone(level, BlockPos.of(pos));
        }
    }

    private void forgetIfGone(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LOGS) && !state.is(Blocks.MOVING_PISTON)) {
            remove(pos);
        }
    }

    public boolean contains(BlockPos pos) {
        return positions.contains(pos.asLong());
    }

    public void add(BlockPos pos) {
        if (positions.add(pos.asLong())) {
            byChunk.computeIfAbsent(ChunkPos.asLong(pos), chunk -> new LongOpenHashSet()).add(pos.asLong());
            setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (positions.remove(pos.asLong())) {
            long chunk = ChunkPos.asLong(pos);
            LongSet inChunk = byChunk.get(chunk);
            if (inChunk != null && inChunk.remove(pos.asLong()) && inChunk.isEmpty()) {
                byChunk.remove(chunk);
            }
            setDirty();
        }
    }
}
