package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.WorldPermissions;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The paver's path work: finds a route ({@link PathRoute}) from each queued house's door to the bell and lays it one
 * cell at a time, clearing headroom, placing dirt or oak plank support where the ground is uneven or wet, and
 * surfacing dirt-like support with {@link MakePath}. Routes are cached per house origin until the house's path is
 * finished or abandoned. Only natural ground the paver can dig, or vegetation a placement can replace, is ever
 * broken, and only ground still dirt-like when the paver gets there is ever paved; a block that fails either check
 * (a player's chest or a block dropped on the support cell, say) drops the cached route instead, so the next plan()
 * call searches a route around it rather than through it.
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
    /** How far a villager must walk from a door it opened before the sweep below shuts it again. */
    public static final double DOOR_CLOSE_DISTANCE = 6.0;
    /** Shuts a door this mod opened even if its opener never came within {@link #DOOR_CLOSE_DISTANCE} of it (its
     * route changed, say, right after opening it): a safety net, generous enough that it never fires while the
     * opener is still plausibly on its way there for the first time. */
    public static final int DOOR_OPEN_TIMEOUT_TICKS = 400;

    /**
     * Doors opened by any paver, anywhere, closed again once their opener has walked clear (or never came, or is
     * simply gone). Deliberately not a field on a {@link PathWork} instance (as it was before this became a bug in
     * its own right): a job instance only ever runs code while {@link TaskScheduler} keeps ticking it, which it
     * stops doing the moment the villager sleeps or takes up any other yielding activity, is released or discarded,
     * or is simply handed a different job - none of which give a job any chance to clean up after itself (the
     * {@code Job} contract only ever promises {@link #plan} and {@link #onTaskFinished}, called on a schedule this
     * class does not control). Tracked here instead, swept by a level-tick listener that owes nothing to any one
     * villager's own ticking, a door this mod opens is shut again on the first level tick where it no longer needs
     * to be, whether or not the paver, its job, or its task instance still exist to ask for that. Never persisted:
     * a restart loses this exactly like the routes and failure counts above, and a door left open across one is a
     * player's or another mod's problem to notice, not a state this job need survive to fix.
     */
    private static final Map<OpenDoor, DoorState> OPEN_DOORS = new ConcurrentHashMap<>();

    static {
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (OPEN_DOORS.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
                return;
            }
            for (Map.Entry<OpenDoor, DoorState> entry : OPEN_DOORS.entrySet()) {
                if (entry.getKey().dimension().equals(level.dimension())) {
                    closeIfClear(level, entry.getKey(), entry.getValue());
                }
            }
        });
    }

    /** One door this mod opened: where, in which dimension, and which villager opened it (to measure distance by). */
    private record OpenDoor(BlockPos pos, ResourceKey<Level> dimension, UUID villager) {
    }

    /** {@code everNear} once the opener has been seen within {@link #DOOR_CLOSE_DISTANCE}: only from then on does
     * leaving that range again mean "done with it, close it" rather than "still on its way there". */
    private static final class DoorState {
        final long openedAtTick;
        boolean everNear;

        DoorState(long openedAtTick) {
            this.openedAtTick = openedAtTick;
        }
    }

    /**
     * Shuts {@code door} unless its opener is still alive, and either currently within {@link #DOOR_CLOSE_DISTANCE}
     * of it or has not yet had time to walk there for the first time (see {@link #DOOR_OPEN_TIMEOUT_TICKS}): opened
     * right as a fresh route is computed, the door is typically still far from its opener, who has not moved yet,
     * so closing on distance alone the moment it is opened would shut it again before the paver ever reaches it.
     */
    private static void closeIfClear(ServerLevel level, OpenDoor door, DoorState state) {
        Entity owner = level.getEntity(door.villager());
        boolean near = owner instanceof Villager villager && villager.isAlive()
                && villager.distanceToSqr(Vec3.atCenterOf(door.pos())) < DOOR_CLOSE_DISTANCE * DOOR_CLOSE_DISTANCE;
        if (near) {
            state.everNear = true;
            return;
        }
        if (owner instanceof Villager villager && villager.isAlive() && !state.everNear
                && level.getGameTime() - state.openedAtTick < DOOR_OPEN_TIMEOUT_TICKS) {
            return;
        }
        BlockState blockState = level.getBlockState(door.pos());
        if (blockState.getBlock() instanceof DoorBlock doorBlock && doorBlock.isOpen(blockState)) {
            doorBlock.setOpen(owner instanceof Villager villager ? villager : null, level, blockState, door.pos(), false);
        }
        OPEN_DOORS.remove(door);
    }

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
            Optional<List<PathRoute.Cell>> found = doorOutside(ctx, house.get())
                    .flatMap(start -> PathRoute.find(level, village, start, village.center()));
            if (found.isEmpty()) {
                // Not removeQueuedPath without requeuing: whatever blocks the route today (a wall the player has
                // not built yet, a door not placed until worldgen finishes loading, ...) may not tomorrow, and
                // dropping the house here would lose its path to the bell forever, with nothing left to ever queue
                // it again (queuePath is reached only from addHouse, load, and the MAX_CONSECUTIVE_FAILURES retry
                // below). Moving it to the back, the same way a house stuck on a build failure is moved, gives it
                // another try later without letting an unbuildable house starve every other house behind it.
                VillagerCity.LOGGER.info("no route yet from the house at {} to the bell; retrying later", origin);
                village.removeQueuedPath(origin);
                village.queuePath(origin);
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
                // waitingFor is set so the loop in plan() does not mistake this for an already-built cell: without
                // it, it would call addPathCell on the obstructed cell and, on the last cell, leave the queue as if
                // the path had finished.
                dropRoute(headroom);
                waitingFor = "a block at " + headroom.toShortString() + " blocks the path to the bell";
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
            return TaskSequence.of(MoveTo.digOut(surface, BuilderJob.WORK_REACH), new RequireDirt(support), new MakePath(support));
        }
        return null;
    }

    /**
     * Walks to {@code pos}, then re-checks {@link #clearable} right before breaking it: a block placed there since
     * the cell was planned (a player's chest, say) is left alone and the route is dropped instead, just as
     * {@link ChopTree} re-checks a log right before it breaks it rather than trusting a check made when it started.
     */
    private TaskSequence dig(BlockPos pos) {
        return TaskSequence.of(MoveTo.digOut(pos, BuilderJob.WORK_REACH), new RequireClearable(pos), new BreakBlock(pos),
                new PickUpItems(pos, 2.0, PathWork::isBlockItem));
    }

    /** Drops the cached route and its failure count for the house currently being built, logging why. */
    private void dropRoute(BlockPos obstruction) {
        VillagerCity.LOGGER.info("a block at {} blocks the path from the house at {} to the bell; recomputing the route",
                obstruction, current);
        if (current != null) {
            routes.remove(current);
            failures.remove(current);
        }
    }

    /** An instant check, run as a {@link TaskSequence} step right before a {@link BreakBlock} it guards. */
    private final class RequireClearable implements Task {
        private final BlockPos pos;

        RequireClearable(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public Status tick(TaskContext ctx) {
            if (clearable(ctx.level(), pos)) {
                return Status.SUCCESS;
            }
            dropRoute(pos);
            return Status.FAILED;
        }

        @Override
        public String describe(TaskContext ctx) {
            return "checking " + pos.toShortString() + " is still clear to dig";
        }
    }

    /**
     * An instant check, run as a {@link TaskSequence} step right before a {@link MakePath} it guards: a block placed
     * over dirt-like ground since the cell was planned (a player's block, say) is left alone and the route is
     * dropped instead of being paved over, the same way {@link RequireClearable} guards a {@link BreakBlock}.
     */
    private final class RequireDirt implements Task {
        private final BlockPos pos;

        RequireDirt(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public Status tick(TaskContext ctx) {
            if (ctx.level().getBlockState(pos).is(BlockTags.DIRT)) {
                return Status.SUCCESS;
            }
            dropRoute(pos);
            return Status.FAILED;
        }

        @Override
        public String describe(TaskContext ctx) {
            return "checking " + pos.toShortString() + " is still dirt-like ground";
        }
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
        if (!routes.containsKey(origin)) {
            // dropRoute already ran (RequireClearable or RequireDirt found the cell no longer clearable or dirt-like):
            // the route is already gone and will be recomputed, so this failure is not one more of the
            // MAX_CONSECUTIVE_FAILURES kind.
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
     * ({@code InteractWithDoor}) needs, and (unlike a mob that opens doors itself) a shut door near the route can
     * also make vanilla path node evaluation refuse to route anywhere nearby at all, not merely through the door's
     * own tile, so leaving it shut can leave the paver unable to reach cells the route never actually crosses it to
     * get to. Never opens a door the villager could not open itself (an iron door, say): the house's path is skipped
     * instead, with a logged reason, the same way a house with no route to the bell is. When {@link
     * WorldPermissions#mayGrief} refuses, the door is left shut but the start position is still returned: the route
     * itself never runs through the door (only alongside it), so the paver can still lay a path without ever
     * opening it, just possibly a longer one than if it could. A door this method does open is recorded in {@link
     * #OPEN_DOORS} and shut again from there, not from anything this job instance does afterwards.
     */
    private static Optional<BlockPos> doorOutside(TaskContext ctx, BuildingRecord house) {
        ServerLevel level = ctx.level();
        Villager villager = ctx.villager();
        Footprint footprint = house.footprint();
        int doorY = house.origin().getY() + 1;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                BlockPos pos = new BlockPos(x, doorY, z);
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock doorBlock && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                    if (!doorBlock.isOpen(state)) {
                        if (!state.is(BlockTags.WOODEN_DOORS)) {
                            VillagerCity.LOGGER.info("the door at {} is not one the paver could open itself; skipping the path from {} to the bell",
                                    pos, house.origin());
                            return Optional.empty();
                        }
                        if (WorldPermissions.mayGrief(level, villager)) {
                            doorBlock.setOpen(villager, level, state, pos, true);
                            OPEN_DOORS.put(new OpenDoor(pos.immutable(), level.dimension(), villager.getUUID()),
                                    new DoorState(level.getGameTime()));
                        }
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
