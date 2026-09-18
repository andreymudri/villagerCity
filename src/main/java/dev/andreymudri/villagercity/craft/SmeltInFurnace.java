package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.VillageWorks.FurnaceClaim;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Runs one {@link FurnaceClaim}: tops the furnace up to the claimed batch, waits beside it while vanilla smelts, and
 * takes the batch's own output. The wait is bounded by {@link #TICKS_PER_ITEM} per item plus {@link #EXTRA_TICKS}, and
 * counts only the ticks this task ran, so a night the scheduler spends resting does not run it out.
 * <p>
 * The furnace is shared with players and a slot says nothing about who filled it, so two rules decide everything here
 * and both are checked in this class, at the tick the items actually move:
 * <ul>
 * <li>a claim is <em>opened</em> only on a furnace whose three slots are all empty ({@link #isEmpty}), so the receipt
 *     starts from a state where nothing in the furnace belongs to anybody else;</li>
 * <li>a claim is <em>resumed</em>, and anything at all is taken out, only while the furnace still holds nothing but
 *     that receipt ({@link #foreignIn}).</li>
 * </ul>
 * Neither is checked only when the trip is planned: a player can open the furnace during the walk, so a check made
 * back at the storehouse says nothing about the furnace by the time the villager reaches it.
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
    private final FurnaceClaim claim;
    private boolean loaded;
    private int waited;

    public SmeltInFurnace(BlockPos furnace, FurnaceClaim claim) {
        this.furnace = furnace.immutable();
        this.claim = claim;
    }

    /** Whether all three slots are empty: the only state a new claim may ever be opened on. */
    public static boolean isEmpty(AbstractFurnaceBlockEntity entity) {
        return entity.getItem(SLOT_INPUT).isEmpty() && entity.getItem(SLOT_FUEL).isEmpty()
                && entity.getItem(SLOT_RESULT).isEmpty();
    }

    /**
     * Null while the furnace holds nothing but this receipt; otherwise the slot and contents that the receipt does not
     * cover, ready to be reported to a player. A slot is covered when it is empty, or holds the receipt's own item for
     * that slot in a count no greater than the receipt's. On top of that the input and the output still in the furnace
     * may not add up to more than the batch put in: smelting turns one input into one output, so a furnace holding
     * more of them than the receipt paid for is holding somebody else's as well.
     */
    public static @Nullable String foreignIn(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        String input = beyond(entity, SLOT_INPUT, claim.input(), claim.inputCount());
        if (input != null) {
            return input;
        }
        String fuel = beyond(entity, SLOT_FUEL, claim.fuel(), claim.fuelCount());
        if (fuel != null) {
            return fuel;
        }
        String result = beyond(entity, SLOT_RESULT, claim.output(), claim.outputCount());
        if (result != null) {
            return result;
        }
        int unsmelted = entity.getItem(SLOT_INPUT).getCount();
        int smelted = entity.getItem(SLOT_RESULT).getCount();
        if (unsmelted + smelted > claim.inputCount()) {
            return SLOT_NAMES[SLOT_RESULT] + " slot holding " + smelted + " " + name(claim.output())
                    + ", more than the " + claim.inputCount() + " this batch put in";
        }
        return null;
    }

    /** What this slot holds that the receipt does not cover, or null when it covers all of it. */
    private static @Nullable String beyond(AbstractFurnaceBlockEntity entity, int slot, Item allowed, int limit) {
        ItemStack held = entity.getItem(slot);
        if (held.isEmpty()) {
            return null;
        }
        if (!held.is(allowed)) {
            return SLOT_NAMES[slot] + " slot holding " + name(held.getItem());
        }
        return held.getCount() > limit
                ? SLOT_NAMES[slot] + " slot holding " + held.getCount() + " " + name(allowed) + ", more than the " + limit + " claimed"
                : null;
    }

    /**
     * How many of the claimed batch are still to be smelted. Only ever read after {@link #foreignIn} has passed, so
     * everything counted here really is this batch's own.
     */
    public static int remaining(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        return Math.max(0, claim.inputCount() - produced(entity, claim));
    }

    /** How many of the claim's output the furnace already holds for it, never more than the claim is owed. */
    public static int produced(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        ItemStack result = entity.getItem(SLOT_RESULT);
        return result.is(claim.output()) ? Math.min(claim.outputCount(), result.getCount()) : 0;
    }

    /** How much of the input the villager must still bring: what is left to smelt, less what the furnace already holds. */
    public static int inputTopUp(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        ItemStack slot = entity.getItem(SLOT_INPUT);
        int want = remaining(entity, claim) - (slot.is(claim.input()) ? slot.getCount() : 0);
        return capped(entity, claim.input(), slot, want);
    }

    /** How much fuel the villager must still bring; none at all for a batch with nothing left to smelt. */
    public static int fuelTopUp(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        if (remaining(entity, claim) <= 0) {
            return 0;
        }
        ItemStack slot = entity.getItem(SLOT_FUEL);
        int want = claim.fuelCount() - (slot.is(claim.fuel()) ? slot.getCount() : 0);
        return capped(entity, claim.fuel(), slot, want);
    }

    /**
     * {@code want}, floored at zero and capped at the room left in the slot. Without the cap
     * {@link AbstractFurnaceBlockEntity#setItem} truncates the stack silently and destroys the excess, which the
     * villager has already taken out of the storehouse and paid for.
     */
    private static int capped(AbstractFurnaceBlockEntity entity, Item item, ItemStack slot, int want) {
        int limit = Math.min(entity.getMaxStackSize(), new ItemStack(item).getMaxStackSize());
        return Math.max(0, Math.min(want, limit - slot.getCount()));
    }

    @Override
    public void start(TaskContext ctx) {
        loaded = false;
        waited = 0;
    }

    @Override
    public String describe(TaskContext ctx) {
        return "smelting " + name(claim.input()) + " in " + furnace.toShortString();
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (!(ctx.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return Status.FAILED;
        }
        SimpleContainer inventory = ctx.villager().getInventory();
        if (ctx.villager().distanceToSqr(Vec3.atCenterOf(furnace)) > REACH * REACH) {
            // Out of reach mid-smelt, which is what a night does: the brain walks the villager to bed and the
            // scheduler ticks this task again in the morning, too far away to touch the furnace. The claim stays
            // written, so the next trip resumes this batch however empty the storehouse is by then.
            return Status.FAILED;
        }
        if (!loaded) {
            return load(ctx, entity, inventory);
        }
        waited++;
        if (foreignIn(entity, claim) != null) {
            // Somebody opened the furnace while this batch was cooking. Nothing in there can be told apart from
            // theirs any more, so nothing is taken and nothing is recovered; the receipt stays, and the artisan
            // reports the slot until it is cleared.
            return Status.FAILED;
        }
        if (entity.getItem(SLOT_RESULT).getCount() < claim.outputCount()
                && waited <= claim.inputCount() * TICKS_PER_ITEM + EXTRA_TICKS) {
            return Status.RUNNING;
        }
        return finish(ctx, entity, inventory, moveOut(entity, inventory, SLOT_RESULT, produced(entity, claim)));
    }

    /**
     * Tops the input and fuel slots up from what the villager carries and records the receipt, in the same tick. A new
     * claim needs an empty furnace and a resumed one needs a furnace still holding nothing but its own batch; either
     * way, a furnace that fails its check is left exactly as it was found.
     */
    private Status load(TaskContext ctx, AbstractFurnaceBlockEntity entity, SimpleContainer inventory) {
        boolean resuming = claim.equals(ctx.village().furnaceClaim());
        if (resuming ? foreignIn(entity, claim) != null : !isEmpty(entity)) {
            return Status.FAILED;
        }
        Item input = claim.input();
        Item fuel = claim.fuel();
        int carriedInput = Inventories.count(inventory, stack -> stack.is(input));
        int carriedFuel = Inventories.count(inventory, stack -> stack.is(fuel));
        // Hand over as much of the top-up as the villager actually carries rather than refusing outright. A resumed
        // batch is exactly the case where the storehouse could not refill the trip, and failing here would re-plan
        // the same trip for ever; a short batch instead runs, times out and settles up.
        int giveInput = Math.min(inputTopUp(entity, claim), carriedInput);
        int giveFuel = Math.max(0, Math.min(fuelTopUp(entity, claim), input == fuel ? carriedFuel - giveInput : carriedFuel));
        if (!resuming && giveInput <= 0) {
            // Nothing to start a batch with: opening a receipt for items that never went in is how a claim comes to
            // cover stock the village never put there.
            return Status.FAILED;
        }
        if (resuming && giveInput <= 0 && giveFuel <= 0 && produced(entity, claim) == 0 && entity.getItem(SLOT_INPUT).isEmpty()) {
            // Nothing of the batch is in the furnace and nothing can be put there: the receipt is stale, drop it.
            return finish(ctx, entity, inventory, 0);
        }
        if (giveInput > 0) {
            inventory.removeItemType(input, giveInput);
            grow(entity, SLOT_INPUT, input, giveInput);
        }
        if (giveFuel > 0) {
            inventory.removeItemType(fuel, giveFuel);
            grow(entity, SLOT_FUEL, fuel, giveFuel);
        }
        entity.setChanged();
        // The receipt goes in the village, not in this task: the villager may never come back for it.
        ctx.village().setFurnaceClaim(claim);
        VillageRegistry.get(ctx.level()).setDirty();
        loaded = true;
        return Status.RUNNING;
    }

    /**
     * Takes back everything of the receipt still sitting in the input and fuel slots, on every exit path including a
     * batch that finished: fuel left over from a finished batch is what would otherwise sit there for ever and lock
     * the village out of its own furnace. Only reached with {@link #foreignIn} already clear.
     */
    private Status finish(TaskContext ctx, AbstractFurnaceBlockEntity entity, SimpleContainer inventory, int taken) {
        int back = moveOut(entity, inventory, SLOT_INPUT, owned(entity, SLOT_INPUT, claim.input(), claim.inputCount()))
                + moveOut(entity, inventory, SLOT_FUEL, owned(entity, SLOT_FUEL, claim.fuel(), claim.fuelCount()));
        ctx.village().clearFurnaceClaim();
        VillageRegistry.get(ctx.level()).setDirty();
        return taken + back > 0 ? Status.SUCCESS : Status.FAILED;
    }

    /** How many of a slot's contents the receipt covers: never more than it says the village put there. */
    private static int owned(AbstractFurnaceBlockEntity entity, int slot, Item item, int claimed) {
        ItemStack held = entity.getItem(slot);
        return held.is(item) ? Math.min(claimed, held.getCount()) : 0;
    }

    /** Adds to a slot without replacing what is in it, so a player's renamed stack keeps its data components. */
    private static void grow(AbstractFurnaceBlockEntity entity, int slot, Item item, int amount) {
        ItemStack present = entity.getItem(slot);
        entity.setItem(slot, present.isEmpty() ? new ItemStack(item, amount) : present.copyWithCount(present.getCount() + amount));
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

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
