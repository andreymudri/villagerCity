package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.craft.CraftPlanner;
import dev.andreymudri.villagercity.craft.CraftStep;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class CraftPlannerTests {
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void oakDoorFromLogs(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of(Items.OAK_LOG, 10L);
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.OAK_DOOR, 1));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, Map.of());

        helper.assertTrue(result.unmet().isEmpty(), "unmet " + result.unmet());
        helper.assertTrue(result.missingBase().isEmpty(), "met order should not name a missing base, got " + result.missingBase());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.output() == Items.OAK_PLANKS), "no planks step in " + result.steps());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.output() == Items.OAK_DOOR), "no door step in " + result.steps());
        Map<Item, Long> finalStock = replay(helper, stock, result.steps());
        helper.assertTrue(finalStock.getOrDefault(Items.OAK_DOOR, 0L) >= 1, "door not produced, final stock " + finalStock);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void torchesFromLogsViaCharcoal(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of(Items.OAK_LOG, 10L);
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.TORCH, 4));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, Map.of());

        helper.assertTrue(result.unmet().isEmpty(), "unmet " + result.unmet());
        helper.assertTrue(result.missingBase().isEmpty(), "met order should not name a missing base, got " + result.missingBase());
        helper.assertTrue(
                result.steps().stream().anyMatch(s -> s.kind() == CraftStep.Kind.SMELT && s.output() == Items.CHARCOAL),
                "no charcoal smelt step in " + result.steps());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.output() == Items.STICK), "no stick step in " + result.steps());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.output() == Items.TORCH), "no torch step in " + result.steps());
        Map<Item, Long> finalStock = replay(helper, stock, result.steps());
        helper.assertTrue(finalStock.getOrDefault(Items.TORCH, 0L) >= 4, "torches not produced, final stock " + finalStock);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void bedFromWhiteWoolAndDye(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of(Items.WHITE_WOOL, 3L, Items.RED_DYE, 3L, Items.OAK_PLANKS, 3L);
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.RED_BED, 1));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, Map.of());

        helper.assertTrue(result.unmet().isEmpty(), "unmet " + result.unmet());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.output() == Items.WHITE_BED), "no white bed step in " + result.steps());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.output() == Items.RED_BED), "no red bed step in " + result.steps());
        Map<Item, Long> finalStock = replay(helper, stock, result.steps());
        helper.assertTrue(finalStock.getOrDefault(Items.RED_BED, 0L) >= 1, "red bed not produced, final stock " + finalStock);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void glassFromSand(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of(Items.SAND, 4L);
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.GLASS, 2));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, Map.of());

        helper.assertTrue(result.unmet().isEmpty(), "unmet " + result.unmet());
        helper.assertTrue(result.missingBase().isEmpty(), "met order should not name a missing base, got " + result.missingBase());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.kind() == CraftStep.Kind.SMELT && s.output() == Items.GLASS),
                "no glass smelt step in " + result.steps());
        Map<Item, Long> finalStock = replay(helper, stock, result.steps());
        helper.assertTrue(finalStock.getOrDefault(Items.GLASS, 0L) >= 2, "glass not produced, final stock " + finalStock);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void twoOrdersDoNotShareLogs(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of(Items.OAK_LOG, 1L);
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.OAK_PLANKS, 4), Map.entry(Items.STICK, 1));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, Map.of());

        helper.assertFalse(result.unmet().containsKey(Items.OAK_PLANKS), "planks order should have been met, unmet " + result.unmet());
        helper.assertTrue(result.unmet().containsKey(Items.STICK), "stick order should be unmet, unmet " + result.unmet());
        replay(helper, stock, result.steps());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void reservedItemsAreNotUsed(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of(Items.OAK_PLANKS, 60L, Items.OAK_LOG, 5L);
        Map<Item, Integer> reserved = Map.of(Items.OAK_PLANKS, 57);
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.OAK_DOOR, 1));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, reserved);

        helper.assertTrue(result.unmet().isEmpty(), "unmet " + result.unmet());
        helper.assertTrue(result.steps().stream().anyMatch(s -> s.output() == Items.OAK_PLANKS),
                "reserved planks should have forced a planks step, got " + result.steps());
        Map<Item, Long> finalStock = replay(helper, stock, result.steps());
        helper.assertTrue(finalStock.getOrDefault(Items.OAK_DOOR, 0L) >= 1, "door not produced, final stock " + finalStock);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void recipesWithACraftingRemainderAreSkipped(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of(Items.MILK_BUCKET, 3L, Items.SUGAR, 2L, Items.EGG, 1L, Items.WHEAT, 3L);
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.CAKE, 1));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, Map.of());

        // The only vanilla cake recipe uses 3 milk buckets, which leave a crafting remainder (an empty bucket) the
        // planner does not account for, so it must be skipped entirely rather than planned as if the buckets were
        // consumed for free.
        helper.assertTrue(result.unmet().containsKey(Items.CAKE), "cake should be unmet, unmet " + result.unmet());
        helper.assertTrue(result.steps().isEmpty(), "no steps should be planned for a remainder-only recipe, got " + result.steps());
        // Cake has a recipe; it was rejected for its crafting remainder, not because cake is a base material with
        // no recipe at all, so it must not show up in missingBase (the artisan would otherwise tell the player to
        // go fetch cake).
        helper.assertTrue(result.missingBase().isEmpty(), "cake has a recipe, so it should not be named as missing base, got " + result.missingBase());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void anUnmeetableOrderNamesItsBase(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Map<Item, Long> stock = Map.of();
        List<Map.Entry<Item, Integer>> orders = List.of(Map.entry(Items.GLASS, 1));

        CraftPlanner.Result result = CraftPlanner.plan(recipes, registries, stock, orders, Map.of());

        helper.assertTrue(result.unmet().containsKey(Items.GLASS), "glass should be unmet, unmet " + result.unmet());
        helper.assertTrue(result.missingBase().contains(Items.SAND), "missing base should name sand, got " + result.missingBase());
        helper.succeed();
    }

    /**
     * Replays {@code steps} over a copy of {@code initialStock}, failing the test the moment a step's inputs are
     * not covered by what came before it, and returns the stock left after every step ran.
     */
    private static Map<Item, Long> replay(GameTestHelper helper, Map<Item, Long> initialStock, List<CraftStep> steps) {
        Map<Item, Long> simulated = new HashMap<>(initialStock);
        for (CraftStep step : steps) {
            for (Map.Entry<Item, Integer> input : step.inputs().entrySet()) {
                long have = simulated.getOrDefault(input.getKey(), 0L);
                helper.assertTrue(have >= input.getValue(), "step " + step + " needs " + input + " but stock only has " + have);
                simulated.put(input.getKey(), have - input.getValue());
            }
            simulated.merge(step.output(), (long) step.outputCount(), Long::sum);
        }
        return simulated;
    }
}
