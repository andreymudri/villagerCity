package dev.andreymudri.villagercity.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Plans vanilla crafting and smelting steps that turn storehouse stock into requested items, recursing into missing
 * ingredients up to {@link #MAX_DEPTH} levels. Every order shares the same simulated stock, so an earlier order can
 * use up what a later one needed.
 */
public final class CraftPlanner {
    /** How many levels of missing-ingredient recursion a single order may trigger. */
    public static final int MAX_DEPTH = 4;

    private CraftPlanner() {
    }

    /** The steps to run in order, the top-level items still missing and their shortfall, and the base items (those
     * with no recipe of their own) whose absence blocked a plan. */
    public record Result(List<CraftStep> steps, Map<Item, Integer> unmet, Set<Item> missingBase) {
    }

    /**
     * Plans {@code orders}, in the given order, against {@code stock} minus {@code reserved}. Considers vanilla
     * shaped and shapeless crafting recipes and smelting recipes; fuel for smelting is not planned here.
     */
    public static Result plan(RecipeManager recipes, HolderLookup.Provider registries, Map<Item, Long> stock,
                               List<Map.Entry<Item, Integer>> orders, Map<Item, Integer> reserved) {
        Session session = new Session(recipes, registries, stock, reserved);
        Map<Item, Integer> unmet = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> order : orders) {
            Item item = order.getKey();
            long want = order.getValue();
            long have = session.stock.getOrDefault(item, 0L);
            if (have >= want) {
                session.stock.merge(item, -want, Long::sum);
                continue;
            }
            if (session.ensureAvailable(item, want, 0)) {
                session.stock.merge(item, -want, Long::sum);
            } else {
                unmet.put(item, (int) (want - have));
            }
        }
        return new Result(List.copyOf(session.steps), unmet, Set.copyOf(session.missingBase));
    }

    /** Mutable state for one {@link #plan} call: simulated stock, the steps built so far, and the base items found
     * to have no recipe. */
    private static final class Session {
        private final RecipeManager recipes;
        private final HolderLookup.Provider registries;
        private final Map<Item, Long> stock = new LinkedHashMap<>();
        private final List<CraftStep> steps = new ArrayList<>();
        private final Set<Item> missingBase = new LinkedHashSet<>();

        Session(RecipeManager recipes, HolderLookup.Provider registries, Map<Item, Long> baseStock, Map<Item, Integer> reserved) {
            this.recipes = recipes;
            this.registries = registries;
            for (Map.Entry<Item, Long> entry : baseStock.entrySet()) {
                long reservedAmount = reserved.getOrDefault(entry.getKey(), 0);
                stock.put(entry.getKey(), Math.max(0L, entry.getValue() - reservedAmount));
            }
        }

        /**
         * Ensures at least {@code need} of {@code item} are in the simulated stock, crafting or smelting the
         * shortfall when a recipe can. Leaves the stock and steps exactly as they were on failure. A failed
         * candidate can still add base items (those with no recipe) to {@link #missingBase}; that is only undone,
         * back to what it was before any candidate here was tried, once some candidate succeeds — a later success
         * means nothing the earlier candidates were missing is actually blocking this item.
         */
        boolean ensureAvailable(Item item, long need, int depth) {
            long have = stock.getOrDefault(item, 0L);
            if (have >= need) {
                return true;
            }
            if (depth >= MAX_DEPTH) {
                return false;
            }
            long shortfall = need - have;
            List<RecipeHolder<?>> recipesFor = findRecipesFor(item);
            if (recipesFor.isEmpty()) {
                missingBase.add(item);
                return false;
            }
            List<RecipeHolder<?>> candidates = recipesFor.stream().filter(holder -> !hasRemainderIngredient(holder.value())).toList();
            if (candidates.isEmpty()) {
                // item has a recipe, just none we can use (every one leaves a crafting remainder we don't account
                // for): report it through unmet, not missingBase, which names only items with no recipe at all.
                return false;
            }
            Set<Item> missingBaseAtEntry = new LinkedHashSet<>(missingBase);
            for (RecipeHolder<?> candidate : candidates) {
                Map<Item, Long> stockSnapshot = new LinkedHashMap<>(stock);
                int mark = steps.size();
                if (tryRecipe(candidate, item, shortfall, depth)) {
                    missingBase.clear();
                    missingBase.addAll(missingBaseAtEntry);
                    return true;
                }
                stock.clear();
                stock.putAll(stockSnapshot);
                while (steps.size() > mark) {
                    steps.remove(steps.size() - 1);
                }
            }
            return false;
        }

        /**
         * Shaped, shapeless and smelting recipes that produce {@code item}, sorted by recipe id. Skips every other
         * kind of crafting recipe, but not recipes that use an item with a crafting remainder: whether {@code item}
         * has a recipe at all (the {@link #missingBase} question) does not depend on whether the planner can use
         * it (the {@link #hasRemainderIngredient} question); callers filter the remainder ones out themselves.
         */
        private List<RecipeHolder<?>> findRecipesFor(Item item) {
            List<RecipeHolder<?>> recipesFor = new ArrayList<>();
            for (RecipeHolder<?> holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
                Recipe<?> recipe = holder.value();
                if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
                    continue;
                }
                if (recipe.getResultItem(registries).is(item)) {
                    recipesFor.add(holder);
                }
            }
            for (RecipeHolder<?> holder : recipes.getAllRecipesFor(RecipeType.SMELTING)) {
                Recipe<?> recipe = holder.value();
                if (recipe.getResultItem(registries).is(item)) {
                    recipesFor.add(holder);
                }
            }
            recipesFor.sort(Comparator.comparing(holder -> holder.id().toString()));
            return recipesFor;
        }

        private static boolean hasRemainderIngredient(Recipe<?> recipe) {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack.getItem().hasCraftingRemainingItem()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Applies {@code holder} {@code ceil(shortfall / resultCount)} times, resolving every non-empty ingredient
         * slot first. On success, appends one {@link CraftStep} after any sub-steps its ingredients needed, and
         * adds the produced amount to the simulated stock.
         */
        private boolean tryRecipe(RecipeHolder<?> holder, Item target, long shortfall, int depth) {
            Recipe<?> recipe = holder.value();
            int resultCount = Math.max(1, recipe.getResultItem(registries).getCount());
            int times = (int) Math.ceil((double) shortfall / resultCount);
            Map<Item, Integer> inputs = new LinkedHashMap<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                Item chosen = resolveSlot(ingredient, times, depth + 1);
                if (chosen == null) {
                    return false;
                }
                inputs.merge(chosen, times, Integer::sum);
            }
            CraftStep.Kind kind = recipe.getType() == RecipeType.SMELTING ? CraftStep.Kind.SMELT : CraftStep.Kind.CRAFT;
            steps.add(new CraftStep(kind, holder, inputs, target, times * resultCount, times));
            stock.merge(target, (long) times * resultCount, Long::sum);
            return true;
        }

        /**
         * Picks the item in {@code ingredient.getItems()} with the highest simulated stock that can supply
         * {@code times} units (crafting the shortfall if needed), trying the next one, in the ingredient's own
         * order, when it cannot. Consumes {@code times} of the chosen item from stock. Returns {@code null}, with
         * stock and steps unchanged, when no candidate item resolves; on success, undoes any {@link #missingBase}
         * additions a rejected candidate item left behind, back to what it was before this slot was tried.
         */
        private Item resolveSlot(Ingredient ingredient, int times, int depth) {
            List<Item> options = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                Item candidate = stack.getItem();
                if (!options.contains(candidate)) {
                    options.add(candidate);
                }
            }
            options.sort(Comparator.comparingLong((Item candidate) -> stock.getOrDefault(candidate, 0L)).reversed());
            Set<Item> missingBaseAtEntry = new LinkedHashSet<>(missingBase);
            for (Item candidate : options) {
                Map<Item, Long> stockSnapshot = new LinkedHashMap<>(stock);
                int mark = steps.size();
                if (ensureAvailable(candidate, times, depth)) {
                    stock.merge(candidate, -(long) times, Long::sum);
                    missingBase.clear();
                    missingBase.addAll(missingBaseAtEntry);
                    return candidate;
                }
                stock.clear();
                stock.putAll(stockSnapshot);
                while (steps.size() > mark) {
                    steps.remove(steps.size() - 1);
                }
            }
            return null;
        }
    }
}
