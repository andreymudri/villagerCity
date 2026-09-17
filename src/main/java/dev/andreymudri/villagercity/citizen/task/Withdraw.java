package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Takes the wanted amounts from the storehouse; fails if the storehouse is short or the inventory overflows. */
public final class Withdraw implements Task {
    private final BlockPos storehouse;
    private final Map<Item, Integer> wanted;

    public Withdraw(BlockPos storehouse, Map<Item, Integer> wanted) {
        this.storehouse = storehouse.immutable();
        this.wanted = new LinkedHashMap<>(wanted);
    }

    @Override
    public String describe(TaskContext ctx) {
        return "taking materials from the storehouse at " + storehouse.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(storehouse)) > Deposit.REACH * Deposit.REACH) {
            return Status.FAILED;
        }
        if (!(ctx.level().getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) || !entity.hasAll(wanted)) {
            return Status.FAILED;
        }
        SimpleContainer inventory = ctx.villager().getInventory();
        for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                ItemStack taken = entity.extractForCitizen(entry.getKey(), left);
                if (taken.isEmpty()) {
                    return Status.FAILED;
                }
                left -= taken.getCount();
                ItemStack rest = inventory.addItem(taken);
                if (!rest.isEmpty()) {
                    entity.insertFromCitizen(rest);
                    return Status.FAILED;
                }
            }
        }
        return Status.SUCCESS;
    }
}
