package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;

/**
 * Fells a tree bottom-up from its base: every log is broken, including the ones above normal reach, so no trunk
 * top or branch is left floating. Logs already gone are skipped, and a failed log (protected, say) does not abort
 * the rest.
 */
final class ChopTree implements Task {
    /** Covers the tallest sapling tree measured from a villager standing at its base. */
    static final double FELL_REACH = 48.0;

    private final Deque<BlockPos> remaining;
    private @Nullable BreakBlock current;

    ChopTree(List<BlockPos> logs) {
        this.remaining = new ArrayDeque<>(logs);
    }

    @Override
    public Status tick(TaskContext ctx) {
        while (current == null) {
            BlockPos next = remaining.poll();
            if (next == null) {
                return Status.SUCCESS;
            }
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

    @Override
    public void stop(TaskContext ctx) {
        if (current != null) {
            current.stop(ctx);
        }
    }
}
