package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Fells the nearest natural tree, collects the drops, replants, and hauls logs to the storehouse. A tree whose
 * felling failed or left its trunk standing is skipped for {@link #AVOID_TICKS}; the avoid list is in-memory only,
 * as {@link Job} keeps no state that must survive a save.
 */
public final class LumberjackJob implements Job {
    public static final int DEPOSIT_THRESHOLD = 16;
    public static final double DROP_RADIUS = 6.0;
    public static final int AVOID_TICKS = 2400;

    private final Map<BlockPos, Long> avoidUntil = new HashMap<>();
    private @Nullable BlockPos target;

    static final Map<Block, Item> SAPLINGS = Map.of(
            Blocks.OAK_LOG, Items.OAK_SAPLING,
            Blocks.SPRUCE_LOG, Items.SPRUCE_SAPLING,
            Blocks.BIRCH_LOG, Items.BIRCH_SAPLING,
            Blocks.JUNGLE_LOG, Items.JUNGLE_SAPLING,
            Blocks.ACACIA_LOG, Items.ACACIA_SAPLING,
            Blocks.DARK_OAK_LOG, Items.DARK_OAK_SAPLING,
            Blocks.CHERRY_LOG, Items.CHERRY_SAPLING,
            Blocks.MANGROVE_LOG, Items.MANGROVE_PROPAGULE);

    static boolean isLog(ItemStack stack) {
        return stack.is(ItemTags.LOGS);
    }

    static boolean isHaul(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    static boolean isDeposit(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        SimpleContainer inventory = ctx.villager().getInventory();
        BlockPos storehouse = ctx.village().storehousePos();
        int logs = Inventories.count(inventory, LumberjackJob::isLog);
        long now = ctx.gameTime();
        avoidUntil.values().removeIf(until -> until <= now);
        target = null;
        for (BlockPos base : ctx.village().felling()) {
            if (ctx.level().isLoaded(base) && !ctx.level().getBlockState(base).is(BlockTags.LOGS)) {
                forgetFelling(ctx, base);
            }
        }
        Optional<TreeFinder.Tree> tree = logs >= DEPOSIT_THRESHOLD
                ? Optional.empty()
                : TreeFinder.findNearest(ctx.level(), ctx.villager().blockPosition(), ctx.village(), base -> avoidUntil.containsKey(base));
        if (logs >= DEPOSIT_THRESHOLD || (tree.isEmpty() && Inventories.count(inventory, LumberjackJob::isDeposit) > 0)) {
            return storehouse == null ? null : TaskSequence.of(new MoveTo(storehouse, 2.5), new Deposit(storehouse, LumberjackJob::isDeposit));
        }
        if (tree.isEmpty()) {
            return null;
        }
        TreeFinder.Tree found = tree.get();
        target = found.base();
        return TaskSequence.of(
                new MoveTo(found.base(), 2.5),
                new ChopTree(found.base(), found.logs(), found.logBlock()),
                new PickUpItems(found.base(), DROP_RADIUS, LumberjackJob::isHaul),
                new MoveTo(found.base(), 2.5),
                new Replant(found.base(), SAPLINGS.getOrDefault(found.logBlock(), Items.AIR)));
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        if (target != null && (status == Task.Status.FAILED || TreeFinder.shape(ctx.level(), target).isPresent())) {
            avoidUntil.put(target, ctx.gameTime() + AVOID_TICKS);
        }
        if (target != null && !ctx.level().getBlockState(target).is(BlockTags.LOGS)) {
            forgetFelling(ctx, target);
        }
        target = null;
    }

    /** Drops a remembered felling whose base log is gone; the felling breaks the base last, so the tree is down. */
    private static void forgetFelling(TaskContext ctx, BlockPos base) {
        if (ctx.village().stopFelling(base)) {
            VillageRegistry.get(ctx.level()).setDirty();
        }
    }
}
