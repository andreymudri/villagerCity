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
import dev.andreymudri.villagercity.craft.WorkshopService;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.VillageData;
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
    /**
     * How long the village puts up with a furnace it cannot use before building itself another one. A furnace that
     * stays full -- because a player is using it, or because the village abandoned a batch in it and will not take
     * that back -- must not be able to stop the village smelting for good.
     */
    public static final int FURNACE_PATIENCE_TICKS = 600;
    /** How finely the villager's schedule is sampled when looking ahead for its rest. */
    private static final int REST_SCAN_STEP = 10;
    private static final int DAY_TICKS = 24000;

    private @Nullable String waitingFor;
    /** Game time of the first plan that found the furnace occupied, or 0 while it is usable. */
    private long blockedSince;

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
        VillageDemand.Demand demand = VillageDemand.of(level, village, storehouse);
        CraftPlanner.Result result = CraftPlanner.plan(level.getRecipeManager(), level.registryAccess(),
                storehouse.counts(), demand.orders(), demand.reserved());
        village.setArtisanOrders(demand.orders().stream().map(order -> path(order.getKey()) + " x" + order.getValue()).toList());
        if (result.steps().isEmpty()) {
            waitingFor = result.unmet().isEmpty() ? "no orders" : "materials: " + shortfall(result);
            return null;
        }
        // The first step the artisan can actually run today, not simply the first one planned: a smelt it cannot do
        // right now must not freeze every order queued behind it. A step whose inputs the storehouse does not hold is
        // one that waits for an earlier step's output, so it is skipped with the step it depends on.
        String blocked = null;
        boolean furnaceWanted = false;
        for (CraftStep planned : result.steps()) {
            CraftStep step = trimmed(planned);
            if (!storehouseHolds(storehouse, demand.reserved(), step.inputs())) {
                continue;
            }
            if (step.kind() != CraftStep.Kind.SMELT) {
                return craft(storehousePos, table, step);
            }
            furnaceWanted = true;
            String occupied = furnaceBlockedBy(level, furnace);
            String why = occupied != null ? occupied : tooLateToSmelt(level, ctx.villager(), step.times());
            Task smelting = why == null ? smelt(level, storehousePos, storehouse, furnace, step, demand) : null;
            if (smelting != null) {
                blockedSince = 0;
                return smelting;
            }
            if (occupied == null) {
                // Held up by the hour or by the shelves, not by the furnace: nothing a new furnace would fix.
                blockedSince = 0;
            } else if (blockedSince == 0) {
                blockedSince = level.getGameTime();
            } else if (level.getGameTime() - blockedSince >= FURNACE_PATIENCE_TICKS
                    && WorkshopService.moveFurnace(level, village)) {
                // Long enough. The village builds itself another furnace and leaves this one, and whatever is in it,
                // for whoever put it there. Without this an interrupted batch would stop the village smelting for
                // good, because it will not take back what it walked away from and will not start on a full furnace.
                blockedSince = 0;
                waitingFor = null;
                return null;
            }
            blocked = blocked != null ? blocked : (why != null ? why : "fuel the storehouse can spare for the furnace");
        }
        if (!furnaceWanted) {
            blockedSince = 0;
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
     * What stops a new batch from going into the village furnace, or null when nothing does. A batch may only be
     * started on a furnace whose three slots are all empty: a slot with anything in it belongs to whoever put it
     * there, and a furnace that already holds something offers no way to tell their items from the village's later.
     * The blocker is named by position, slot and item, so the message says exactly what to take out and nothing else.
     */
    private static @Nullable String furnaceBlockedBy(ServerLevel level, BlockPos furnace) {
        if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return "a furnace by the storehouse";
        }
        String held = SmeltInFurnace.occupiedSlot(entity);
        return held == null ? null : occupied(furnace, held);
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
     * As {@link #craft}, at the furnace: withdraw the whole batch and its fuel, smelt it, and bring back whatever the
     * villager watched itself make. Null when no fuel can be spared, which the caller reports rather than walking.
     */
    private static @Nullable Task smelt(ServerLevel level, BlockPos storehousePos, StorehouseBlockEntity storehouse,
                                        BlockPos furnace, CraftStep step, VillageDemand.Demand demand) {
        Map.Entry<Item, Integer> input = step.inputs().entrySet().iterator().next();
        if (step.inputs().size() != 1) {
            return null;
        }
        Map<Item, Integer> spokenFor = new LinkedHashMap<>(demand.reserved());
        step.inputs().forEach((item, amount) -> spokenFor.merge(item, amount, Integer::sum));
        Map.Entry<Item, Integer> fuel = chooseFuel(storehouse, spokenFor, step.times());
        if (fuel == null) {
            return null;
        }
        Map<Item, Integer> withdrawal = new LinkedHashMap<>();
        want(withdrawal, storehouse, input.getKey(), step.times());
        want(withdrawal, storehouse, fuel.getKey(), fuel.getValue());
        return TaskSequence.of(
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Withdraw(storehousePos, withdrawal),
                MoveTo.digOut(furnace, WORK_REACH),
                new SmeltInFurnace(furnace, input.getKey(), step.times(), fuel.getKey(), fuel.getValue()),
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
     * The furnace's own fuel slot has no say here: a batch only ever starts on an empty furnace, so there is never
     * anything in it to work around, and one stray item can no longer decide what the village is allowed to burn.
     */
    private static @Nullable Map.Entry<Item, Integer> chooseFuel(StorehouseBlockEntity storehouse,
                                                                 Map<Item, Integer> claimed, int items) {
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
    public static CraftStep trimmed(CraftStep step) {
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
