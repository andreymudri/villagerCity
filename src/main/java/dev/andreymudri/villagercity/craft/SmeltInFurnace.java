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
 * The furnace is shared with players and a furnace slot carries no ownership information at all: the village's own
 * finished batch is byte-identical to a player's. So nothing here infers ownership from what a slot holds. Four rules
 * decide everything, and all four are checked in this class at the tick the items actually move, never back at the
 * storehouse where the answer goes stale during the walk:
 * <ul>
 * <li>a claim is <em>opened</em> only on a furnace whose input and result slots are empty ({@link #openable});</li>
 * <li>a claim is <em>resumed</em>, and output taken, only while the furnace still holds nothing the receipt does not
 *     cover ({@link #foreignIn});</li>
 * <li>what may still be taken is counted over the batch's whole life ({@link FurnaceClaim#takenCount}), so emptying
 *     the result slot never hands the allowance back;</li>
 * <li>a batch that is no longer in the furnace is <em>written off</em> ({@link #vanished}) — the receipt is dropped
 *     and nothing is ever taken for it, because the next thing to appear in that furnace is not it.</li>
 * </ul>
 * The village also never takes anything out of the fuel slot. Fuel handed to a shared furnace is spent, and losing
 * the village's own coal is a bad day where carrying off a player's coal is a bug.
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

    /**
     * Whether a new claim may be opened on this furnace: the input and result slots must be empty, so that nothing
     * the batch has not put there can ever be counted as the batch's own.
     * <p>
     * The fuel slot may hold something, because the village never empties it: a batch either burns what is already in
     * there — which is why the claim has to cover it — or does not start.
     */
    public static boolean openable(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        ItemStack fuel = entity.getItem(SLOT_FUEL);
        return entity.getItem(SLOT_INPUT).isEmpty() && entity.getItem(SLOT_RESULT).isEmpty()
                && (fuel.isEmpty() || (fuel.is(claim.fuel()) && fuel.getCount() <= claim.fuelCount()));
    }

    /** How much of the output this receipt may still be paid, over the batch's whole life. */
    public static int owedOutput(FurnaceClaim claim) {
        return Math.max(0, claim.outputCount() - claim.takenCount());
    }

    /** How much of the batch is still unsettled: what went in, less what has already been carried off. */
    public static int owedInput(FurnaceClaim claim) {
        return Math.max(0, claim.inputCount() - claim.takenCount());
    }

    /**
     * Null while the furnace holds nothing but this receipt; otherwise the slot and contents that the receipt does not
     * cover, ready to be reported to a player. A slot is covered when it is empty, or holds the receipt's own item for
     * that slot in a count no greater than the receipt's. On top of that the input and the output still in the furnace
     * may not add up to more than the batch has left unsettled: smelting turns one input into one output, so a furnace
     * holding more of them than that is holding somebody else's as well.
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
        int excess = unsmelted + smelted - owedInput(claim);
        if (excess > 0) {
            // Name the slot that actually carries the excess, not whichever one is easiest to name. A player who
            // empties the slot the village pointed at has done what was asked, and if the excess was in the other
            // slot all that clearing achieves is to bring the rest of their stock inside the receipt's bound.
            int slot = unsmelted >= excess ? SLOT_INPUT : SLOT_RESULT;
            int held = slot == SLOT_INPUT ? unsmelted : smelted;
            Item item = slot == SLOT_INPUT ? claim.input() : claim.output();
            return SLOT_NAMES[slot] + " slot holding " + held + " " + name(item) + ", " + excess
                    + " more than this batch has left in the furnace";
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
     * Whether the batch is gone: the furnace holds neither anything left to smelt nor anything smelted, while the
     * receipt is still owed output. Only ever read with {@link #foreignIn} already clear, so "holds nothing" really
     * does mean the batch's own items are not there.
     * <p>
     * A hopper under the furnace reaches this state on its own, with nobody doing anything wrong. Whatever put the
     * batch beyond reach, it is not coming back, and the next thing to appear in that result slot belongs to whoever
     * put it there. The receipt is dropped and nothing is taken for it, ever.
     */
    public static boolean vanished(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        return owedOutput(claim) > 0 && entity.getItem(SLOT_INPUT).isEmpty() && entity.getItem(SLOT_RESULT).isEmpty();
    }

    /**
     * How many of the claimed batch are still to be smelted. Only ever read after {@link #foreignIn} has passed, so
     * everything counted here really is this batch's own.
     */
    public static int remaining(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        return Math.max(0, owedInput(claim) - produced(entity, claim));
    }

    /** How many of the claim's output the furnace already holds for it, never more than the claim is still owed. */
    public static int produced(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        ItemStack result = entity.getItem(SLOT_RESULT);
        return result.is(claim.output()) ? Math.min(owedOutput(claim), result.getCount()) : 0;
    }

    /** How much of the input the villager must still bring: what is left to smelt, less what the furnace already holds. */
    public static int inputTopUp(AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        ItemStack slot = entity.getItem(SLOT_INPUT);
        int want = remaining(entity, claim) - (slot.is(claim.input()) ? slot.getCount() : 0);
        return capped(entity, claim.input(), slot, want);
    }

    /**
     * How much fuel the villager must still bring: what the claim sized for the batch, less what is already burning
     * in the slot. None at all for a batch with nothing left to smelt — fuel put in then can never come back out.
     */
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
        // The receipt lives in the village, not in this task, and the village is the only copy that counts what has
        // already been taken. Read it back every tick rather than trusting the one this trip was planned with.
        FurnaceClaim live = ctx.village().furnaceClaim();
        if (live == null) {
            return Status.FAILED;
        }
        waited++;
        if (foreignIn(entity, live) != null) {
            // Somebody opened the furnace while this batch was cooking. Nothing in there can be told apart from
            // theirs any more, so nothing is taken and nothing is recovered; the receipt stays, and the artisan
            // reports the slot until it is cleared.
            return Status.FAILED;
        }
        if (vanished(entity, live)) {
            return writeOff(ctx);
        }
        if (entity.getItem(SLOT_RESULT).getCount() < owedOutput(live)
                && waited <= live.inputCount() * TICKS_PER_ITEM + EXTRA_TICKS) {
            return Status.RUNNING;
        }
        int taken = moveOut(entity, inventory, SLOT_RESULT, produced(entity, live));
        // The receipt shrinks by what was carried off before anything else reads it, so no later step can be told
        // that this batch is still owed what it has already been paid.
        FurnaceClaim settled = taken > 0 ? live.plusTaken(taken) : live;
        if (taken > 0 && owedOutput(settled) > 0 && !entity.getItem(SLOT_INPUT).isEmpty()) {
            // Part of a long batch collected while the rest is still smelting. The receipt stays open for the trip
            // that comes back for it, one item smaller: each of those trips takes at least one, so there is always
            // an end to them.
            ctx.village().setFurnaceClaim(settled);
            VillageRegistry.get(ctx.level()).setDirty();
            return Status.SUCCESS;
        }
        return finish(ctx, entity, inventory, settled, taken);
    }

    /**
     * Tops the input and fuel slots up from what the villager carries and records the receipt, in the same tick. A new
     * claim needs a furnace {@link #openable} for it and a resumed one needs a furnace still holding nothing but its
     * own batch; either way, a furnace that fails its check is left exactly as it was found.
     */
    private Status load(TaskContext ctx, AbstractFurnaceBlockEntity entity, SimpleContainer inventory) {
        boolean resuming = claim.equals(ctx.village().furnaceClaim());
        if (resuming) {
            if (foreignIn(entity, claim) != null) {
                return Status.FAILED;
            }
            if (vanished(entity, claim)) {
                return writeOff(ctx);
            }
        } else if (!openable(entity, claim)) {
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
     * Takes back what is left of the receipt's own input, on every exit path including a batch that finished, and
     * closes the receipt.
     * <p>
     * The fuel slot is not touched here and is not touched anywhere. There is no bound that makes taking fuel out
     * safe: whatever is in that slot may be the village's leftover or a player's stock, and the two are the same
     * item with the same count. So fuel the village hands over is spent, and what a player leaves is theirs.
     */
    private Status finish(TaskContext ctx, AbstractFurnaceBlockEntity entity, SimpleContainer inventory,
                          FurnaceClaim settled, int taken) {
        int back = moveOut(entity, inventory, SLOT_INPUT, owned(entity, SLOT_INPUT, settled.input(), owedInput(settled)));
        ctx.village().clearFurnaceClaim();
        VillageRegistry.get(ctx.level()).setDirty();
        return taken + back > 0 ? Status.SUCCESS : Status.FAILED;
    }

    /** Drops a receipt whose batch is no longer in the furnace, taking nothing at all for it. */
    private Status writeOff(TaskContext ctx) {
        ctx.village().clearFurnaceClaim();
        VillageRegistry.get(ctx.level()).setDirty();
        return Status.FAILED;
    }

    /** How many of a slot's contents the receipt covers: never more than it says the village has left in there. */
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
