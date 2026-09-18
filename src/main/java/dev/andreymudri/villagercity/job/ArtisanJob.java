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
import dev.andreymudri.villagercity.craft.VillageDemand;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Crafts what the other jobs are short of. Every plan asks {@link VillageDemand} what the village needs, lets
 * {@link CraftPlanner} turn that into steps against the storehouse stock, and runs the first step as one round trip:
 * storehouse, workshop, storehouse. Nothing is remembered between plans, so the next one sees whatever the village
 * has become in the meantime.
 * <p>
 * The village does not smelt. {@link CraftPlanner} still models smelting, because it plans for a world where somebody
 * smelts; the artisan simply cannot run such a step and skips it. What it reports in its place is the item the order
 * actually wants -- glass, charcoal -- and never the raw material that item would have been smelted from, because
 * sand is of no use to a village that cannot turn it into anything.
 */
public final class ArtisanJob implements Job {
    /** How close a walk gets the artisan to the storehouse and the workshop blocks. */
    public static final double WORK_REACH = 2.5;

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
        if (storehousePos == null || !(level.getBlockEntity(storehousePos) instanceof StorehouseBlockEntity storehouse)
                || table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
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
        // The first step the artisan can actually run today, not simply the first one planned: a step it cannot do
        // must not freeze every order queued behind it. A step whose inputs the storehouse does not hold is one that
        // waits for an earlier step's output, so it is skipped with the step it depends on.
        List<Item> unsmeltable = new ArrayList<>();
        for (CraftStep planned : result.steps()) {
            CraftStep step = trimmed(planned);
            if (step.kind() == CraftStep.Kind.SMELT) {
                // The village does not smelt. What it needs is the step's own output -- glass, charcoal -- and that
                // is what it asks for: naming the sand this step would have melted sends a player to dig a beach for
                // a village that can do nothing with sand.
                if (!unsmeltable.contains(step.output())) {
                    unsmeltable.add(step.output());
                }
                continue;
            }
            if (!storehouseHolds(storehouse, demand.reserved(), step.inputs())) {
                continue;
            }
            return craft(storehousePos, table, step);
        }
        waitingFor = !unsmeltable.isEmpty() ? "materials: " + unsmeltable.stream().map(ArtisanJob::path).collect(Collectors.joining(", "))
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
     * The step cut down to the runs whose output fits a single stack, with its inputs cut in the same proportion, so
     * one trip never asks the villager's eight slots for more than they hold.
     */
    public static CraftStep trimmed(CraftStep step) {
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
