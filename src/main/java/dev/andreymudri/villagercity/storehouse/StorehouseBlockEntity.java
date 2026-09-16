package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class StorehouseBlockEntity extends BaseContainerBlockEntity {
    public static final int SIZE = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final Map<UUID, Map<Item, Integer>> openSnapshots = new HashMap<>();

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

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
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

    @Override
    public void startOpen(Player player) {
        if (level != null && !level.isClientSide && !player.isSpectator()) {
            openSnapshots.put(player.getUUID(), counts());
        }
    }

    @Override
    public void stopOpen(Player player) {
        Map<Item, Integer> before = openSnapshots.remove(player.getUUID());
        if (before == null || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Map<Item, Integer> after = counts();
        Set<Item> seen = new HashSet<>(before.keySet());
        seen.addAll(after.keySet());
        long deposited = 0;
        for (Item item : seen) {
            deposited += Math.max(0, after.getOrDefault(item, 0) - before.getOrDefault(item, 0));
        }
        if (deposited <= 0) {
            return;
        }
        VillageRegistry registry = VillageRegistry.get(serverLevel);
        for (VillageData village : registry.all()) {
            if (worldPosition.equals(village.storehousePos())) {
                village.ledger().record(player.getUUID(), ContributionCategory.DEPOSIT, deposited);
                registry.setDirty();
                return;
            }
        }
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
            adjustSnapshots(stack.getItem(), inserted);
            setChanged();
        }
        return rest.isEmpty() ? ItemStack.EMPTY : rest;
    }

    /** Removes up to one stack of the item (capped at its max stack size). Not credited to any player. */
    public ItemStack extractForCitizen(Item item, int amount) {
        int wanted = Math.min(amount, new ItemStack(item).getMaxStackSize());
        ItemStack taken = new ItemStack(item, 0);
        for (int i = SIZE - 1; i >= 0 && taken.getCount() < wanted; i--) {
            ItemStack slot = items.get(i);
            if (slot.is(item)) {
                ItemStack part = slot.split(wanted - taken.getCount());
                if (taken.isEmpty()) {
                    taken = part;
                } else {
                    taken.grow(part.getCount());
                }
            }
        }
        if (!taken.isEmpty()) {
            adjustSnapshots(item, -taken.getCount());
            setChanged();
        }
        return taken.isEmpty() ? ItemStack.EMPTY : taken;
    }

    private void adjustSnapshots(Item item, int delta) {
        for (Map<Item, Integer> snapshot : openSnapshots.values()) {
            snapshot.merge(item, delta, Integer::sum);
        }
    }
}
