package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Breaks one block with the held tool at player-like speed, dropping its loot and wearing the tool. */
public final class BreakBlock implements Task {
    public static final double REACH = 6.0;

    private final BlockPos pos;
    private BlockState expected;
    private int required;
    private int progress;

    public BreakBlock(BlockPos pos) {
        this.pos = pos.immutable();
    }

    @Override
    public void start(TaskContext ctx) {
        expected = ctx.level().getBlockState(pos);
        progress = 0;
        required = breakTicks(ctx.level(), pos, expected, ctx.villager().getMainHandItem());
    }

    @Override
    public Status tick(TaskContext ctx) {
        ServerLevel level = ctx.level();
        Villager villager = ctx.villager();
        BlockState current = level.getBlockState(pos);
        if (current.isAir()) {
            return Status.SUCCESS;
        }
        if (current != expected) {
            expected = current;
            progress = 0;
            required = breakTicks(level, pos, current, villager.getMainHandItem());
        }
        if (required < 0 || villager.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return Status.FAILED;
        }
        villager.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        progress++;
        if (progress % 5 == 1) {
            villager.swing(InteractionHand.MAIN_HAND);
        }
        if (progress < required) {
            level.destroyBlockProgress(villager.getId(), pos, Math.min(9, progress * 10 / required));
            return Status.RUNNING;
        }
        level.destroyBlockProgress(villager.getId(), pos, -1);
        ItemStack tool = villager.getMainHandItem();
        Block.dropResources(current, level, pos, level.getBlockEntity(pos), villager, tool);
        level.destroyBlock(pos, false, villager);
        if (!tool.isEmpty() && tool.isDamageableItem()) {
            tool.hurtAndBreak(1, level, villager, item -> {
            });
        }
        return Status.SUCCESS;
    }

    @Override
    public void stop(TaskContext ctx) {
        ctx.level().destroyBlockProgress(ctx.villager().getId(), pos, -1);
    }

    /** Ticks to break the state with the tool; -1 when unbreakable. */
    public static int breakTicks(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return -1;
        }
        if (hardness == 0) {
            return 1;
        }
        float speed = tool.getDestroySpeed(state);
        boolean harvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float perTick = speed / hardness / (harvest ? 30f : 100f);
        return Math.max(1, (int) Math.ceil(1f / perTick));
    }
}
