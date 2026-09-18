package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Loads the furnace with what the villager carries, waits beside it while vanilla smelts, and takes the result. The
 * wait is bounded by {@link #TICKS_PER_ITEM} per item plus {@link #EXTRA_TICKS}, and counts only the ticks this task
 * ran, so a night the scheduler spends resting does not run it out.
 */
public final class SmeltInFurnace implements Task {
    /** How far the villager may stand from the furnace's center. */
    public static final double REACH = 3.0;
    /** Vanilla's smelting time for one item; the artisan sizes the fuel by it. */
    public static final int TICKS_PER_ITEM = 200;
    /** Slack on top of the expected smelting time before the villager gives up waiting. */
    public static final int EXTRA_TICKS = 100;
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_FUEL = 1;
    public static final int SLOT_RESULT = 2;

    private final BlockPos furnace;
    private final Item input;
    private final int count;
    private final Item fuel;
    private final int fuelCount;
    private boolean loaded;
    private int waited;

    public SmeltInFurnace(BlockPos furnace, Item input, int count, Item fuel, int fuelCount) {
        this.furnace = furnace.immutable();
        this.input = input;
        this.count = count;
        this.fuel = fuel;
        this.fuelCount = fuelCount;
    }

    @Override
    public void start(TaskContext ctx) {
        loaded = false;
        waited = 0;
    }

    @Override
    public String describe(TaskContext ctx) {
        return "smelting " + BuiltInRegistries.ITEM.getKey(input).getPath() + " in " + furnace.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (!(ctx.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return Status.FAILED;
        }
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(furnace)) > REACH * REACH) {
            return Status.FAILED;
        }
        SimpleContainer inventory = ctx.villager().getInventory();
        if (!loaded) {
            return load(entity, inventory);
        }
        waited++;
        ItemStack result = entity.getItem(SLOT_RESULT);
        if (result.getCount() < count && waited <= count * TICKS_PER_ITEM + EXTRA_TICKS) {
            return Status.RUNNING;
        }
        // Out of time with a part of the batch smelted: take what is there rather than leave it in the furnace, and
        // let the artisan plan the rest from the deposited stock.
        if (result.isEmpty()) {
            return Status.FAILED;
        }
        ItemStack taken = entity.removeItem(SLOT_RESULT, result.getCount());
        ItemStack rest = inventory.addItem(taken);
        if (!rest.isEmpty()) {
            entity.setItem(SLOT_RESULT, rest);
            return Status.FAILED;
        }
        return Status.SUCCESS;
    }

    /** Fills the input and fuel slots from the villager's inventory, refusing a furnace already busy with something else. */
    private Status load(AbstractFurnaceBlockEntity entity, SimpleContainer inventory) {
        ItemStack inputSlot = entity.getItem(SLOT_INPUT);
        ItemStack fuelSlot = entity.getItem(SLOT_FUEL);
        if (!inputSlot.isEmpty() && !inputSlot.is(input)) {
            return Status.FAILED;
        }
        if (!fuelSlot.isEmpty() && !fuelSlot.is(fuel)) {
            return Status.FAILED;
        }
        int inputTotal = inputSlot.getCount() + count;
        int fuelTotal = fuelSlot.getCount() + fuelCount;
        if (inputTotal > new ItemStack(input).getMaxStackSize() || fuelTotal > new ItemStack(fuel).getMaxStackSize()) {
            return Status.FAILED;
        }
        int carriedInput = Inventories.count(inventory, stack -> stack.is(input));
        int carriedFuel = Inventories.count(inventory, stack -> stack.is(fuel));
        boolean enough = input == fuel ? carriedInput >= count + fuelCount : carriedInput >= count && carriedFuel >= fuelCount;
        if (!enough) {
            return Status.FAILED;
        }
        inventory.removeItemType(input, count);
        inventory.removeItemType(fuel, fuelCount);
        entity.setItem(SLOT_INPUT, new ItemStack(input, inputTotal));
        entity.setItem(SLOT_FUEL, new ItemStack(fuel, fuelTotal));
        entity.setChanged();
        loaded = true;
        return Status.RUNNING;
    }
}
