package dev.andreymudri.villagercity.citizen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Inventories {
    private Inventories() {
    }

    public static int count(Container container, Predicate<ItemStack> filter) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && filter.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static Map<Item, Integer> counts(Container container) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** What the container lacks to cover the needed amounts. */
    public static Map<Item, Integer> missing(Map<Item, Integer> needed, Container container) {
        Map<Item, Integer> have = counts(container);
        Map<Item, Integer> missing = new LinkedHashMap<>();
        needed.forEach((item, amount) -> {
            int lack = amount - have.getOrDefault(item, 0);
            if (lack > 0) {
                missing.put(item, lack);
            }
        });
        return missing;
    }
}
