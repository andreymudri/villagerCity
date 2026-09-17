package dev.andreymudri.villagercity.storehouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Stores any number of each kind of item: one entry per item and data components, with a long count. Items only
 * leave through citizens and menu actions (which charge the player's debt); hoppers and item pipes may insert
 * through {@link #itemHandler()} but never extract.
 */
public class StorehouseBlockEntity extends BlockEntity implements MenuProvider {
    /** The slot count of the old slot-based storehouse, whose saved {@code Items} list is still read. */
    public static final int LEGACY_SIZE = 27;

    private final List<Stored> entries = new ArrayList<>();
    private final IItemHandler itemHandler = new InsertOnlyHandler();
    private long version;

    private static final class Stored {
        final ItemStack prototype;
        long count;

        Stored(ItemStack prototype, long count) {
            this.prototype = prototype;
            this.count = count;
        }
    }

    public StorehouseBlockEntity(BlockPos pos, BlockState state) {
        super(StorehouseContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.villagercity.storehouse");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new StorehouseMenu(containerId, inventory, this);
    }

    public IItemHandler itemHandler() {
        return itemHandler;
    }

    /** Changes whenever the contents change, so open menus know when to resend them. */
    public long version() {
        return version;
    }

    public List<StorehouseEntry> entries() {
        List<StorehouseEntry> list = new ArrayList<>(entries.size());
        for (Stored stored : entries) {
            list.add(new StorehouseEntry(stored.prototype.copy(), stored.count));
        }
        return Collections.unmodifiableList(list);
    }

    public long total() {
        long total = 0;
        for (Stored stored : entries) {
            total += stored.count;
        }
        return total;
    }

    public Map<Item, Long> counts() {
        Map<Item, Long> counts = new LinkedHashMap<>();
        for (Stored stored : entries) {
            counts.merge(stored.prototype.getItem(), stored.count, Long::sum);
        }
        return counts;
    }

    public long count(Item item) {
        long count = 0;
        for (Stored stored : entries) {
            if (stored.prototype.is(item)) {
                count += stored.count;
            }
        }
        return count;
    }

    public boolean hasAll(Map<Item, Integer> wanted) {
        return wanted.entrySet().stream().allMatch(e -> count(e.getKey()) >= e.getValue());
    }

    /** Stores the whole stack; returns an empty stack. Not credited to any player. */
    public ItemStack insertFromCitizen(ItemStack stack) {
        insert(stack, stack.getCount());
        return ItemStack.EMPTY;
    }

    /** Stores {@code amount} items like {@code prototype} (its own count is ignored). */
    public void insert(ItemStack prototype, long amount) {
        if (prototype.isEmpty() || amount <= 0) {
            return;
        }
        Stored stored = find(prototype);
        if (stored == null) {
            entries.add(new Stored(prototype.copyWithCount(1), amount));
        } else {
            stored.count += amount;
        }
        changed();
    }

    /**
     * Removes up to one stack of the item (capped at its max stack size) from a single entry, so differently named
     * or enchanted items never merge. Not credited to any player.
     */
    public ItemStack extractForCitizen(Item item, int amount) {
        for (Stored stored : entries) {
            if (stored.prototype.is(item)) {
                return extract(stored.prototype, amount);
            }
        }
        return ItemStack.EMPTY;
    }

    /** Removes up to {@code amount} items like {@code prototype}, capped at one stack. */
    public ItemStack extract(ItemStack prototype, long amount) {
        Stored stored = prototype.isEmpty() ? null : find(prototype);
        if (stored == null || amount <= 0) {
            return ItemStack.EMPTY;
        }
        int taken = (int) Math.min(Math.min(amount, stored.count), stored.prototype.getMaxStackSize());
        ItemStack stack = stored.prototype.copyWithCount(taken);
        stored.count -= taken;
        if (stored.count <= 0) {
            entries.remove(stored);
        }
        changed();
        return stack;
    }

    /** Takes everything out, as stacks of at most their max size; used when the block is broken. */
    public List<ItemStack> removeAllAsStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (Stored stored : entries) {
            int max = stored.prototype.getMaxStackSize();
            for (long left = stored.count; left > 0; left -= max) {
                stacks.add(stored.prototype.copyWithCount((int) Math.min(left, max)));
            }
        }
        entries.clear();
        changed();
        return stacks;
    }

    private @Nullable Stored find(ItemStack prototype) {
        for (Stored stored : entries) {
            if (ItemStack.isSameItemSameComponents(stored.prototype, prototype)) {
                return stored;
            }
        }
        return null;
    }

    private void changed() {
        version++;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (Stored stored : entries) {
            CompoundTag entry = new CompoundTag();
            entry.put("item", stored.prototype.save(registries));
            entry.putLong("count", stored.count);
            list.add(entry);
        }
        tag.put("Stored", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        entries.clear();
        for (Tag element : tag.getList("Stored", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            long count = entry.getLong("count");
            ItemStack.parse(registries, entry.getCompound("item")).ifPresent(prototype -> insert(prototype, count));
        }
        if (tag.contains("Items", Tag.TAG_LIST)) {
            NonNullList<ItemStack> legacy = NonNullList.withSize(LEGACY_SIZE, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, legacy, registries);
            legacy.forEach(stack -> insert(stack, stack.getCount()));
        }
        version++;
    }

    /** Every entry as a slot, plus one empty slot; accepts any stack in any slot and never gives anything back. */
    private final class InsertOnlyHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return entries.size() + 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= entries.size()) {
                return ItemStack.EMPTY;
            }
            Stored stored = entries.get(slot);
            return stored.prototype.copyWithCount((int) Math.min(stored.count, stored.prototype.getMaxStackSize()));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!simulate) {
                insert(stack, stack.getCount());
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }
}
