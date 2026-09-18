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
 * Tops the furnace up to one batch of {@code count} items, waits beside it while vanilla smelts, and takes the batch's
 * own output. The wait is bounded by {@link #TICKS_PER_ITEM} per item plus {@link #EXTRA_TICKS}, and counts only the
 * ticks this task ran, so a night the scheduler spends resting does not run it out.
 * <p>
 * The furnace is shared with whoever else uses it, so the task treats it as found, not as owned. It refuses one whose
 * slots hold anything but this batch's own input, fuel and output; it never takes more than the batch could have
 * produced; and it resumes a batch an earlier trip left behind rather than refusing it, which is what keeps an
 * interrupted smelt from stranding the village's stock in the furnace.
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
    private final Item output;
    private boolean loaded;
    private int waited;
    private int handedInput;
    private int handedFuel;

    public SmeltInFurnace(BlockPos furnace, Item input, int count, Item fuel, int fuelCount, Item output) {
        this.furnace = furnace.immutable();
        this.input = input;
        this.count = count;
        this.fuel = fuel;
        this.fuelCount = fuelCount;
        this.output = output;
    }

    /** How many of a {@code count}-item batch are still to be smelted, given the output the furnace already holds for it. */
    public static int remaining(AbstractFurnaceBlockEntity entity, Item output, int count) {
        ItemStack result = entity.getItem(SLOT_RESULT);
        return Math.max(0, count - (result.is(output) ? Math.min(count, result.getCount()) : 0));
    }

    /** How much of the input the storehouse must still supply: what is left to smelt, less what the furnace already holds. */
    public static int inputTopUp(AbstractFurnaceBlockEntity entity, Item input, int count, Item output) {
        ItemStack inputSlot = entity.getItem(SLOT_INPUT);
        return Math.max(0, remaining(entity, output, count) - (inputSlot.is(input) ? inputSlot.getCount() : 0));
    }

    /** How much fuel the storehouse must still supply for a batch that wants {@code fuelCount} in the fuel slot. */
    public static int fuelTopUp(AbstractFurnaceBlockEntity entity, Item fuel, int fuelCount) {
        ItemStack fuelSlot = entity.getItem(SLOT_FUEL);
        return Math.max(0, fuelCount - (fuelSlot.is(fuel) ? fuelSlot.getCount() : 0));
    }

    @Override
    public void start(TaskContext ctx) {
        loaded = false;
        waited = 0;
        handedInput = 0;
        handedFuel = 0;
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
        SimpleContainer inventory = ctx.villager().getInventory();
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(furnace)) > REACH * REACH) {
            // Walked out of reach mid-smelt, which is what a night does: the brain takes the villager to bed and the
            // scheduler ticks this task again in the morning, too far away to touch the furnace. The batch stays put
            // and the next trip resumes it, so giving up here strands nothing.
            return Status.FAILED;
        }
        if (!loaded) {
            return load(entity, inventory);
        }
        waited++;
        ItemStack result = entity.getItem(SLOT_RESULT);
        if (!result.isEmpty() && !result.is(output)) {
            // Somebody else's smelting finished into the result slot while this batch waited. None of it is the
            // village's, so the batch is abandoned and only what this task handed over comes back.
            return recover(entity, inventory) > 0 ? Status.SUCCESS : Status.FAILED;
        }
        if (result.getCount() < count && waited <= count * TICKS_PER_ITEM + EXTRA_TICKS) {
            return Status.RUNNING;
        }
        int taken = moveOut(entity, inventory, SLOT_RESULT, Math.min(result.getCount(), count));
        // Finished, or out of time. Either way take back whatever of this batch the furnace never burned, so the
        // village's books balance again as soon as the villager deposits.
        int back = taken >= count ? 0 : recover(entity, inventory);
        return taken + back > 0 ? Status.SUCCESS : Status.FAILED;
    }

    /**
     * Tops the input and fuel slots up to one batch from what the villager carries, on a furnace that is empty or
     * already holds this very batch. A furnace holding anything else is left alone.
     */
    private Status load(AbstractFurnaceBlockEntity entity, SimpleContainer inventory) {
        ItemStack inputSlot = entity.getItem(SLOT_INPUT);
        ItemStack fuelSlot = entity.getItem(SLOT_FUEL);
        ItemStack resultSlot = entity.getItem(SLOT_RESULT);
        if ((!inputSlot.isEmpty() && !inputSlot.is(input))
                || (!fuelSlot.isEmpty() && !fuelSlot.is(fuel))
                || (!resultSlot.isEmpty() && !resultSlot.is(output))) {
            return Status.FAILED;
        }
        int wantInput = inputTopUp(entity, input, count, output);
        int wantFuel = fuelTopUp(entity, fuel, fuelCount);
        int carriedInput = Inventories.count(inventory, stack -> stack.is(input));
        int carriedFuel = Inventories.count(inventory, stack -> stack.is(fuel));
        boolean enough = input == fuel
                ? carriedInput >= wantInput + wantFuel
                : carriedInput >= wantInput && carriedFuel >= wantFuel;
        if (!enough) {
            return Status.FAILED;
        }
        if (wantInput > 0) {
            inventory.removeItemType(input, wantInput);
            entity.setItem(SLOT_INPUT, new ItemStack(input, inputSlot.getCount() + wantInput));
            handedInput += wantInput;
        }
        if (wantFuel > 0) {
            inventory.removeItemType(fuel, wantFuel);
            entity.setItem(SLOT_FUEL, new ItemStack(fuel, entity.getItem(SLOT_FUEL).getCount() + wantFuel));
            handedFuel += wantFuel;
        }
        entity.setChanged();
        loaded = true;
        return Status.RUNNING;
    }

    /** Takes back what this task put into the input and fuel slots and the furnace has not burned; never more. */
    private int recover(AbstractFurnaceBlockEntity entity, SimpleContainer inventory) {
        int inputBack = pullBack(entity, inventory, SLOT_INPUT, input, handedInput);
        handedInput -= inputBack;
        int fuelBack = pullBack(entity, inventory, SLOT_FUEL, fuel, handedFuel);
        handedFuel -= fuelBack;
        return inputBack + fuelBack;
    }

    private static int pullBack(AbstractFurnaceBlockEntity entity, SimpleContainer inventory, int slot, Item item, int handed) {
        ItemStack stack = entity.getItem(slot);
        if (handed <= 0 || !stack.is(item)) {
            return 0;
        }
        return moveOut(entity, inventory, slot, Math.min(handed, stack.getCount()));
    }

    /** Moves up to {@code amount} out of the slot into the inventory; whatever does not fit goes back. Returns how many moved. */
    private static int moveOut(AbstractFurnaceBlockEntity entity, SimpleContainer inventory, int slot, int amount) {
        if (amount <= 0) {
            return 0;
        }
        ItemStack taken = entity.removeItem(slot, amount);
        if (taken.isEmpty()) {
            return 0;
        }
        int wanted = taken.getCount();
        ItemStack rest = inventory.addItem(taken);
        if (!rest.isEmpty()) {
            ItemStack left = entity.getItem(slot);
            entity.setItem(slot, left.isEmpty() ? rest : left.copyWithCount(left.getCount() + rest.getCount()));
        }
        return wanted - rest.getCount();
    }
}
