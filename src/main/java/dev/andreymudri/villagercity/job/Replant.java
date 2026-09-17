package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.WorldPermissions;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;

/**
 * Plants a sapling at the stump when possible; never fails — a missing sapling just skips replanting. Skips
 * planting when {@link WorldPermissions#mayGrief} refuses, and undoes the placement, returning the sapling, when
 * EntityPlaceEvent is cancelled (both covered by TreeTargetingTests), so the sapling is consumed only by a
 * placement that stands.
 */
public final class Replant implements Task {
    private final BlockPos pos;
    private final Item sapling;

    public Replant(BlockPos pos, Item sapling) {
        this.pos = pos.immutable();
        this.sapling = sapling;
    }

    @Override
    public String describe(TaskContext ctx) {
        return "replanting at " + pos.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        ServerLevel level = ctx.level();
        if (!(sapling instanceof BlockItem blockItem)
                || !level.getBlockState(pos).isAir()
                || !level.getBlockState(pos.below()).is(BlockTags.DIRT)
                || Inventories.count(ctx.villager().getInventory(), stack -> stack.is(sapling)) == 0
                || ctx.villager().distanceToSqr(Vec3.atCenterOf(pos)) > PlaceBlock.REACH * PlaceBlock.REACH) {
            return Status.SUCCESS;
        }
        if (!WorldPermissions.mayGrief(level, ctx.villager())) {
            return Status.SUCCESS;
        }
        ctx.villager().getInventory().removeItemType(sapling, 1);
        BlockSnapshot snapshot = WorldPermissions.snapshot(level, pos);
        level.setBlock(pos, blockItem.getBlock().defaultBlockState(), Block.UPDATE_ALL);
        if (WorldPermissions.placementCancelled(ctx.villager(), snapshot)) {
            snapshot.restore(Block.UPDATE_CLIENTS);
            ctx.villager().getInventory().addItem(new ItemStack(sapling));
        }
        return Status.SUCCESS;
    }
}
