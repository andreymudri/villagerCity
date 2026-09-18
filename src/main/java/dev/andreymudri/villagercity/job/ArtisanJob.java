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
        CraftStep step = trimmed(result.steps().get(0));
        return step.kind() == CraftStep.Kind.SMELT
                ? smelt(storehousePos, storehouse, furnace, step, demand)
                : craft(storehousePos, table, step);
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

    /** As {@link #craft}, at the furnace and carrying the fuel as well; null while no fuel can be spared. */
    private @Nullable Task smelt(BlockPos storehousePos, StorehouseBlockEntity storehouse, BlockPos furnace,
                                 CraftStep step, VillageDemand.Demand demand) {
        if (step.inputs().size() != 1) {
            waitingFor = "a single smelting input";
            return null;
        }
        Map.Entry<Item, Integer> input = step.inputs().entrySet().iterator().next();
        Map<Item, Integer> claimed = new LinkedHashMap<>(demand.reserved());
        step.inputs().forEach((item, amount) -> claimed.merge(item, amount, Integer::sum));
        Map.Entry<Item, Integer> fuel = chooseFuel(storehouse, claimed, step.times());
        if (fuel == null) {
            waitingFor = "fuel for the furnace";
            return null;
        }
        Map<Item, Integer> withdrawal = new LinkedHashMap<>(step.inputs());
        withdrawal.merge(fuel.getKey(), fuel.getValue(), Integer::sum);
        return TaskSequence.of(
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Withdraw(storehousePos, withdrawal),
                MoveTo.digOut(furnace, WORK_REACH),
                new SmeltInFurnace(furnace, input.getKey(), input.getValue(), fuel.getKey(), fuel.getValue()),
                MoveTo.digOut(storehousePos, WORK_REACH),
                new Deposit(storehousePos, stack -> true));
    }

    /**
     * The spare fuel with the most stock behind it, and how much of it burning {@code items} items takes at
     * {@link SmeltInFurnace#TICKS_PER_ITEM} each. Stock another job is counted on, and the batch's own input, are not
     * spare; a fuel the village cannot spare enough of is skipped rather than half-loaded. Null when none qualifies.
     */
    private static @Nullable Map.Entry<Item, Integer> chooseFuel(StorehouseBlockEntity storehouse, Map<Item, Integer> claimed, int items) {
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
