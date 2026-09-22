package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Runs one {@link CraftStep} of kind {@code CRAFT} at the village's crafting table, one batch every
 * {@link #BATCH_TICKS} ticks: the batch's inputs leave the villager's inventory and the recipe's result enters it.
 * Fails when the table is gone or out of reach, when an input is missing, or when the result does not fit.
 */
public final class CraftAtTable implements Task {
    /** How far the villager may stand from the table's center while crafting. */
    public static final double REACH = 3.0;
    /** Ticks between one batch of the step and the next. */
    public static final int BATCH_TICKS = 10;

    private final CraftStep step;
    private int waited;
    private int done;

    public CraftAtTable(CraftStep step) {
        this.step = step;
    }

    @Override
    public void start(TaskContext ctx) {
        waited = 0;
        done = 0;
    }

    @Override
    public String describe(TaskContext ctx) {
        BlockPos table = ctx.village() == null ? null : ctx.village().craftingTablePos();
        String what = "crafting " + BuiltInRegistries.ITEM.getKey(step.output()).getPath();
        return table == null ? what : what + " at " + table.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        BlockPos table = ctx.village().craftingTablePos();
        if (table == null || !ctx.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            return Status.FAILED;
        }
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(table)) > REACH * REACH) {
            return Status.FAILED;
        }
        if (++waited < BATCH_TICKS) {
            return Status.RUNNING;
        }
        waited = 0;
        SimpleContainer inventory = ctx.villager().getInventory();
        ItemStack result = step.recipe().value().getResultItem(ctx.level().registryAccess()).copy();
        if (result.isEmpty() || !inventory.canAddItem(result)) {
            return Status.FAILED;
        }
        Map<Item, Integer> batch = perBatch();
        for (Map.Entry<Item, Integer> input : batch.entrySet()) {
            if (Inventories.count(inventory, stack -> stack.is(input.getKey())) < input.getValue()) {
                return Status.FAILED;
            }
        }
        for (Map.Entry<Item, Integer> input : batch.entrySet()) {
            inventory.removeItemType(input.getKey(), input.getValue());
        }
        if (!inventory.addItem(result).isEmpty()) {
            return Status.FAILED;
        }
        ctx.villager().swing(InteractionHand.MAIN_HAND);
        return ++done >= step.times() ? Status.SUCCESS : Status.RUNNING;
    }

    /** What one run of the recipe consumes: the step's inputs, which cover every run, divided by the number of runs. */
    private Map<Item, Integer> perBatch() {
        Map<Item, Integer> batch = new LinkedHashMap<>();
        int times = Math.max(1, step.times());
        step.inputs().forEach((item, amount) -> batch.put(item, Math.max(1, amount / times)));
        return batch;
    }
}
