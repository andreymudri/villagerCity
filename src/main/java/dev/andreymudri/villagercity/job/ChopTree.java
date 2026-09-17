package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;

/**
 * Fells a tree from its base, top log first, breaking every log including the ones above normal reach, so no trunk
 * top or branch is left floating. Breaking top-down keeps the base standing until last, so a felling cut short (a
 * reload, a released citizen) leaves a tree the finder still recognises. The villager must stand near the base
 * while breaking; when something moved it away (nightfall, panic, trading) it walks back first. Logs already gone
 * are skipped, and a failed log (protected, say) does not abort the rest.
 */
final class ChopTree implements Task {
    /** Covers the tallest sapling tree measured from a villager standing at its base. */
    static final double FELL_REACH = 48.0;
    /** Farthest the villager may stand from the base (horizontally, and vertically) while felling. */
    static final double STAND_DISTANCE = 4.0;

    private final BlockPos base;
    private final Deque<BlockPos> remaining;
    private @Nullable BreakBlock current;
    private @Nullable MoveTo returning;

    ChopTree(BlockPos base, List<BlockPos> logs) {
        this.base = base.immutable();
        this.remaining = new ArrayDeque<>();
        logs.forEach(this.remaining::push);
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (returning != null || !nearBase(ctx)) {
            if (current != null) {
                current.stop(ctx);
                current = null;
            }
            if (returning == null) {
                returning = new MoveTo(base, 2.5);
                returning.start(ctx);
            }
            Status walk = returning.tick(ctx);
            if (walk == Status.RUNNING) {
                return Status.RUNNING;
            }
            returning.stop(ctx);
            returning = null;
            if (walk == Status.FAILED) {
                return Status.FAILED;
            }
        }
        while (current == null) {
            BlockPos next = remaining.peek();
            if (next == null) {
                return Status.SUCCESS;
            }
            remaining.poll();
            if (!ctx.level().getBlockState(next).is(BlockTags.LOGS)
                    || ctx.villager().distanceToSqr(Vec3.atCenterOf(next)) > FELL_REACH * FELL_REACH) {
                continue;
            }
            current = new BreakBlock(next, FELL_REACH);
            current.start(ctx);
        }
        Status status = current.tick(ctx);
        if (status != Status.RUNNING) {
            current.stop(ctx);
            current = null;
        }
        return Status.RUNNING;
    }

    private boolean nearBase(TaskContext ctx) {
        Vec3 feet = ctx.villager().position();
        Vec3 center = Vec3.atBottomCenterOf(base);
        return Math.max(Math.abs(feet.x - center.x), Math.abs(feet.z - center.z)) <= STAND_DISTANCE
                && Math.abs(feet.y - center.y) <= STAND_DISTANCE;
    }

    @Override
    public void stop(TaskContext ctx) {
        if (current != null) {
            current.stop(ctx);
        }
        if (returning != null) {
            returning.stop(ctx);
        }
    }
}
