package dev.andreymudri.villagercity.storehouse;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class StorehouseBlockEntity extends BaseContainerBlockEntity {
    public static final int SIZE = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public StorehouseBlockEntity(BlockPos pos, BlockState state) {
        super(StorehouseContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.villagercity.storehouse");
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    /**
     * Hoppers may insert but never extract: extraction outside a menu click would bypass storehouse debt.
     */
    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return false;
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new StorehouseMenu(containerId, inventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    public Map<Item, Integer> counts() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    public int count(Item item) {
        return counts().getOrDefault(item, 0);
    }

    public boolean hasAll(Map<Item, Integer> wanted) {
        Map<Item, Integer> counts = counts();
        return wanted.entrySet().stream().allMatch(e -> counts.getOrDefault(e.getKey(), 0) >= e.getValue());
    }

    /** Inserts as much as fits; returns what did not fit. Not credited to any player. */
    public ItemStack insertFromCitizen(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack rest = stack.copy();
        int before = rest.getCount();
        for (int i = 0; i < SIZE && !rest.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest)) {
                int move = Math.min(rest.getCount(), slot.getMaxStackSize() - slot.getCount());
                if (move > 0) {
                    slot.grow(move);
                    rest.shrink(move);
                }
            }
        }
        for (int i = 0; i < SIZE && !rest.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                int move = Math.min(rest.getCount(), rest.getMaxStackSize());
                items.set(i, rest.split(move));
            }
        }
        int inserted = before - rest.getCount();
        if (inserted > 0) {
            setChanged();
        }
        return rest.isEmpty() ? ItemStack.EMPTY : rest;
    }

    /**
     * Removes up to one stack of the item (capped at its max stack size), pulling only from slots whose
     * components match the first matching slot, so differently named or enchanted stacks stay apart.
     * Not credited to any player.
     */
    public ItemStack extractForCitizen(Item item, int amount) {
        ItemStack taken = ItemStack.EMPTY;
        int wanted = 0;
        for (int i = SIZE - 1; i >= 0 && (taken.isEmpty() || taken.getCount() < wanted); i--) {
            ItemStack slot = items.get(i);
            if (taken.isEmpty() && slot.is(item)) {
                wanted = Math.min(amount, slot.getMaxStackSize());
                taken = slot.split(wanted);
            } else if (!taken.isEmpty() && ItemStack.isSameItemSameComponents(slot, taken)) {
                taken.grow(slot.split(wanted - taken.getCount()).getCount());
            }
        }
        if (!taken.isEmpty()) {
            setChanged();
        }
        return taken;
    }
}
