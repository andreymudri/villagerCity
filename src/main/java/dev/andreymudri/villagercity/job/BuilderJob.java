package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskScheduler;
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
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Takes over a released plot once its retry time has passed, or else claims a new plot once the storehouse holds a
 * full blueprint's materials. In a village with a paver the new plot must open onto a street ({@link PlotPlanner}
 * chooses it), so until the paver has laid one the builder waits for {@code "a street to build on"}. A village with no
 * paver, or whose pavers cannot start a street at the bell at all ({@link StreetWork#nextRun} is empty on an empty
 * graph, counting the columns each paver has given up on), builds where it can without streets rather than waiting
 * forever. A plot needing earthwork is only claimed while the village has a paver,
 * and the builder waits until the paver has prepared it, handing a plot that stays unprepared back after
 * {@link #PREPARATION_WAIT_TICKS} of working time. Then it withdraws what is missing, and clears and places blocks in
 * build order. Progress lives in the world and the plot record, so a reloaded or replacement builder resumes by
 * skipping blocks that are already right. A plot that keeps failing to build is released for a cooldown, and dropped
 * and its spot marked failed after {@link #MAX_ABANDONS} abandons.
 */
public final class BuilderJob implements Job {
    public static final int MAX_CONSECUTIVE_FAILURES = 5;
    public static final int MAX_ABANDONS = 3;
    public static final int RETRY_TICKS = 2400;
    /**
     * How long the builder waits for the paver to prepare its plot before releasing it. Counted in ticks the builder was
     * planned for, never in raw game time: villagers rest half of every day ({@link TaskScheduler#shouldYield}), and the
     * paver rests with them, so a night must not spend the wait.
     */
    public static final int PREPARATION_WAIT_TICKS = 6000;
    /** How many preparation waits one plot gets before the builder leaves it for another spot. */
    public static final int MAX_PREPARATION_WAITS = 2;
    public static final double WORK_REACH = 4.0;
    public static final int STAND_ASIDE_DISTANCE = 2;
    public static final double STAND_ASIDE_REACH = 0.9;
    /** How long builders wait after the village had no buildable plot before searching again. */
    public static final int PLOT_SEARCH_RETRY_TICKS = 200;
    /** How long a plot origin the builder could not path to is skipped. */
    public static final int UNREACHABLE_PLOT_TICKS = 6000;
    /** Pathfinding checks per plot search; later spots wait for the next search, after the checked ones are skipped. */
    public static final int PATH_CHECKS_PER_SEARCH = 3;

    private int consecutiveFailures;
    /** The plot whose preparation the builder is waiting for; a plot never prepared is left for another spot. */
    private @Nullable UUID unpreparedPlot;
    /** Ticks the builder has been planned for while its plot was unprepared, and the game time of the last of them. */
    private long preparationWait;
    private long lastPreparationCheck;
    private int preparationWaits;
    /** Whether the last planned task works at the plot; only those failures count toward abandoning it. */
    private boolean atPlot;
    private @Nullable String waitingFor;

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        waitingFor = null;
        atPlot = false;
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
        if (!plot.prepared()) {
            long now = level.getGameTime();
            if (unpreparedPlot == null || !unpreparedPlot.equals(plot.id())) {
                unpreparedPlot = plot.id();
                preparationWait = 0;
                preparationWaits = 0;
            } else {
                // Only the time the builder was actually planned for counts; the gap across a night counts as one check.
                preparationWait += Math.min(now - lastPreparationCheck, TaskScheduler.IDLE_RETRY_TICKS);
            }
            lastPreparationCheck = now;
            if (preparationWait < PREPARATION_WAIT_TICKS) {
                waitingFor = "the paver to prepare the plot";
                return null;
            }
            // The paver is not getting there: hand the plot back, and after enough waits look elsewhere. A plot nobody
            // prepared has not failed to build, so this never counts as an abandon and never marks the spot failed;
            // the spot is only skipped for a while, as an unreachable one is.
            preparationWait = 0;
            preparationWaits++;
            if (preparationWaits >= MAX_PREPARATION_WAITS) {
                unpreparedPlot = null;
                village.removePlot(plot.id());
                village.markPlotUnreachable(plot.origin(), now + UNREACHABLE_PLOT_TICKS);
            } else {
                village.releasePlot(plot.id(), now + RETRY_TICKS, false);
            }
            VillageRegistry.get(level).setDirty();
            return null;
        }
        unpreparedPlot = null;
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
            giveUp(level, village, plot);
            return null;
        }
        Map<Item, Integer> missing = Inventories.missing(Blueprint.materialsFor(unfinished), inventory);
        if (!missing.isEmpty()) {
            if (storehouse == null || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) || !entity.hasAll(missing)) {
                waitingFor = storehouse == null || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity)
                        ? "a storehouse" : "materials: " + shortfall(level, storehouse, missing);
                return null;
            }
            return TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Withdraw(storehouse, missing));
        }
        atPlot = true;
        BlueprintPlacement next = unfinished.get(0);
        BlockPos pos = plot.origin().offset(next.offset());
        BlockState current = level.getBlockState(pos);
        if (next.state().isAir() || (!current.isAir() && !current.canBeReplaced())) {
            return TaskSequence.of(
                    MoveTo.digOut(pos, WORK_REACH),
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
                MoveTo.digOut(pos, WORK_REACH),
                new PlaceBlock(pos, next.state(), Blueprint.costOf(next.state())));
    }

    /** Releases the plot for {@link #RETRY_TICKS}, or drops it and marks the spot failed once it has been abandoned {@link #MAX_ABANDONS} times. */
    private void giveUp(ServerLevel level, VillageData village, Plot plot) {
        if (plot.abandons() + 1 >= MAX_ABANDONS) {
            village.removePlot(plot.id());
            village.markPlotFailed(plot.origin());
        } else {
            village.releasePlot(plot.id(), level.getGameTime() + RETRY_TICKS, true);
        }
        VillageRegistry.get(level).setDirty();
        consecutiveFailures = 0;
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
        // Failing to reach the storehouse or take materials says nothing about the plot, so it neither counts nor resets.
        if (atPlot) {
            consecutiveFailures = status == Task.Status.FAILED ? consecutiveFailures + 1 : 0;
        }
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
        return TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Deposit(storehouse, BuilderJob::isBuildingMaterial));
    }

    /**
     * False when the builder's pathfinder, from where it stands, ends short of the plot corner (below a cliff, across
     * water), remembering the spot for {@link #UNREACHABLE_PLOT_TICKS}. A spot beyond the villager's follow range cannot
     * be judged, so it counts as reachable. While the villager cannot path at all (mid-air) nothing is claimed yet.
     */
    private static boolean reachable(Villager villager, VillageData village, BlockPos spot, long now) {
        double range = villager.getAttributeValue(Attributes.FOLLOW_RANGE) - 4;
        if (villager.distanceToSqr(Vec3.atBottomCenterOf(spot)) > range * range) {
            return true;
        }
        Path path = villager.getNavigation().createPath(spot, 1);
        if (path == null) {
            return false;
        }
        if (path.canReach()) {
            return true;
        }
        village.markPlotUnreachable(spot, now + UNREACHABLE_PLOT_TICKS);
        return false;
    }

    /** What the storehouse lacks of {@code wanted}, as "item xN" pairs. */
    private static String shortfall(ServerLevel level, BlockPos storehouse, Map<Item, Integer> wanted) {
        Map<Item, Long> have = level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity ? entity.counts() : Map.of();
        return wanted.entrySet().stream()
                .filter(entry -> have.getOrDefault(entry.getKey(), 0L) < entry.getValue())
                .map(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).getPath() + " x" + (entry.getValue() - have.getOrDefault(entry.getKey(), 0L)))
                .collect(Collectors.joining(", "));
    }

    private Optional<Plot> claimPlot(TaskContext ctx) {
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
        if (storehouse == null) {
            waitingFor = "a storehouse";
            return Optional.empty();
        }
        if (!village.plots().isEmpty()) {
            waitingFor = village.plots().stream().anyMatch(Plot::released) ? "an abandoned plot's retry time" : "another builder's plot to finish";
            return Optional.empty();
        }
        Optional<Blueprint> blueprint = Blueprints.load(level, Blueprints.STARTER_HOUSE);
        if (blueprint.isEmpty()) {
            waitingFor = "the starter house blueprint";
            return Optional.empty();
        }
        if (!(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity)) {
            waitingFor = level.isLoaded(storehouse) ? "a storehouse" : "the storehouse to load";
            return Optional.empty();
        }
        if (!entity.hasAll(blueprint.get().requiredMaterials())) {
            waitingFor = "materials: " + shortfall(level, storehouse, blueprint.get().requiredMaterials());
            return Optional.empty();
        }
        // A village with a paver builds only beside its streets, which the paver grows while nothing is buildable. When
        // no street can leave the bell at all, waiting would wedge the village: it builds without streets instead.
        boolean paver = village.jobCount(JobType.PAVER) > 0;
        boolean waitForStreet = waitsForFirstStreet(level, village);
        boolean streetBound = waitForStreet || (paver && !village.streets().isEmpty());
        String noPlot = streetBound ? "a street to build on" : "a buildable plot near the bell";
        if (waitForStreet) {
            waitingFor = noPlot;
            return Optional.empty();
        }
        if (level.getGameTime() < village.nextPlotSearch()) {
            waitingFor = noPlot;
            return Optional.empty();
        }
        long now = level.getGameTime();
        int[] pathChecks = {0};
        Optional<PlotPlanner.Site> site = PlotPlanner.findSite(level, village, blueprint.get().size(), paver,
                spot -> !village.isFailedPlot(spot) && !village.isPlotUnreachable(spot, now)
                        && pathChecks[0]++ < PATH_CHECKS_PER_SEARCH && reachable(ctx.villager(), village, spot, now));
        if (site.isEmpty()) {
            village.setNextPlotSearch(now + PLOT_SEARCH_RETRY_TICKS);
            waitingFor = noPlot;
            return Optional.empty();
        }
        Plot plot = new Plot(UUID.randomUUID(), blueprint.get().id().toString(), site.get().origin(), blueprint.get().size(), ctx.villager().getUUID(),
                0L, 0, site.get().earthwork() == 0);
        village.addPlot(plot);
        VillageRegistry.get(level).setDirty();
        return Optional.of(plot);
    }

    /**
     * Whether a builder in this village claims nothing yet and waits for the paver's first street: the village has a
     * paver, no street, and a street that can still leave the bell ({@link #aPaverCanStartAStreet}). The plot command
     * asks the same question, so it never calls a spot buildable that the builder would not claim.
     */
    public static boolean waitsForFirstStreet(ServerLevel level, VillageData village) {
        return village.jobCount(JobType.PAVER) > 0 && village.streets().isEmpty() && aPaverCanStartAStreet(level, village);
    }

    /**
     * Whether some paver on the roster can start a street on the empty graph: {@link StreetWork#nextRun} with the columns
     * that paver has given up on ({@link PaverJob#refusedColumns}). A paver that is not loaded, or not running a
     * {@link PaverJob}, has given up on nothing, as a fresh job after a reload has not.
     */
    private static boolean aPaverCanStartAStreet(ServerLevel level, VillageData village) {
        for (Map.Entry<UUID, JobType> citizen : village.citizens().entrySet()) {
            if (citizen.getValue() != JobType.PAVER) {
                continue;
            }
            Set<Long> refused = level.getEntity(citizen.getKey()) instanceof Villager villager
                    && villager.getData(CitizenAttachments.RUNTIME).activeJob() instanceof PaverJob paver ? paver.refusedColumns() : Set.of();
            if (StreetWork.nextRun(level, village, refused).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
