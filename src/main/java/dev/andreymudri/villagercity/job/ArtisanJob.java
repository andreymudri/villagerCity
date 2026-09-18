package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.craft.CraftAtTable;
import dev.andreymudri.villagercity.craft.CraftPlanner;
import dev.andreymudri.villagercity.craft.CraftStep;
import dev.andreymudri.villagercity.craft.SmeltInFurnace;
import dev.andreymudri.villagercity.craft.VillageDemand;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageWorks.FurnaceClaim;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Crafts and smelts what the other jobs are short of. Every plan asks {@link VillageDemand} what the village needs,
 * lets {@link CraftPlanner} turn that into steps against the storehouse stock, and runs the first step as one round
 * trip: storehouse, workshop, storehouse. Nothing is remembered between plans, so the next one sees whatever the
 * village has become in the meantime.
 */
public final class ArtisanJob implements Job {
    /** How close a walk gets the artisan to the storehouse and the workshop blocks. */
    public static final double WORK_REACH = 2.5;
    /** Fuels the artisan will burn, best first on a tie: the two coals, then any log, then any plank. */
    public static final List<TagKey<Item>> FUEL_TAGS = List.of(ItemTags.LOGS, ItemTags.PLANKS);
    /**
     * Most items one smelting trip may take on. A batch is the village's exposure while it is in a shared furnace, so
     * it is kept small: whatever else goes wrong, an interrupted batch strands at most this much.
     */
    public static final int MAX_SMELT_BATCH = 8;
    /** Slack on top of a batch's smelting time for walking there and back before the villager is due to rest. */
    public static final int TRAVEL_ALLOWANCE_TICKS = 400;
    /** How finely the villager's schedule is sampled when looking ahead for its rest. */
    private static final int REST_SCAN_STEP = 10;
    private static final int DAY_TICKS = 24000;

    private @Nullable String waitingFor;

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        waitingFor = null;
        ServerLevel level = ctx.level();
        VillageData village = ctx.village();
        BlockPos storehousePos = village.storehousePos();
        BlockPos table = village.craftingTablePos();
        BlockPos furnace = village.furnacePos();
        if (storehousePos == null || !(level.getBlockEntity(storehousePos) instanceof StorehouseBlockEntity storehouse)
                || table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)
                || furnace == null || !level.getBlockState(furnace).is(Blocks.FURNACE)) {
            waitingFor = "a workshop by the storehouse";
            return null;
        }
        // Anything still carried after a failed trip belongs in the storehouse first: both the demand and the plan are
        // counted from storehouse stock, so items left in the inventory would be planned for a second time.
        if (!ctx.villager().getInventory().isEmpty()) {
            return TaskSequence.of(MoveTo.digOut(storehousePos, WORK_REACH), new Deposit(storehousePos, stack -> true));
        }
        // An open claim is the village's own stock sitting in the furnace, and the trip that left it there is the very
        // reason the storehouse may be empty of the input now. So it is resumed before anything is planned, and never
        // gated on what the storehouse still holds.
        FurnaceClaim claim = village.furnaceClaim();
        String claimBlocked = null;
        if (claim != null) {
            if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
                waitingFor = "a furnace by the storehouse";
                return null;
            }
            String foreign = SmeltInFurnace.foreignIn(entity, claim);
            if (foreign == null) {
                return resume(storehousePos, storehouse, furnace, entity, claim);
            }
            // The furnace holds more than this receipt covers, so none of it can be told from a player's. The village
            // says which slot and what is in it and gets on with other orders until somebody clears it.
            claimBlocked = occupied(furnace, foreign);
        }
        VillageDemand.Demand demand = VillageDemand.of(level, village, storehouse);
        CraftPlanner.Result result = CraftPlanner.plan(level.getRecipeManager(), level.registryAccess(),
                storehouse.counts(), demand.orders(), demand.reserved());
        village.setArtisanOrders(demand.orders().stream().map(order -> path(order.getKey()) + " x" + order.getValue()).toList());
        if (result.steps().isEmpty()) {
            // A blocked claim outranks having nothing to do. It is exactly when the storehouse is empty that the
            // village most needs to be told its own stock is stuck in the furnace: "materials: sand" sends a player
            // looking for sand, and the sand is already in the furnace they would have to clear to get it back.
            waitingFor = claimBlocked != null ? claimBlocked
                    : result.unmet().isEmpty() ? "no orders" : "materials: " + shortfall(result);
            return null;
        }
        // The first step the artisan can actually run today, not simply the first one planned: a smelt it cannot do
        // right now must not freeze every order queued behind it. A step whose inputs the storehouse does not hold is
        // one that waits for an earlier step's output, so it is skipped with the step it depends on.
        String blocked = claimBlocked;
        for (CraftStep planned : result.steps()) {
            CraftStep step = trimmed(planned);
            if (!storehouseHolds(storehouse, demand.reserved(), step.inputs())) {
                continue;
            }
            if (step.kind() != CraftStep.Kind.SMELT) {
                return craft(storehousePos, table, step);
            }
            // One furnace, one claim: while a batch is open no second one is started, whatever the orders say.
            if (claim != null) {
                continue;
            }
            String why = furnaceBlockedBy(level, furnace, step);
            if (why == null) {
                why = fuelSlotBlockedBy(level, furnace, storehouse, spokenFor(demand, step), step);
            }
            if (why == null) {
                why = tooLateToSmelt(level, ctx.villager(), step.times());
            }
            Task smelting = why == null ? smelt(level, storehousePos, storehouse, furnace, step, demand) : null;
            if (smelting != null) {
                return smelting;
            }
            blocked = blocked != null ? blocked : (why != null ? why : "fuel the storehouse can spare for the furnace");
        }
        waitingFor = blocked != null ? blocked
                : result.unmet().isEmpty() ? "no orders" : "materials: " + shortfall(result);
        return null;
    }

    /** Whether the storehouse holds every one of these amounts on top of what other jobs are counted on having. */
    private static boolean storehouseHolds(StorehouseBlockEntity storehouse, Map<Item, Integer> reserved, Map<Item, Integer> wanted) {
        for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
            if (storehouse.count(entry.getKey()) - reserved.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * What stops a new batch from going into the village furnace, or null when nothing does. A claim may only be
     * opened on a furnace whose input and result slots are empty: that is what makes everything the result slot gains
     * this batch's own output, and it is why a single foreign item in either is reported to the player rather than
     * worked around. Reporting costs the village nothing, because the step loop moves on to other orders.
     */
    private static @Nullable String furnaceBlockedBy(ServerLevel level, BlockPos furnace, CraftStep step) {
        if (step.inputs().size() != 1) {
            return "a smelting step with one input";
        }
        if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return "a furnace by the storehouse";
        }
        for (int slot : new int[] {SmeltInFurnace.SLOT_INPUT, SmeltInFurnace.SLOT_RESULT}) {
            ItemStack held = entity.getItem(slot);
            if (!held.isEmpty()) {
                return occupied(furnace, SmeltInFurnace.SLOT_NAMES[slot] + " slot holding " + path(held.getItem()));
            }
        }
        return null;
    }

    /**
     * Why whatever is already in the fuel slot stops this batch, or null when it does not. The village never empties
     * that slot — see {@link SmeltInFurnace#finish} — so a batch either burns what is in there or does not start: it
     * burns it when it is a fuel and the storehouse can cover whatever more the batch needs, and otherwise the slot
     * and its contents are reported by name and left alone.
     */
    private static @Nullable String fuelSlotBlockedBy(ServerLevel level, BlockPos furnace, StorehouseBlockEntity storehouse,
                                                      Map<Item, Integer> spokenFor, CraftStep step) {
        if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return null;
        }
        ItemStack held = entity.getItem(SmeltInFurnace.SLOT_FUEL);
        return held.isEmpty() || fuelAlreadyIn(storehouse, spokenFor, step.times(), held) != null ? null
                : occupied(furnace, SmeltInFurnace.SLOT_NAMES[SmeltInFurnace.SLOT_FUEL] + " slot holding " + path(held.getItem()));
    }

    /** The batch's own inputs on top of what the other jobs are already counted on having. */
    private static Map<Item, Integer> spokenFor(VillageDemand.Demand demand, CraftStep step) {
        Map<Item, Integer> claimed = new LinkedHashMap<>(demand.reserved());
        step.inputs().forEach((item, amount) -> claimed.merge(item, amount, Integer::sum));
        return claimed;
    }

    /**
     * Why this batch should not be started now, or null when there is day enough for it. A batch left half-done in a
     * shared furnace is the whole problem this class works to recover from, and a villager that walks off to bed
     * mid-smelt leaves exactly that. Sitting the batch out until morning costs the village one idle evening.
     */
    private static @Nullable String tooLateToSmelt(ServerLevel level, Villager villager, int items) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            // The clock is frozen: whatever hour it stopped at, the villager's schedule will never reach rest and
            // the batch has all the time there is. Refusing here would idle the artisan for the rest of the world.
            return null;
        }
        int needed = items * SmeltInFurnace.TICKS_PER_ITEM + SmeltInFurnace.EXTRA_TICKS + TRAVEL_ALLOWANCE_TICKS;
        return ticksUntilRest(level, villager) >= needed ? null : "daylight enough to finish a batch in the furnace";
    }

    /**
     * How long until this villager's own schedule sends it to rest, a whole day when it never does (a GameTest
     * villager with an empty schedule, or one whose day holds no rest at all).
     */
    public static int ticksUntilRest(ServerLevel level, Villager villager) {
        Schedule schedule = villager.getBrain().getSchedule();
        int now = (int) (level.getDayTime() % DAY_TICKS);
        for (int ahead = 0; ahead <= DAY_TICKS; ahead += REST_SCAN_STEP) {
            if (schedule.getActivityAt((now + ahead) % DAY_TICKS) == Activity.REST) {
                return ahead;
            }
        }
        return DAY_TICKS;
    }

    /** Names the furnace, the slot and the item, so {@code /villagercity village} tells a player what to take out. */
    private static String occupied(BlockPos furnace, String what) {
        return "the furnace at " + furnace.toShortString() + " to have its " + what + " emptied";
    }

    private static int burnTime(Item item) {
        Integer burn = AbstractFurnaceBlockEntity.getFuel().get(item);
        return burn == null ? 0 : burn;
    }

    /** Storehouse, table, storehouse: withdraw the batch's inputs, craft them, and store everything brought back. */
    private static Task craft(BlockPos storehousePos, BlockPos table, CraftStep step) {
        return TaskSequence.of(
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Withdraw(storehousePos, step.inputs()),
                MoveTo.digOut(table, WORK_REACH),
                new CraftAtTable(step),
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Deposit(storehousePos, stack -> true));
    }

    /**
     * As {@link #craft}, at the furnace: the villager carries only what the furnace still lacks of the batch and of
     * its fuel, so a batch an earlier trip left behind is finished rather than duplicated. Null when no fuel can be
     * spared, which is a state the caller reports rather than a trip worth walking.
     */
    private static @Nullable Task smelt(ServerLevel level, BlockPos storehousePos, StorehouseBlockEntity storehouse,
                                        BlockPos furnace, CraftStep step, VillageDemand.Demand demand) {
        if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return null;
        }
        Map.Entry<Item, Integer> input = step.inputs().entrySet().iterator().next();
        Map.Entry<Item, Integer> fuel = chooseFuel(storehouse, spokenFor(demand, step), step.times(), entity);
        if (fuel == null) {
            return null;
        }
        FurnaceClaim claim = new FurnaceClaim(input.getKey(), step.times(), fuel.getKey(), fuel.getValue(),
                step.output(), step.times());
        return trip(storehousePos, furnace, entity, claim, storehouse);
    }

    /** Goes back to a batch the village already has in the furnace, carrying only what the claim is still short of. */
    private static Task resume(BlockPos storehousePos, StorehouseBlockEntity storehouse, BlockPos furnace,
                               AbstractFurnaceBlockEntity entity, FurnaceClaim claim) {
        return trip(storehousePos, furnace, entity, claim, storehouse);
    }

    /**
     * Storehouse, furnace, storehouse for one claim. The withdrawal is only the top-up the furnace still lacks, and it
     * is clamped to what the storehouse actually holds: a resumed batch must go ahead even when the trip that started
     * it emptied the shelves, because its stock is already in the furnace.
     */
    private static Task trip(BlockPos storehousePos, BlockPos furnace, AbstractFurnaceBlockEntity entity,
                             FurnaceClaim claim, StorehouseBlockEntity storehouse) {
        Map<Item, Integer> withdrawal = new LinkedHashMap<>();
        want(withdrawal, storehouse, claim.input(), SmeltInFurnace.inputTopUp(entity, claim));
        want(withdrawal, storehouse, claim.fuel(), SmeltInFurnace.fuelTopUp(entity, claim));
        return TaskSequence.of(
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Withdraw(storehousePos, withdrawal),
                MoveTo.digOut(furnace, WORK_REACH),
                new SmeltInFurnace(furnace, claim),
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Deposit(storehousePos, stack -> true));
    }

    /** Adds what the storehouse can actually give of {@code amount}; asking for more only makes the withdrawal fail. */
    private static void want(Map<Item, Integer> withdrawal, StorehouseBlockEntity storehouse, Item item, int amount) {
        int available = (int) Math.min(amount, storehouse.count(item));
        if (available > 0) {
            withdrawal.merge(item, available, Integer::sum);
        }
    }

    /**
     * The spare fuel with the most stock behind it, and how much of it burning {@code items} items takes at
     * {@link SmeltInFurnace#TICKS_PER_ITEM} each. Stock another job is counted on, and the batch's own input, are not
     * spare; a fuel the village cannot spare enough of is skipped rather than half-loaded. Null when none qualifies.
     * <p>
     * Fuel already in the slot settles the choice by itself, because the village will not empty that slot to make
     * room for a fuel it likes better.
     */
    private static @Nullable Map.Entry<Item, Integer> chooseFuel(StorehouseBlockEntity storehouse,
                                                                 Map<Item, Integer> claimed, int items,
                                                                 AbstractFurnaceBlockEntity entity) {
        ItemStack burning = entity.getItem(SmeltInFurnace.SLOT_FUEL);
        if (!burning.isEmpty()) {
            return fuelAlreadyIn(storehouse, claimed, items, burning);
        }
        Map<Item, Integer> burnTimes = AbstractFurnaceBlockEntity.getFuel();
        Item best = null;
        long bestSpare = 0;
        int bestCount = 0;
        for (Item candidate : fuels()) {
            Integer burnTime = burnTimes.get(candidate);
            if (burnTime == null || burnTime <= 0) {
                continue;
            }
            long spare = storehouse.count(candidate) - claimed.getOrDefault(candidate, 0);
            int needed = Mth.ceil(items * (double) SmeltInFurnace.TICKS_PER_ITEM / burnTime);
            if (spare < needed) {
                continue;
            }
            if (best == null || spare > bestSpare) {
                best = candidate;
                bestSpare = spare;
                bestCount = needed;
            }
        }
        return best == null ? null : Map.entry(best, bestCount);
    }

    /**
     * The claim to write for a batch that has to burn what is already in the fuel slot, or null when it cannot: the
     * slot holds something that does not burn, or burns for too little and the storehouse cannot spare the rest.
     * <p>
     * The claim covers everything in the slot, not just the part the village paid for. It has to: the receipt is
     * what says the furnace holds nothing foreign, and a claim that covered less than the slot already held would
     * read as foreign the moment it was written, stranding the batch it just started.
     */
    private static @Nullable Map.Entry<Item, Integer> fuelAlreadyIn(StorehouseBlockEntity storehouse,
                                                                    Map<Item, Integer> claimed, int items, ItemStack burning) {
        int burnTime = burnTime(burning.getItem());
        if (burnTime <= 0) {
            return null;
        }
        int needed = Mth.ceil(items * (double) SmeltInFurnace.TICKS_PER_ITEM / burnTime);
        long spare = storehouse.count(burning.getItem()) - claimed.getOrDefault(burning.getItem(), 0);
        return spare < needed - burning.getCount() ? null
                : Map.entry(burning.getItem(), Math.max(needed, burning.getCount()));
    }

    /** Charcoal and coal first, then every log and every plank, each tag sorted by item id so ties break the same way twice. */
    private static List<Item> fuels() {
        List<Item> fuels = new ArrayList<>(List.of(Items.CHARCOAL, Items.COAL));
        for (TagKey<Item> tag : FUEL_TAGS) {
            List<Item> tagged = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(holder -> tagged.add(holder.value()));
            tagged.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));
            tagged.stream().filter(item -> !fuels.contains(item)).forEach(fuels::add);
        }
        return fuels;
    }

    /**
     * The step cut down to the runs whose output fits a single stack, with its inputs cut in the same proportion, so
     * one trip never asks the villager's eight slots for more than they hold.
     */
    private static CraftStep trimmed(CraftStep step) {
        int perRun = Math.max(1, step.outputCount() / Math.max(1, step.times()));
        int maxRuns = Math.max(1, new ItemStack(step.output()).getMaxStackSize() / perRun);
        if (step.kind() == CraftStep.Kind.SMELT) {
            maxRuns = Math.min(maxRuns, MAX_SMELT_BATCH);
        }
        int times = Math.min(step.times(), maxRuns);
        if (times == step.times()) {
            return step;
        }
        Map<Item, Integer> inputs = new LinkedHashMap<>();
        step.inputs().forEach((item, amount) -> inputs.put(item, Math.max(1, amount / step.times()) * times));
        return new CraftStep(step.kind(), step.recipe(), inputs, step.output(), perRun * times, times);
    }

    /** The base materials that blocked every unmet order, or the unmet items themselves when no base was named. */
    private static String shortfall(CraftPlanner.Result result) {
        return (result.missingBase().isEmpty() ? result.unmet().keySet() : result.missingBase()).stream()
                .map(ArtisanJob::path)
                .collect(Collectors.joining(", "));
    }

    private static String path(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
