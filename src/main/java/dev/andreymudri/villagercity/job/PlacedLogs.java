package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.VillagerCity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Positions of logs an entity (a player or a citizen) placed in one level, saved as data/villagercity_placed_logs.dat.
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
    private final LongSet toCheck = new LongOpenHashSet();
    private @Nullable PendingPush pendingPush;

    private record PendingPush(BlockPos piston, Direction motion, List<BlockPos> from) {
    }

    public static PlacedLogs get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static PlacedLogs load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacedLogs logs = new PlacedLogs();
        for (long pos : tag.getLongArray("positions")) {
            logs.positions.add(pos);
        }
        return logs;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("positions", positions.toLongArray());
        return tag;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() == null || !(event.getLevel() instanceof ServerLevel level)) {
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
        for (long packed : logs.positions) {
            BlockPos pos = BlockPos.of(packed);
            if (Math.max(Math.max(Math.abs(pos.getX() - event.getPos().getX()), Math.abs(pos.getY() - event.getPos().getY())), Math.abs(pos.getZ() - event.getPos().getZ())) <= PISTON_REACH && level.getBlockState(pos).is(BlockTags.LOGS)) {
                candidates.add(pos);
            }
        }
        if (!candidates.isEmpty()) {
            Direction motion = event.getPistonMoveType().isExtend ? event.getDirection() : event.getDirection().getOpposite();
            logs.pendingPush = new PendingPush(event.getPos().immutable(), motion, candidates);
        }
    }

    /** Moves the entry of every remembered log the piston actually carried one block along its motion. */
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
        if (level.getGameTime() % SWEEP_TICKS == 0) {
            logs.sweep(level);
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
            setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (positions.remove(pos.asLong())) {
            setDirty();
        }
    }
}
