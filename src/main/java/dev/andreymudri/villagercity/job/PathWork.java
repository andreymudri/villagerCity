package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.DigStep;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The paver's path work: finds a route ({@link PathRoute}) from each queued house's door to the bell and lays it one
 * cell at a time, clearing headroom, placing dirt or oak plank support where the ground is uneven or wet, and
 * surfacing dirt-like support with {@link MakePath}. Routes are cached per house origin until the house's path is
 * finished or abandoned. Only natural ground the paver can dig, or vegetation a placement can replace, is ever
 * broken; a block that is neither (a player's chest, say) drops the cached route instead, so the next plan() call
 * searches a route around it rather than through it.
 */
public final class PathWork implements Job {
    /** Consecutive failed build tasks on the same house's path before it is moved to the end of the queue. */
    public static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** Most units of one support item withdrawn from the storehouse at a time. */
    public static final int WITHDRAW_BATCH = 32;
    /** How far down a cut looks for the block it must break through to reach solid ground. */
    public static final int MAX_CUT_DEPTH = 6;
    /** How far (horizontally) a villager steps aside when it is standing where a support block must go. */
    public static final int STAND_ASIDE_DISTANCE = 2;
    public static final double STAND_ASIDE_REACH = 0.9;

    private final Map<BlockPos, List<PathRoute.Cell>> routes = new HashMap<>();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private @Nullable BlockPos current;
    private boolean building;
    private @Nullable String waitingFor;

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        waitingFor = null;
        building = false;
        current = null;
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        List<BlockPos> queue = village.pathQueue();
        if (queue.isEmpty()) {
            return null;
        }
        BlockPos origin = queue.get(0);
        Optional<BuildingRecord> house = village.houses().stream().filter(h -> h.origin().equals(origin)).findFirst();
        if (house.isEmpty()) {
            village.removeQueuedPath(origin);
            routes.remove(origin);
            failures.remove(origin);
            return null;
        }
        List<PathRoute.Cell> route = routes.get(origin);
        if (route == null) {
            Optional<List<PathRoute.Cell>> found = doorOutside(level, ctx.villager(), house.get())
                    .flatMap(start -> PathRoute.find(level, village, start, village.center()));
            if (found.isEmpty()) {
                VillagerCity.LOGGER.info("no route from the house at {} to the bell", origin);
                village.removeQueuedPath(origin);
                failures.remove(origin);
                return null;
            }
            route = found.get();
            routes.put(origin, route);
        }
        current = origin;

        for (PathRoute.Cell cell : route) {
            Task task = planCell(ctx, village, cell);
            if (task != null) {
                return task;
            }
            if (waitingFor != null) {
                return null;
            }
            village.addPathCell(cell.surface());
            VillageRegistry.get(level).setDirty();
        }
        village.removeQueuedPath(origin);
        routes.remove(origin);
        failures.remove(origin);
        current = null;
        return null;
    }

    /** The next task to finish this cell, or null when it is already built (or, with {@link #waitingFor} set, blocked on materials). */
    private @Nullable Task planCell(TaskContext ctx, VillageData village, PathRoute.Cell cell) {
        ServerLevel level = ctx.level();
        BlockPos surface = cell.surface();
        BlockPos support = surface.below();

        BlockPos headroom = firstOccupied(level, surface);
        if (headroom != null) {
            if (!clearable(level, headroom)) {
                // A block that is neither natural ground nor replaceable vegetation (a player's chest, for example)
                // must never be broken. The cached route may have been planned before it appeared, or may have
                // started right on it, so drop it and let the next plan() recompute a route around the obstruction.
                VillagerCity.LOGGER.info("a block at {} blocks the path from the house at {} to the bell; recomputing the route",
                        headroom, current);
                if (current != null) {
                    routes.remove(current);
                    failures.remove(current);
                }
                return null;
            }
            building = true;
            return dig(headroom);
        }

        if (cell.kind() == PathRoute.Kind.RAISED || cell.kind() == PathRoute.Kind.BRIDGE) {
            BlockState supportState = level.getBlockState(support);
            if (supportState.isAir() || supportState.canBeReplaced()) {
                if (ctx.villager().getBoundingBox().intersects(new AABB(support))) {
                    Optional<BlockPos> aside = standAside(level, support, ctx.villager());
                    if (aside.isPresent()) {
                        building = true;
                        return new MoveTo(aside.get(), STAND_ASIDE_REACH);
                    }
                }
                Item item = cell.kind() == PathRoute.Kind.BRIDGE ? Items.OAK_PLANKS : Items.DIRT;
                BlockState placed = cell.kind() == PathRoute.Kind.BRIDGE ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                SimpleContainer inventory = ctx.villager().getInventory();
                if (Inventories.count(inventory, stack -> stack.getItem() == item) <= 0) {
                    return withdraw(ctx, village, item);
                }
                building = true;
                return TaskSequence.of(MoveTo.digOut(surface, BuilderJob.WORK_REACH), new PlaceBlock(support, placed, item));
            }
        } else if (cell.kind() == PathRoute.Kind.CUT) {
            BlockPos cutTarget = cutTarget(level, support);
            if (cutTarget != null) {
                building = true;
                return dig(cutTarget);
            }
        }

        if (level.getBlockState(support).is(BlockTags.DIRT)) {
            building = true;
            return TaskSequence.of(MoveTo.digOut(surface, BuilderJob.WORK_REACH), new MakePath(support));
        }
        return null;
    }

    private static TaskSequence dig(BlockPos pos) {
        return TaskSequence.of(MoveTo.digOut(pos, BuilderJob.WORK_REACH), new BreakBlock(pos),
                new PickUpItems(pos, 2.0, PathWork::isBlockItem));
    }

    /** Withdraws up to {@link #WITHDRAW_BATCH} of the wanted item, or sets {@link #waitingFor} when the storehouse has none either. */
    private @Nullable Task withdraw(TaskContext ctx, VillageData village, Item item) {
        ServerLevel level = ctx.level();
        BlockPos storehouse = village.storehousePos();
        long have = storehouse != null && level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity ? entity.count(item) : 0;
        if (have <= 0) {
            waitingFor = "dirt or oak planks for the path";
            return null;
        }
        int amount = (int) Math.min(WITHDRAW_BATCH, have);
        return TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Withdraw(storehouse, Map.of(item, amount)));
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        if (!building || current == null) {
            return;
        }
        BlockPos origin = current;
        if (status != Task.Status.FAILED) {
            failures.remove(origin);
            return;
        }
        if (failures.merge(origin, 1, Integer::sum) >= MAX_CONSECUTIVE_FAILURES) {
            VillageData village = ctx.village();
            if (village != null) {
                village.removeQueuedPath(origin);
                village.queuePath(origin);
            }
            routes.remove(origin);
            failures.remove(origin);
        }
    }

    /**
     * The cell just outside the house's own footprint, next to its lower door half at {@code origin.y + 1}. Opens
     * the door if it is shut: our task-based navigation drives {@link net.minecraft.world.entity.ai.navigation.PathNavigation}
     * directly, without the brain memory ({@code MemoryModuleType.PATH}) that vanilla's own door-opening behaviour
     * ({@code InteractWithDoor}) needs, so a shut door in the route is never opened on its own and blocks the paver.
     */
    private static Optional<BlockPos> doorOutside(ServerLevel level, Villager villager, BuildingRecord house) {
        Footprint footprint = house.footprint();
        int doorY = house.origin().getY() + 1;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                BlockPos pos = new BlockPos(x, doorY, z);
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock doorBlock && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                    if (!doorBlock.isOpen(state)) {
                        doorBlock.setOpen(villager, level, state, pos, true);
                    }
                    Direction facing = state.getValue(DoorBlock.FACING);
                    BlockPos towardFacing = pos.relative(facing);
                    BlockPos awayFromFacing = pos.relative(facing.getOpposite());
                    return Optional.of(footprint.contains(towardFacing.getX(), towardFacing.getZ()) ? awayFromFacing : towardFacing);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A standable cell within {@link #STAND_ASIDE_DISTANCE} blocks (horizontally) of {@code target}, nearest to the
     * villager first. Without this, a villager whose approach already left it standing on the spot a support block
     * must fill would be embedded by its own placement.
     */
    private static Optional<BlockPos> standAside(ServerLevel level, BlockPos target, Villager villager) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -STAND_ASIDE_DISTANCE; dx <= STAND_ASIDE_DISTANCE; dx++) {
                for (int dz = -STAND_ASIDE_DISTANCE; dz <= STAND_ASIDE_DISTANCE; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != STAND_ASIDE_DISTANCE) {
                        continue;
                    }
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!isStandable(level, feet)) {
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

    /** The first of {@code surface} and {@code surface.above()} that is not air. */
    private static @Nullable BlockPos firstOccupied(ServerLevel level, BlockPos surface) {
        for (BlockPos pos : new BlockPos[] {surface, surface.above()}) {
            if (!level.getBlockState(pos).isAir()) {
                return pos;
            }
        }
        return null;
    }

    /** Whether the paver may clear {@code pos}: natural ground it can dig, or vegetation a placement can replace. */
    private static boolean clearable(ServerLevel level, BlockPos pos) {
        return DigStep.isDiggable(level, pos) || level.getBlockState(pos).canBeReplaced();
    }

    /** The nearest block below {@code support} the paver must break through to reach solid ground, within {@link #MAX_CUT_DEPTH}. */
    private static @Nullable BlockPos cutTarget(ServerLevel level, BlockPos support) {
        BlockPos pos = support;
        for (int i = 0; i < MAX_CUT_DEPTH; i++) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.canBeReplaced()) {
                return null;
            }
            if (!state.isAir()) {
                return pos;
            }
            pos = pos.below();
        }
        return null;
    }

    static boolean isBlockItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }
}
