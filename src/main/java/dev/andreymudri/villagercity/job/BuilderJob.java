package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Takes over a released plot once its retry time has passed, or else claims a new plot once the storehouse holds a
 * full blueprint's materials; withdraws what is missing, then clears and places blocks in build order. Progress lives
 * in the world and the plot record, so a reloaded or replacement builder resumes by skipping blocks that are already
 * right. A plot that keeps failing is released for a cooldown, and dropped after {@link #MAX_ABANDONS} abandons.
 */
public final class BuilderJob implements Job {
    public static final int MAX_CONSECUTIVE_FAILURES = 5;
    public static final int MAX_ABANDONS = 3;
    public static final int RETRY_TICKS = 2400;
    public static final double WORK_REACH = 4.0;
    public static final int STAND_ASIDE_DISTANCE = 2;
    public static final double STAND_ASIDE_REACH = 0.9;

    private int consecutiveFailures;

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        SimpleContainer inventory = ctx.villager().getInventory();
        BlockPos storehouse = village.storehousePos();
        UUID self = ctx.villager().getUUID();

        Optional<Plot> mine = village.plotBuiltBy(self);
        if (mine.isEmpty()) {
            if (storehouse != null && Inventories.count(inventory, BuilderJob::isBuildingMaterial) > 0) {
                return deposit(storehouse);
            }
            mine = claimPlot(ctx);
            if (mine.isEmpty()) {
                return null;
            }
        }
        Plot plot = mine.get();
        Optional<Blueprint> blueprint = Blueprints.load(level, ResourceLocation.parse(plot.blueprint()));
        if (blueprint.isEmpty()) {
            village.removePlot(plot.id());
            VillageRegistry.get(level).setDirty();
            return null;
        }
        List<BlueprintPlacement> unfinished = blueprint.get().placements().stream()
                .filter(placement -> !isDone(level, plot, placement))
                .toList();
        if (unfinished.isEmpty()) {
            village.removePlot(plot.id());
            village.addHouse(new BuildingRecord(plot.blueprint(), plot.origin(), plot.size()));
            VillageRegistry.get(level).setDirty();
            consecutiveFailures = 0;
            return storehouse != null && Inventories.count(inventory, BuilderJob::isBuildingMaterial) > 0 ? deposit(storehouse) : null;
        }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            if (plot.abandons() + 1 >= MAX_ABANDONS) {
                village.removePlot(plot.id());
            } else {
                village.releasePlot(plot.id(), level.getGameTime() + RETRY_TICKS, true);
            }
            VillageRegistry.get(level).setDirty();
            consecutiveFailures = 0;
            return null;
        }
        Map<Item, Integer> missing = Inventories.missing(Blueprint.materialsFor(unfinished), inventory);
        if (!missing.isEmpty()) {
            if (storehouse == null || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) || !entity.hasAll(missing)) {
                return null;
            }
            return TaskSequence.of(new MoveTo(storehouse, 2.5), new Withdraw(storehouse, missing));
        }
        BlueprintPlacement next = unfinished.get(0);
        BlockPos pos = plot.origin().offset(next.offset());
        BlockState current = level.getBlockState(pos);
        if (next.state().isAir() || (!current.isAir() && !current.canBeReplaced())) {
            return TaskSequence.of(
                    new MoveTo(pos, WORK_REACH),
                    new BreakBlock(pos),
                    new PickUpItems(pos, 2.0, BuilderJob::isBuildingMaterial));
        }
        if (next.state().blocksMotion() && ctx.villager().getBoundingBox().intersects(new AABB(pos))) {
            Optional<BlockPos> aside = standAside(level, plot, unfinished, pos, ctx.villager());
            if (aside.isPresent()) {
                return new MoveTo(aside.get(), STAND_ASIDE_REACH);
            }
        }
        return TaskSequence.of(
                new MoveTo(pos, WORK_REACH),
                new PlaceBlock(pos, next.state(), Blueprint.costOf(next.state())));
    }

    /**
     * A standable cell two blocks (horizontally) from the target and at most one block above or below it,
     * that is not itself waiting for a solid placement, nearest to the villager first. Without this a builder
     * that ends its walk inside the cell it must fill blocks its own placement until the plot is abandoned.
     */
    static Optional<BlockPos> standAside(ServerLevel level, Plot plot, List<BlueprintPlacement> unfinished, BlockPos target, Villager villager) {
        Set<BlockPos> pendingSolid = new HashSet<>();
        for (BlueprintPlacement placement : unfinished) {
            if (placement.state().blocksMotion()) {
                pendingSolid.add(plot.origin().offset(placement.offset()));
            }
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -STAND_ASIDE_DISTANCE; dx <= STAND_ASIDE_DISTANCE; dx++) {
                for (int dz = -STAND_ASIDE_DISTANCE; dz <= STAND_ASIDE_DISTANCE; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != STAND_ASIDE_DISTANCE) {
                        continue;
                    }
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!isStandable(level, feet) || pendingSolid.contains(feet) || pendingSolid.contains(feet.above())) {
                        continue;
                    }
                    double distance = villager.distanceToSqr(Vec3.atBottomCenterOf(feet));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = feet;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        consecutiveFailures = status == Task.Status.FAILED ? consecutiveFailures + 1 : 0;
    }

    /** A placement is done when the block type matches (door and bed states may legitimately change) or, for air, when the cell is empty. */
    public static boolean isDone(ServerLevel level, Plot plot, BlueprintPlacement placement) {
        BlockState current = level.getBlockState(plot.origin().offset(placement.offset()));
        if (placement.state().isAir()) {
            return current.isAir() || !current.getFluidState().isEmpty();
        }
        return current.is(placement.state().getBlock());
    }

    static boolean isBuildingMaterial(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }

    private static Task deposit(BlockPos storehouse) {
        return TaskSequence.of(new MoveTo(storehouse, 2.5), new Deposit(storehouse, BuilderJob::isBuildingMaterial));
    }

    private static Optional<Plot> claimPlot(TaskContext ctx) {
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        BlockPos storehouse = village.storehousePos();
        Optional<Plot> released = village.plots().stream()
                .filter(plot -> plot.released() && plot.retryAt() <= level.getGameTime())
                .findFirst();
        if (released.isPresent()) {
            UUID self = ctx.villager().getUUID();
            village.assignPlot(released.get().id(), self);
            VillageRegistry.get(level).setDirty();
            return village.plotBuiltBy(self);
        }
        if (storehouse == null || !village.plots().isEmpty()) {
            return Optional.empty();
        }
        Optional<Blueprint> blueprint = Blueprints.load(level, Blueprints.STARTER_HOUSE);
        if (blueprint.isEmpty()
                || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity)
                || !entity.hasAll(blueprint.get().requiredMaterials())) {
            return Optional.empty();
        }
        Optional<BlockPos> origin = PlotPlanner.find(level, village, blueprint.get().size());
        if (origin.isEmpty()) {
            return Optional.empty();
        }
        Plot plot = new Plot(UUID.randomUUID(), blueprint.get().id().toString(), origin.get(), blueprint.get().size(), ctx.villager().getUUID());
        village.addPlot(plot);
        VillageRegistry.get(level).setDirty();
        return Optional.of(plot);
    }
}
