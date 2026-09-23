package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** What the village is short of, as orders for the artisan, plus the stock its other jobs have already been promised. */
public final class VillageDemand {
    /** How many torches the village keeps in stock while it employs a lamplighter. */
    public static final int TORCH_STOCK = 16;

    private VillageDemand() {
    }

    /**
     * {@code orders} are the items to produce and how many, smallest order first so a cheap one is never starved by an
     * expensive one; {@code reserved} is the storehouse stock another job is already counted on having, which the
     * planner must not spend.
     */
    public record Demand(List<Map.Entry<Item, Integer>> orders, Map<Item, Integer> reserved) {
    }

    /** The village's demand right now: the builder's outstanding materials, and torches while a lamplighter works. */
    public static Demand of(ServerLevel level, VillageData village, StorehouseBlockEntity storehouse) {
        Map<Item, Integer> needs = builderNeeds(level, village);
        Map<Item, Integer> carried = carriedByBuilders(level, village);
        Map<Item, Integer> reserved = new LinkedHashMap<>();
        Map<Item, Integer> wanted = new LinkedHashMap<>();
        needs.forEach((item, amount) -> {
            int held = (int) Math.min(amount, storehouse.count(item));
            if (held > 0) {
                reserved.put(item, held);
            }
            // A builder already carrying part of the order needs no more of it ordered: what it carries is not
            // storehouse stock, so it is not reserved, but it still counts against the shortfall.
            int stillWanted = amount - held - carried.getOrDefault(item, 0);
            if (stillWanted > 0) {
                wanted.put(item, stillWanted);
            }
        });
        if (village.jobCount(JobType.LAMPLIGHTER) > 0) {
            int held = (int) Math.min(TORCH_STOCK, storehouse.count(Items.TORCH));
            // The torches already stocked count toward the sixteen, so they are reserved: the planner subtracts spare
            // stock from each order, and without this it would subtract them a second time and stop at eight.
            if (held > 0) {
                reserved.merge(Items.TORCH, held, Integer::max);
            }
            int torches = TORCH_STOCK - held;
            if (torches > 0) {
                // The builder's own torch is already counted against the same stock, so the village orders the larger
                // of the two rather than their sum; adding them would order torches the storehouse already holds.
                wanted.merge(Items.TORCH, torches, Integer::max);
            }
        }
        List<Map.Entry<Item, Integer>> orders = wanted.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<Item, Integer>::getValue)
                        .thenComparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).toString()))
                .<Map.Entry<Item, Integer>>map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        return new Demand(orders, Map.copyOf(reserved));
    }

    /**
     * The materials the builder still has to place: those of the first plot's unfinished placements, or a whole starter
     * house while the village has a builder but no plot yet, so the artisan works ahead of the first claim. Nothing at
     * all when no builder is employed.
     */
    private static Map<Item, Integer> builderNeeds(ServerLevel level, VillageData village) {
        List<Plot> plots = village.plots();
        if (!plots.isEmpty()) {
            Plot plot = plots.get(0);
            Optional<Blueprint> blueprint = Blueprints.load(level, ResourceLocation.parse(plot.blueprint()));
            if (blueprint.isEmpty()) {
                return Map.of();
            }
            List<BlueprintPlacement> unfinished = blueprint.get().placements().stream()
                    .filter(placement -> !BuilderJob.isDone(level, plot, placement))
                    .toList();
            return Blueprint.materialsFor(unfinished);
        }
        if (village.jobCount(JobType.BUILDER) > 0) {
            return Blueprints.load(level, Blueprints.STARTER_HOUSE).map(Blueprint::requiredMaterials).orElse(Map.of());
        }
        return Map.of();
    }

    /**
     * What the village's builders are carrying right now, summed across every loaded builder's inventory. A builder
     * not currently loaded is counted as carrying nothing.
     */
    private static Map<Item, Integer> carriedByBuilders(ServerLevel level, VillageData village) {
        Map<Item, Integer> carried = new LinkedHashMap<>();
        village.citizens().forEach((citizen, job) -> {
            if (job != JobType.BUILDER || !(level.getEntity(citizen) instanceof Villager builder)) {
                return;
            }
            for (int slot = 0; slot < builder.getInventory().getContainerSize(); slot++) {
                ItemStack stack = builder.getInventory().getItem(slot);
                if (!stack.isEmpty()) {
                    carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        });
        return carried;
    }
}
