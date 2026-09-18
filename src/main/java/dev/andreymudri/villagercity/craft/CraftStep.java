package dev.andreymudri.villagercity.craft;

import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;

/** One batch of work: craft {@code recipe} {@code times} times, or smelt {@code times} of {@code input}. */
public record CraftStep(Kind kind, RecipeHolder<?> recipe, Map<Item, Integer> inputs, Item output, int outputCount, int times) {
    /** Whether {@link #recipe} is run on a crafting table or in a furnace. */
    public enum Kind {
        CRAFT,
        SMELT
    }
}
