package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.WorldPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;

/**
 * Converts a dirt-like ground block to a dirt path, as a shovel does. Fails without changing anything when the cell
 * above {@code pos} (where a villager walks) is not air, or when {@link WorldPermissions#mayGrief} refuses.
 */
public final class MakePath implements Task {
    private final BlockPos pos;

    public MakePath(BlockPos pos) {
        this.pos = pos.immutable();
    }

    @Override
    public String describe(TaskContext ctx) {
        return "paving " + pos.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        ServerLevel level = ctx.level();
        Villager villager = ctx.villager();
        BlockState current = level.getBlockState(pos);
        if (current.is(Blocks.DIRT_PATH)) {
            return Status.SUCCESS;
        }
        if (!level.getBlockState(pos.above()).isAir()) {
            return Status.FAILED;
        }
        if (!WorldPermissions.mayGrief(level, villager)) {
            return Status.FAILED;
        }
        BlockState pathState = Blocks.DIRT_PATH.defaultBlockState();
        BlockSnapshot snapshot = WorldPermissions.snapshot(level, pos);
        level.setBlock(pos, pathState, Block.UPDATE_CLIENTS);
        if (WorldPermissions.placementCancelled(villager, snapshot)) {
            snapshot.restore(Block.UPDATE_CLIENTS);
            return Status.FAILED;
        }
        return Status.SUCCESS;
    }
}
