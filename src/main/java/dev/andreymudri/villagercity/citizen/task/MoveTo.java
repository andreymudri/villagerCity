package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Walks until within reach of the target block's center. Fails after 200 ticks without getting closer; only
 * ticks the task actually ran count, so time spent paused by the scheduler does not. A move made with
 * {@link #digOut} digs a staircase ({@link DigStep}) once it is stuck and its path does not reach the target, so a
 * citizen trapped in a cave still gets back to the village; it stays in that mode, digging one step after another,
 * until a path reaches the target again or {@link #MAX_DIG_STEPS} steps are dug.
 */
public final class MoveTo implements Task {
    public static final double SPEED = 0.6;
    public static final int STUCK_TICKS = 200;
    public static final int REPATH_TICKS = 20;
    public static final int MAX_DIG_STEPS = 64;

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
        if (distanceSqr <= reach * reach) {
            navigation.stop();
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
                return Status.FAILED;
            }
            digging = true;
            digSteps++;
            step = next.get();
            step.start(ctx);
            return Status.RUNNING;
        }
        villager.getLookControl().setLookAt(center);
        return Status.RUNNING;
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

    @Override
    public void stop(TaskContext ctx) {
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
