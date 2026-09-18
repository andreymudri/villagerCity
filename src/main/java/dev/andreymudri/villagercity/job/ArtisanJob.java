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
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        for (CraftStep planned : result.steps()) {
            CraftStep step = trimmed(planned);
            if (!storehouseHolds(storehouse, demand.reserved(), step.inputs())) {
                continue;
            }
            if (step.kind() != CraftStep.Kind.SMELT) {
                return craft(storehousePos, table, step);
            }
            String why = furnaceBlockedBy(level, furnace, step);
            Task smelting = why == null ? smelt(level, storehousePos, storehouse, furnace, step, demand) : null;
            if (smelting != null) {
                return smelting;
            }
            blocked = blocked != null ? blocked : (why != null ? why : "fuel for the furnace");
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
     * What stops this batch from going into the village furnace right now, or null when nothing does. The furnace is
     * shared: a slot holding somebody else's items is theirs to clear, and the artisan says so through
     * {@link #waitingFor} instead of walking a trip that can only fail.
     */
    private static @Nullable String furnaceBlockedBy(ServerLevel level, BlockPos furnace, CraftStep step) {
        if (step.inputs().size() != 1) {
            return "a smelting step with one input";
        }
        if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return "a furnace by the storehouse";
        }
        Item input = step.inputs().keySet().iterator().next();
        ItemStack inputSlot = entity.getItem(SmeltInFurnace.SLOT_INPUT);
        ItemStack resultSlot = entity.getItem(SmeltInFurnace.SLOT_RESULT);
        ItemStack fuelSlot = entity.getItem(SmeltInFurnace.SLOT_FUEL);
        if (!inputSlot.isEmpty() && !inputSlot.is(input)) {
            return occupied(furnace, inputSlot);
        }
        if (!resultSlot.isEmpty() && !resultSlot.is(step.output())) {
            return occupied(furnace, resultSlot);
        }
        if (!fuelSlot.isEmpty() && burnTime(fuelSlot.getItem()) <= 0) {
            return occupied(furnace, fuelSlot);
        }
        return null;
    }

    private static String occupied(BlockPos furnace, ItemStack held) {
        return "the furnace at " + furnace.toShortString() + " to be emptied of " + path(held.getItem());
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
        Map<Item, Integer> claimed = new LinkedHashMap<>(demand.reserved());
        step.inputs().forEach((item, amount) -> claimed.merge(item, amount, Integer::sum));
        Map.Entry<Item, Integer> fuel = chooseFuel(entity, storehouse, claimed, SmeltInFurnace.remaining(entity, step.output(), step.times()));
        if (fuel == null) {
            return null;
        }
        Map<Item, Integer> withdrawal = new LinkedHashMap<>();
        int inputTopUp = SmeltInFurnace.inputTopUp(entity, input.getKey(), step.times(), step.output());
        if (inputTopUp > 0) {
            withdrawal.put(input.getKey(), inputTopUp);
        }
        int fuelTopUp = SmeltInFurnace.fuelTopUp(entity, fuel.getKey(), fuel.getValue());
        if (fuelTopUp > 0) {
            withdrawal.merge(fuel.getKey(), fuelTopUp, Integer::sum);
        }
        return TaskSequence.of(
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Withdraw(storehousePos, withdrawal),
                MoveTo.digOut(furnace, WORK_REACH),
                new SmeltInFurnace(furnace, input.getKey(), step.times(), fuel.getKey(), fuel.getValue(), step.output()),
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Deposit(storehousePos, stack -> true));
    }

    /**
     * The spare fuel with the most stock behind it, and how much of it burning {@code items} items takes at
     * {@link SmeltInFurnace#TICKS_PER_ITEM} each. Stock another job is counted on, and the batch's own input, are not
     * spare; a fuel the village cannot spare enough of is skipped rather than half-loaded. Null when none qualifies.
     * <p>
     * Fuel already in the furnace decides the batch's fuel, whatever the storehouse holds more of: the task refuses a
     * fuel slot holding anything else, so a leftover half-burned stack has to be used up rather than worked around.
     */
    private static @Nullable Map.Entry<Item, Integer> chooseFuel(AbstractFurnaceBlockEntity entity, StorehouseBlockEntity storehouse,
                                                                 Map<Item, Integer> claimed, int items) {
        ItemStack fuelSlot = entity.getItem(SmeltInFurnace.SLOT_FUEL);
        if (!fuelSlot.isEmpty()) {
            Item held = fuelSlot.getItem();
            int burn = burnTime(held);
            if (burn <= 0) {
                return null;
            }
            int needed = Mth.ceil(items * (double) SmeltInFurnace.TICKS_PER_ITEM / burn);
            long spare = storehouse.count(held) - claimed.getOrDefault(held, 0);
            return spare >= Math.max(0, needed - fuelSlot.getCount()) ? Map.entry(held, needed) : null;
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
