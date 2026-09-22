package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Walks until within reach of the target block's center. Fails after 200 ticks without getting closer; only
 * ticks the task actually ran count, so time spent paused by the scheduler does not. A move made with
 * {@link #digOut} digs a staircase ({@link DigStep}) once it is stuck and its path does not reach the target, so a
 * citizen trapped in a cave still gets back to the village; it stays in that mode, digging one step after another,
 * until a path reaches the target again or {@link #MAX_DIG_STEPS} steps are dug.
 *
 * <p>The path may lead through a closed wooden door, since a villager's navigation plans through them, but vanilla's
 * door behaviour only opens doors on the path in the brain's {@code PATH} memory, which this task never sets. So the
 * task opens a closed wooden door itself once the citizen is within {@link #DOOR_REACH} of it on its path, and closes
 * it again once the citizen is farther than that from it, when the task ends, and whenever the citizen yields to
 * vanilla ({@link #closeDoorsOpenedBy}); a door met again on the path afterwards is opened again. It never opens any
 * other door. A door whose chunk is not loaded is left as it is.
 */
public final class MoveTo implements Task {
    public static final double SPEED = 0.6;
    public static final int STUCK_TICKS = 200;
    public static final int REPATH_TICKS = 20;
    public static final int MAX_DIG_STEPS = 64;
    /** How close, from the citizen's feet to the door's centre, a door on the path is opened and kept open. */
    public static final double DOOR_REACH = 2.0;

    private final BlockPos target;
    private final double reach;
    private final boolean digOut;
    private boolean pathReaches;
    private boolean digging;
    private int digSteps;
    private @Nullable DigStep step;
    private double bestDistanceSqr;
    private int ticksRun;
    private int lastProgressTick;
    private int lastPathTick;
    /** Lower halves of the wooden doors this task opened and has not closed yet. */
    private final Set<BlockPos> openedDoors = new LinkedHashSet<>();
    /** The moves with a door open, by villager; weak, so a villager that is gone takes its entry with it. */
    private static final Map<Villager, Set<MoveTo>> OPENERS = new WeakHashMap<>();
    /** The villager this move opened a door for, while it has one open. */
    private @Nullable Villager opener;

    public MoveTo(BlockPos target, double reach) {
        this(target, reach, false);
    }

    private MoveTo(BlockPos target, double reach, boolean digOut) {
        this.target = target.immutable();
        this.reach = reach;
        this.digOut = digOut;
    }

    /** A move to a village place (storehouse, plot) that digs its way out when the citizen is trapped. */
    public static MoveTo digOut(BlockPos target, double reach) {
        return new MoveTo(target, reach, true);
    }

    /** Manhattan accuracy whose worst case (all of it vertical, plus the +0.5 to the block center) stays within reach. */
    static int pathAccuracy(double reach) {
        return Math.max(0, (int) Math.floor(reach - 1.0));
    }

    @Override
    public void start(TaskContext ctx) {
        bestDistanceSqr = Double.MAX_VALUE;
        ticksRun = 0;
        lastProgressTick = 0;
        lastPathTick = -REPATH_TICKS;
        pathReaches = false;
        digging = false;
        digSteps = 0;
        step = null;
    }

    @Override
    public Status tick(TaskContext ctx) {
        ticksRun++;
        Villager villager = ctx.villager();
        PathNavigation navigation = villager.getNavigation();
        Vec3 center = Vec3.atCenterOf(target);
        double distanceSqr = villager.distanceToSqr(center);
        closePassedDoors(ctx.level(), villager);
        if (distanceSqr <= reach * reach) {
            navigation.stop();
            closeDoors(ctx.level());
            return Status.SUCCESS;
        }
        int now = ticksRun;
        if (step != null) {
            Status stepped = step.tick(ctx);
            if (stepped == Status.RUNNING) {
                return Status.RUNNING;
            }
            step.stop(ctx);
            step = null;
            if (stepped == Status.FAILED) {
                return Status.FAILED;
            }
            bestDistanceSqr = distanceSqr;
            lastProgressTick = now;
            lastPathTick = now - REPATH_TICKS;
        }
        if (distanceSqr < bestDistanceSqr - 0.25) {
            bestDistanceSqr = distanceSqr;
            lastProgressTick = now;
        } else if (now - lastProgressTick > STUCK_TICKS && !(digOut && !pathReaches)) {
            navigation.stop();
            closeDoors(ctx.level());
            return Status.FAILED;
        }
        boolean ours = target.equals(navigation.getTargetPos());
        if (digging || ((navigation.isDone() || !ours) && now - lastPathTick >= (ours ? REPATH_TICKS : 0))) {
            Path path = createPath(villager, navigation);
            pathReaches = path != null && path.canReach();
            if (pathReaches) {
                digging = false;
            }
            if (path != null && !digging) {
                navigation.moveTo(path, SPEED);
            }
            lastPathTick = now;
        }
        if (digOut && !pathReaches && (digging || now - lastProgressTick > STUCK_TICKS)) {
            navigation.stop();
            Optional<DigStep> next = digSteps < MAX_DIG_STEPS ? DigStep.toward(ctx.level(), villager, target) : Optional.empty();
            if (next.isEmpty()) {
                closeDoors(ctx.level());
                return Status.FAILED;
            }
            digging = true;
            digSteps++;
            step = next.get();
            step.start(ctx);
            return Status.RUNNING;
        }
        openDoorsOnPath(ctx.level(), villager, navigation.getPath());
        villager.getLookControl().setLookAt(center);
        return Status.RUNNING;
    }

    /** Opens a closed wooden door at the path's previous or next node when the citizen is within {@link #DOOR_REACH}. */
    private void openDoorsOnPath(ServerLevel level, Villager villager, @Nullable Path path) {
        if (path == null || path.notStarted() || path.isDone()) {
            return;
        }
        for (BlockPos node : new BlockPos[] {path.getPreviousNode().asBlockPos(), path.getNextNode().asBlockPos()}) {
            BlockState state = level.getBlockState(node);
            if (!(state.getBlock() instanceof DoorBlock door) || !state.is(BlockTags.WOODEN_DOORS) || !withinDoorReach(villager, node)) {
                continue;
            }
            BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    ? node.immutable() : node.below();
            if (!door.isOpen(state)) {
                door.setOpen(villager, level, level.getBlockState(lower), lower, true);
                openedDoors.add(lower);
                OPENERS.computeIfAbsent(villager, key -> new LinkedHashSet<>()).add(this);
                opener = villager;
            }
        }
    }

    /** Closes the doors this task opened that the citizen is now farther than {@link #DOOR_REACH} from. */
    private void closePassedDoors(ServerLevel level, Villager villager) {
        for (Iterator<BlockPos> it = openedDoors.iterator(); it.hasNext(); ) {
            BlockPos door = it.next();
            if (!withinDoorReach(villager, door)) {
                close(level, door);
                it.remove();
            }
        }
        if (openedDoors.isEmpty()) {
            forgetOpener();
        }
    }

    /** Closes every door this task opened. */
    private void closeDoors(ServerLevel level) {
        openedDoors.forEach(door -> close(level, door));
        openedDoors.clear();
        forgetOpener();
    }

    /** Drops this move from {@link #OPENERS}: it has no door open any more. */
    private void forgetOpener() {
        if (opener == null) {
            return;
        }
        Set<MoveTo> moves = OPENERS.get(opener);
        if (moves != null && moves.remove(this) && moves.isEmpty()) {
            OPENERS.remove(opener);
        }
        opener = null;
    }

    /** Closes {@code pos} if it is still an open wooden door; a door since broken or replaced is left alone. */
    private static void close(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door && state.is(BlockTags.WOODEN_DOORS) && door.isOpen(state)) {
            door.setOpen(null, level, state, pos, false);
        }
    }

    private static boolean withinDoorReach(Villager villager, BlockPos door) {
        return villager.position().distanceToSqr(Vec3.atBottomCenterOf(door)) <= DOOR_REACH * DOOR_REACH;
    }

    /**
     * Ground navigation's {@code createPath(BlockPos, int)} moves a solid target up to the first free block above it,
     * so a path to a tree base leads to the top of the trunk and a path to the storehouse onto its roof. For a solid
     * target the set overload is used instead, which paths to a cell within reach of the block itself.
     */
    private @Nullable Path createPath(Villager villager, PathNavigation navigation) {
        int accuracy = pathAccuracy(reach);
        if (villager.level().getBlockState(target).isSolid()) {
            return navigation.createPath(Set.of(target), accuracy);
        }
        return navigation.createPath(target, accuracy);
    }

    /**
     * Closes every door a move of {@code villager} opened and has not closed yet. The scheduler calls this while the
     * citizen yields to vanilla: the move stays current but is not ticked, however deep inside another task it runs.
     */
    public static void closeDoorsOpenedBy(ServerLevel level, Villager villager) {
        Set<MoveTo> moves = OPENERS.remove(villager);
        if (moves != null) {
            moves.forEach(move -> move.closeDoors(level));
        }
    }

    @Override
    public void stop(TaskContext ctx) {
        closeDoors(ctx.level());
        if (step != null) {
            step.stop(ctx);
            step = null;
        }
    }

    @Override
    public String describe(TaskContext ctx) {
        String what = (step != null || digging ? "digging out toward " : "walking to ") + target.toShortString();
        int distance = (int) Math.round(Math.sqrt(ctx.villager().distanceToSqr(Vec3.atCenterOf(target))));
        return what + " (" + distance + " blocks" + (ticksRun - lastProgressTick > REPATH_TICKS ? ", stuck" : "") + ")";
    }

    public BlockPos target() {
        return target;
    }

    public boolean isDigging() {
        return digging;
    }
}
