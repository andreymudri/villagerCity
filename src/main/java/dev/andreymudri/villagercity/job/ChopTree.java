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

/** Breaks each trunk log bottom-up; logs out of reach or already gone are skipped, a failed log does not abort the rest. */
final class ChopTree implements Task {
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
                    || ctx.villager().distanceToSqr(Vec3.atCenterOf(next)) > BreakBlock.REACH * BreakBlock.REACH) {
                continue;
            }
            current = new BreakBlock(next);
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
