package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/** What {@link SitePrep} fills a plot with: dug earth is carried and reused before drawing on the storehouse. */
public final class FillMaterials {
    /** What is placed back into a plot, in order of preference: dirt, then cobblestone, then stone. */
    public static final List<Item> PLACEABLE = List.of(Items.DIRT, Items.COBBLESTONE, Items.STONE);

    private FillMaterials() {
    }

    /** Everything digging a plot drops; only {@link #PLACEABLE} items are placed back. */
    public static boolean isFill(ItemStack stack) {
        return stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE) || stack.is(Items.STONE)
                || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(Items.GRAVEL) || stack.is(Items.SAND);
    }

    /** The state to place next from what the container carries, in {@link #PLACEABLE} order; empty when it carries none. */
    public static Optional<BlockState> choose(Container inventory) {
        for (Item item : PLACEABLE) {
            if (Inventories.count(inventory, stack -> stack.is(item)) > 0) {
                return Optional.of(stateFor(item));
            }
        }
        return Optional.empty();
    }

    private static BlockState stateFor(Item item) {
        return ((BlockItem) item).getBlock().defaultBlockState();
    }
}
