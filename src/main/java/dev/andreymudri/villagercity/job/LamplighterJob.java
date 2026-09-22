package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Lights dark village ground ({@link DarkSpots}) with torches from the storehouse, nearest spot first, until a full scan
 * finds none; then it rests for {@link #LIT_RESCAN_TICKS} before scanning again. It never places a torch on a laid
 * path: a dark path cell is lit from a neighbouring column instead. Spots and timers are transient, as {@link Job}
 * keeps no saved state.
 */
public final class LamplighterJob implements Job {
    public static final int SCAN_COLUMNS = 256;
    public static final int LIT_RESCAN_TICKS = 1200;
    public static final int MAX_TORCHES = 16;
    public static final int RECHECK_RADIUS = 12;
    /** Ticks the light engine gets to spread a new torch's light before the spots around it are re-tested. */
    public static final int LIGHT_SETTLE_TICKS = 10;
    public static final int AVOID_TICKS = 1200;

    private final DarkSpots spots = new DarkSpots();
    private final Map<BlockPos, Long> avoidUntil = new HashMap<>();
    private long nextScanTick;
    private @Nullable BlockPos spot;
    private @Nullable BlockPos target;
    private @Nullable BlockPos placed;
    private long recheckAt;
    private @Nullable String waitingFor;

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        long now = ctx.gameTime();
        waitingFor = null;
        spot = null;
        target = null;
        avoidUntil.values().removeIf(until -> until <= now);
        if (placed != null) {
            if (now < recheckAt) {
                return null;
            }
            spots.recheckAround(level, placed, RECHECK_RADIUS);
            placed = null;
        }
        if (now < nextScanTick) {
            waitingFor = "the village is lit";
            return null;
        }
        spots.scan(level, village, SCAN_COLUMNS);
        village.setDarkSpotCount(spots.size());
        if (spots.fullPassClean() && spots.size() == 0) {
            waitingFor = "the village is lit";
            nextScanTick = now + LIT_RESCAN_TICKS;
            spots.resetPass();
            return null;
        }
        if (spots.size() == 0) {
            waitingFor = "a dark spot to light";
            return null;
        }
        if (Inventories.count(ctx.villager().getInventory(), stack -> stack.is(Items.TORCH)) == 0) {
            BlockPos storehouse = village.storehousePos();
            if (storehouse == null || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity)) {
                waitingFor = "a storehouse";
                return null;
            }
            long stored = entity.count(Items.TORCH);
            if (stored == 0) {
                waitingFor = "torches";
                return null;
            }
            return TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Withdraw(storehouse, Map.of(Items.TORCH, (int) Math.min(MAX_TORCHES, stored))));
        }
        List<Footprint> occupied = village.occupiedFootprints();
        List<Footprint> noTorch = noTorchFootprints(village);
        BlockPos from = ctx.villager().blockPosition();
        while (true) {
            Optional<BlockPos> nearest = spots.nearest(from, avoidUntil::containsKey);
            if (nearest.isEmpty()) {
                village.setDarkSpotCount(spots.size());
                waitingFor = "a dark spot within reach";
                return null;
            }
            BlockPos candidate = nearest.get();
            spots.recheckAround(level, candidate, 0);
            if (!DarkSpots.isDark(level, occupied, candidate)) {
                continue;
            }
            BlockPos cell = torchCell(level, village, noTorch, candidate);
            if (cell == null) {
                avoidUntil.put(candidate, now + AVOID_TICKS);
                continue;
            }
            spot = candidate;
            target = cell;
            return TaskSequence.of(MoveTo.digOut(cell, 2.5), new PlaceBlock(cell, Blocks.TORCH.defaultBlockState(), Items.TORCH));
        }
    }

    /**
     * Where no torch may stand: everything the village occupies, and each plot grown by {@link PlotRules#MARGIN}, the
     * ground the paver cuts and fills when it prepares the plot and could not clear a torch from.
     */
    private static List<Footprint> noTorchFootprints(VillageData village) {
        List<Footprint> footprints = new ArrayList<>(village.occupiedFootprints());
        village.plots().forEach(plot -> footprints.add(plot.footprint().inflate(PlotRules.MARGIN)));
        return footprints;
    }

    /** The spot itself, or for a spot on a laid path the first neighbouring column that takes a torch; null when none does. */
    private static @Nullable BlockPos torchCell(ServerLevel level, VillageData village, List<Footprint> occupied, BlockPos spot) {
        if (!village.isPathColumn(spot.getX(), spot.getZ())) {
            return takesTorch(level, occupied, spot) ? spot : null;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos column = spot.relative(direction);
            if (village.isPathColumn(column.getX(), column.getZ()) || !level.isLoaded(column)) {
                continue;
            }
            BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            if (Math.abs(feet.getY() - spot.getY()) <= 1 && takesTorch(level, occupied, feet)) {
                return feet;
            }
        }
        return null;
    }

    /** A free cell outside every one of the {@code occupied} footprints where a standing torch holds. */
    private static boolean takesTorch(ServerLevel level, List<Footprint> occupied, BlockPos feet) {
        if (occupied.stream().anyMatch(footprint -> footprint.contains(feet.getX(), feet.getZ()))) {
            return false;
        }
        BlockState current = level.getBlockState(feet);
        return (current.isAir() || current.canBeReplaced())
                && current.getFluidState().isEmpty()
                && Blocks.TORCH.defaultBlockState().canSurvive(level, feet);
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        if (target == null || spot == null) {
            return;
        }
        if (status == Task.Status.SUCCESS && ctx.level().getBlockState(target).is(Blocks.TORCH)) {
            placed = target;
            recheckAt = ctx.gameTime() + LIGHT_SETTLE_TICKS;
        } else {
            avoidUntil.put(spot, ctx.gameTime() + AVOID_TICKS);
        }
        spot = null;
        target = null;
    }

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }
}
