package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Smelts one batch in the village furnace and takes away only what it watched the furnace make from its own input.
 * <p>
 * A furnace slot carries no ownership information: the village's own finished glass is byte-identical to a player's,
 * and anything written down about what was left in there is a claim about items that are gone the moment nobody is
 * watching them. So this task infers nothing. It claims one thing only, and only while it is standing there:
 * <ul>
 * <li>a batch <em>starts</em> only on a furnace whose three slots are all empty ({@link #isEmpty}), checked at the
 *     tick the items move rather than back at the storehouse, because a furnace can change during the walk;</li>
 * <li>from then on the slots are sampled every tick. Output that appears while this batch's own input shrinks by the
 *     same amount was made from this batch; output that appears without that is somebody else's and is never
 *     counted, taken or deposited. Vanilla writes the result and shrinks the input inside one
 *     {@link AbstractFurnaceBlockEntity} server tick, so the two move together and the correlation is exact;</li>
 * <li>what is credited that way is taken out immediately, so there is never an amount the village is "owed" and no
 *     moment at which something that merely looks like it could satisfy a debt would do;</li>
 * <li>anything that stops the watch — walking out of reach, a chunk unloading, a restart, the villager dying — ends
 *     the batch for good. The furnace keeps what is in it and the village never comes back for it.</li>
 * </ul>
 * The fuel slot is never emptied on any path: fuel handed to a shared furnace is spent. Losing the village's own sand
 * is a bad day; taking a player's sand is a bug.
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
    /** Furnace slots by index, for the messages a player reads in {@code /villagercity village}. */
    public static final String[] SLOT_NAMES = {"input", "fuel", "result"};

    private final BlockPos furnace;
    private final Item input;
    private final int count;
    private final Item fuel;
    private final int fuelCount;

    private boolean loaded;
    private int waited;
    /** How much of the input slot is this batch's own: what went in, less what has since been smelted or removed. */
    private int ours;
    /** The input slot's count at the last sample, to read the next tick's change against. */
    private int lastInput;
    /** The result slot's count at the last sample, likewise. */
    private int lastResult;
    /** How many outputs this batch was watched being made and has not been carried off yet. */
    private int credited;
    /** How many outputs the villager has actually taken. */
    private int taken;

    public SmeltInFurnace(BlockPos furnace, Item input, int count, Item fuel, int fuelCount) {
        this.furnace = furnace.immutable();
        this.input = input;
        this.count = count;
        this.fuel = fuel;
        this.fuelCount = fuelCount;
    }

    /** Whether all three slots are empty: the only state a batch may ever be started on. */
    public static boolean isEmpty(AbstractFurnaceBlockEntity entity) {
        return entity.getItem(SLOT_INPUT).isEmpty() && entity.getItem(SLOT_FUEL).isEmpty()
                && entity.getItem(SLOT_RESULT).isEmpty();
    }

    /** The first slot holding anything, with what is in it, or null when the furnace is free for a new batch. */
    public static @Nullable String occupiedSlot(AbstractFurnaceBlockEntity entity) {
        for (int slot : new int[] {SLOT_INPUT, SLOT_FUEL, SLOT_RESULT}) {
            ItemStack held = entity.getItem(slot);
            if (!held.isEmpty()) {
                // Name the slot that is actually holding something. A message that sends a player to empty the wrong
                // slot is not a cosmetic problem: they clear what they were told to, and what is left reads as the
                // village's by arithmetic.
                return SLOT_NAMES[slot] + " slot holding " + held.getCount() + " " + name(held.getItem());
            }
        }
        return null;
    }

    @Override
    public void start(TaskContext ctx) {
        loaded = false;
        waited = 0;
        ours = 0;
        lastInput = 0;
        lastResult = 0;
        credited = 0;
        taken = 0;
    }

    @Override
    public String describe(TaskContext ctx) {
        return "smelting " + name(input) + " in " + furnace.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (!(ctx.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return abandon();
        }
        SimpleContainer inventory = ctx.villager().getInventory();
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(furnace)) > REACH * REACH) {
            // Out of reach, which is what a night does: the brain walks the villager to bed and the task ticks again
            // in the morning too far away to see the furnace. The watch is broken, so the batch is over. Whatever is
            // in there stays there; from here the village could not tell it from anybody else's.
            return abandon();
        }
        if (!loaded) {
            return load(ctx, entity, inventory);
        }
        waited++;
        observe(entity);
        collect(entity, inventory);
        if (taken >= count || (ours <= 0 && credited <= 0) || waited > count * TICKS_PER_ITEM + EXTRA_TICKS) {
            // Done, out of batch, or out of patience. Anything of this batch still in the input slot is taken back:
            // it has been in sight every tick since the villager put it there, which is the only thing that makes it
            // knowably the village's. The fuel slot is not touched.
            ItemStack left = entity.getItem(SLOT_INPUT);
            int back = moveOut(entity, inventory, SLOT_INPUT, left.is(input) ? Math.min(ours, left.getCount()) : 0);
            return taken + back > 0 ? Status.SUCCESS : Status.FAILED;
        }
        return Status.RUNNING;
    }

    /** Ends the batch without taking anything, leaving the furnace exactly as it stands. */
    private Status abandon() {
        return taken > 0 ? Status.SUCCESS : Status.FAILED;
    }

    /**
     * Puts the batch and its fuel in, in one tick, and starts the watch. The furnace must be completely empty: a slot
     * with anything in it belongs to whoever put it there, and there is no way to tell their sand from ours later.
     */
    private Status load(TaskContext ctx, AbstractFurnaceBlockEntity entity, SimpleContainer inventory) {
        if (!isEmpty(entity)) {
            return Status.FAILED;
        }
        int giveInput = Math.min(room(entity, input, count), Inventories.count(inventory, stack -> stack.is(input)));
        int giveFuel = Math.min(room(entity, fuel, fuelCount),
                Inventories.count(inventory, stack -> stack.is(fuel)) - (input == fuel ? giveInput : 0));
        if (giveInput <= 0 || giveFuel <= 0) {
            // Nothing handed over is not a batch. Starting the watch here would leave the villager standing over an
            // empty furnace calling whatever turns up in it its own.
            return Status.FAILED;
        }
        inventory.removeItemType(input, giveInput);
        entity.setItem(SLOT_INPUT, new ItemStack(input, giveInput));
        inventory.removeItemType(fuel, giveFuel);
        entity.setItem(SLOT_FUEL, new ItemStack(fuel, giveFuel));
        entity.setChanged();
        ours = giveInput;
        lastInput = giveInput;
        lastResult = 0;
        loaded = true;
        return Status.RUNNING;
    }

    /**
     * One sample of the two slots that matter. Output is credited only as far as this batch's own input shrank in the
     * same tick: a player dropping glass into the result slot moves one of the two numbers and not the other, and so
     * is worth nothing here however exactly it matches what the village is making.
     */
    private void observe(AbstractFurnaceBlockEntity entity) {
        ItemStack inputSlot = entity.getItem(SLOT_INPUT);
        ItemStack resultSlot = entity.getItem(SLOT_RESULT);
        int nowInput = inputSlot.is(input) ? inputSlot.getCount() : 0;
        int nowResult = resultSlot.getCount();
        int smelted = Math.min(Math.max(0, lastInput - nowInput), ours);
        int appeared = Math.max(0, nowResult - lastResult);
        // Bounded by {@code ours}, which only ever shrinks, this can never add up to more than the batch put in.
        credited += Math.min(smelted, appeared);
        // Whatever left the input slot is no longer ours to take back, however it left. What was added to it is
        // somebody else's: the batch never grows.
        ours = Math.max(0, ours - Math.max(0, lastInput - nowInput));
        lastInput = nowInput;
        lastResult = nowResult;
    }

    /** Carries off what has been credited, the tick it is credited, so there is never an outstanding claim. */
    private void collect(AbstractFurnaceBlockEntity entity, SimpleContainer inventory) {
        if (credited <= 0) {
            return;
        }
        int moved = moveOut(entity, inventory, SLOT_RESULT, credited);
        credited -= moved;
        taken += moved;
        lastResult = entity.getItem(SLOT_RESULT).getCount();
    }

    /** How many of {@code item} fit in a furnace slot, at most {@code want}. */
    private static int room(AbstractFurnaceBlockEntity entity, Item item, int want) {
        return Math.max(0, Math.min(want, Math.min(entity.getMaxStackSize(), new ItemStack(item).getMaxStackSize())));
    }

    /** Moves up to {@code amount} out of the slot into the inventory; whatever does not fit goes back. Returns how many moved. */
    private static int moveOut(AbstractFurnaceBlockEntity entity, SimpleContainer inventory, int slot, int amount) {
        if (amount <= 0) {
            return 0;
        }
        ItemStack out = entity.removeItem(slot, amount);
        if (out.isEmpty()) {
            return 0;
        }
        int wanted = out.getCount();
        ItemStack rest = inventory.addItem(out);
        if (!rest.isEmpty()) {
            ItemStack left = entity.getItem(slot);
            entity.setItem(slot, left.isEmpty() ? rest : left.copyWithCount(left.getCount() + rest.getCount()));
        }
        return wanted - rest.getCount();
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
