package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Moves matching inventory stacks into the storehouse; whatever does not fit stays in the inventory. */
public final class Deposit implements Task {
    public static final double REACH = 3.0;

    private final BlockPos storehouse;
    private final Predicate<ItemStack> filter;

    public Deposit(BlockPos storehouse, Predicate<ItemStack> filter) {
        this.storehouse = storehouse.immutable();
        this.filter = filter;
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(storehouse)) > REACH * REACH) {
            return Status.FAILED;
        }
        if (!(ctx.level().getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity)) {
            return Status.FAILED;
        }
        SimpleContainer inventory = ctx.villager().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && filter.test(stack)) {
                inventory.setItem(i, entity.insertFromCitizen(stack));
            }
        }
        return Status.SUCCESS;
    }
}
