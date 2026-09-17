package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Fells a tree from its base, top log first, breaking every log including the ones above normal reach, so no trunk
 * top or branch is left floating. The base breaks last, so a felling cut short (a reload, a released citizen) leaves a
 * base the village remembers and the finder still recognises. Each log is checked again right before it breaks: one
 * replaced since planning by another kind, or by a log someone placed, is left alone. The villager must stand near the base
 * while breaking; when something moved it away (nightfall, panic, trading) it walks back first. Logs already gone
 * are skipped, and a failed log (protected, say) does not abort the rest.
 */
final class ChopTree implements Task {
    /** Covers the tallest sapling tree measured from a villager standing at its base. */
    static final double FELL_REACH = 48.0;
    /** Farthest the villager may stand from the base (horizontally, and vertically) while felling. */
    static final double STAND_DISTANCE = 4.0;

    private final BlockPos base;
    private final Block logBlock;
    private final Deque<BlockPos> remaining;
    private @Nullable BreakBlock current;
    private @Nullable BlockPos breaking;
    private @Nullable MoveTo returning;

    /** {@code logs} run bottom to top and include {@code base}. */
    ChopTree(BlockPos base, List<BlockPos> logs, Block logBlock) {
        this.base = base.immutable();
        this.logBlock = logBlock;
        this.remaining = new ArrayDeque<>();
        this.remaining.push(this.base);
        logs.stream().filter(pos -> !pos.equals(this.base)).forEach(this.remaining::push);
    }

    @Override
    public String describe(TaskContext ctx) {
        return "felling the tree at " + base.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (returning != null || !nearBase(ctx)) {
            if (current != null) {
                current.stop(ctx);
                current = null;
                remaining.push(breaking);
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
            if (!ctx.level().getBlockState(next).is(logBlock)
                    || PlacedLogs.get(ctx.level()).contains(next)) {
                continue;
            }
            breaking = next;
            current = new BreakBlock(next, FELL_REACH);
            current.start(ctx);
        }
        Status status = current.tick(ctx);
        if (status == Status.SUCCESS && ctx.village().startFelling(base)) {
            // Remembered once a log is down: from here on the tree may lack its canopy until the base goes.
            VillageRegistry.get(ctx.level()).setDirty();
        }
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
