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
import net.minecraft.world.item.Item;
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
        Map<Item, Integer> reserved = new LinkedHashMap<>();
        Map<Item, Integer> wanted = new LinkedHashMap<>();
        needs.forEach((item, amount) -> {
            int held = (int) Math.min(amount, storehouse.count(item));
            if (held > 0) {
                reserved.put(item, held);
            }
            if (amount > held) {
                wanted.put(item, amount - held);
            }
        });
        if (village.jobCount(JobType.LAMPLIGHTER) > 0) {
            int torches = TORCH_STOCK - (int) Math.min(TORCH_STOCK, storehouse.count(Items.TORCH));
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
}
