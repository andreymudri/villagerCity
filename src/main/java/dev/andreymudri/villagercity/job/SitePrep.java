package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.DigStep;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The paver's site preparation: levels the first unprepared plot. Cuts from the highest diggable or vegetation cell
 * down to the plot's floor, then fills any gap below the floor from the bottom up, reusing dug earth before drawing
 * on the storehouse. A player build the cutting cannot remove, or any other repeated failure, skips the plot for
 * {@link BuilderJob#RETRY_TICKS} after {@link BuilderJob#MAX_CONSECUTIVE_FAILURES} failures in a row.
 */
public final class SitePrep implements Job {
    /** How far above the floor a cell is still considered for cutting. */
    public static final int MAX_RISE = 12;
    /** How far below the floor a gap is still considered for filling. */
    public static final int MAX_DROP = 6;

    private final Map<UUID, Long> skipUntil = new HashMap<>();
    private int consecutiveFailures;
    private @Nullable UUID workingPlot;
    private @Nullable String waitingFor;

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        waitingFor = null;
        workingPlot = null;
        VillageData village = ctx.village();
        ServerLevel level = ctx.level();
        long now = ctx.gameTime();
        Optional<Plot> target = village.plots().stream()
                .filter(plot -> !plot.prepared())
                .filter(plot -> skipUntil.getOrDefault(plot.id(), 0L) <= now)
                .findFirst();
        if (target.isEmpty()) {
            return null;
        }
        Plot plot = target.get();
        workingPlot = plot.id();
        Footprint area = plot.footprint().inflate(PlotRules.MARGIN);
        int floor = plot.origin().getY();
        SimpleContainer inventory = ctx.villager().getInventory();
        BlockPos storehouse = village.storehousePos();

        if (storehouse != null && isFull(inventory)) {
            return deposit(storehouse);
        }

        BlockPos cutCell = findCutCandidate(level, area, floor);
        if (cutCell == null && hasObstruction(level, area, floor)) {
            recordFailure(now, plot.id());
            return null;
        }
        if (cutCell != null) {
            return TaskSequence.of(MoveTo.digOut(cutCell, BuilderJob.WORK_REACH), new BreakBlock(cutCell), new PickUpItems(cutCell, 2.0, FillMaterials::isFill));
        }

        FillTarget fillTarget = findFillCandidate(level, area, floor);
        if (fillTarget == null && hasFillObstruction(level, area, floor)) {
            recordFailure(now, plot.id());
            return null;
        }
        if (fillTarget != null) {
            BlockPos fillCell = fillTarget.pos();
            if (fillTarget.needsClearing()) {
                return TaskSequence.of(MoveTo.digOut(fillCell, BuilderJob.WORK_REACH), new BreakBlock(fillCell));
            }
            Optional<BlockState> fillState = FillMaterials.choose(inventory);
            if (fillState.isPresent()) {
                BlockState state = fillState.get();
                return TaskSequence.of(MoveTo.digOut(fillCell, BuilderJob.WORK_REACH), new PlaceBlock(fillCell, state, state.getBlock().asItem()));
            }
            if (storehouse != null && level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) {
                for (Item item : FillMaterials.PLACEABLE) {
                    long have = entity.count(item);
                    if (have > 0) {
                        int amount = (int) Math.min(32, have);
                        return TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Withdraw(storehouse, Map.of(item, amount)));
                    }
                }
            }
            waitingFor = "dirt or cobblestone to fill the plot";
            return null;
        }

        village.markPlotPrepared(plot.id());
        VillageRegistry.get(level).setDirty();
        consecutiveFailures = 0;
        if (storehouse != null && Inventories.count(inventory, FillMaterials::isFill) > 0) {
            return deposit(storehouse);
        }
        return null;
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        if (workingPlot == null) {
            return;
        }
        if (status == Task.Status.FAILED) {
            recordFailure(ctx.gameTime(), workingPlot);
        } else {
            consecutiveFailures = 0;
        }
    }

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }

    private void recordFailure(long now, UUID plotId) {
        consecutiveFailures++;
        if (consecutiveFailures >= BuilderJob.MAX_CONSECUTIVE_FAILURES) {
            skipUntil.put(plotId, now + BuilderJob.RETRY_TICKS);
            consecutiveFailures = 0;
        }
    }

    private static boolean isFull(SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Task deposit(BlockPos storehouse) {
        return TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Deposit(storehouse, FillMaterials::isFill));
    }

    /** The highest cell in the area, floor up to {@code floor + MAX_RISE}, whose block is diggable or non-air replaceable vegetation. */
    private static @Nullable BlockPos findCutCandidate(ServerLevel level, Footprint area, int floor) {
        for (int y = floor + MAX_RISE; y >= floor; y--) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (DigStep.isDiggable(level, pos) || (state.canBeReplaced() && state.getFluidState().isEmpty())) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /** True when a column's highest solid, non-leaf block above the floor is not one the cutting pass would take. */
    private static boolean hasObstruction(ServerLevel level, Footprint area, int floor) {
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                if (columnTop(level, x, z, floor) > floor) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The highest y in {@code [floor, floor + MAX_RISE]} that blocks motion and is not leaves; {@code floor - 1} when none. */
    private static int columnTop(ServerLevel level, int x, int z, int floor) {
        for (int y = floor + MAX_RISE; y >= floor; y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES)) {
                return y;
            }
        }
        return floor - 1;
    }

    /** A cell to fill: {@code needsClearing} when it holds natural vegetation the paver must break before placing. */
    private record FillTarget(BlockPos pos, boolean needsClearing) {
    }

    /**
     * The lowest cell below the floor, down to each column's ground, that is either already open (air, fluid or
     * replaceable vegetation like grass) or covered by natural vegetation the cutting pass would also take (flowers,
     * saplings). A cell with no collision shape that is neither (a torch, a player's block) is never a candidate here.
     */
    private static @Nullable FillTarget findFillCandidate(ServerLevel level, Footprint area, int floor) {
        for (int y = floor - MAX_DROP; y < floor; y++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    if (y < columnGround(level, x, z, floor)) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.getCollisionShape(level, pos).isEmpty()) {
                        continue;
                    }
                    if (state.is(BlockTags.REPLACEABLE)) {
                        return new FillTarget(pos, false);
                    }
                    if (isClearableVegetation(state)) {
                        return new FillTarget(pos, true);
                    }
                }
            }
        }
        return null;
    }

    /** True when some cell in the fillable range has no collision shape but is neither open nor clearable vegetation. */
    private static boolean hasFillObstruction(ServerLevel level, Footprint area, int floor) {
        for (int y = floor - MAX_DROP; y < floor; y++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    if (y < columnGround(level, x, z, floor)) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getCollisionShape(level, pos).isEmpty() && !state.is(BlockTags.REPLACEABLE) && !isClearableVegetation(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Natural growth a paver clears like the cutting pass clears a mound: flowers and saplings, never player builds. */
    private static boolean isClearableVegetation(BlockState state) {
        return state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS);
    }

    /** The first free y below the floor: one above the first solid block found searching down, capped at {@code floor - MAX_DROP}. */
    private static int columnGround(ServerLevel level, int x, int z, int floor) {
        for (int y = floor - 1; y >= floor - MAX_DROP; y--) {
            if (level.getBlockState(new BlockPos(x, y, z)).blocksMotion()) {
                return y + 1;
            }
        }
        return floor - MAX_DROP;
    }
}
