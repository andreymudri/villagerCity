package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The storehouse screen's menu: only the player's 36 inventory slots are real slots; the stored entries are sent to
 * the viewer ({@link StorehouseContentsPayload}) and taken or filled through {@link #act}. Credits deposits to the
 * player whose click or action moved them. Withdrawals become that player's debt, and later deposits repay the debt
 * before they earn credit, so taking items out and putting them back earns nothing. Changes that do not come from
 * the player (citizens, hoppers) credit no one.
 */
public class StorehouseMenu extends AbstractContainerMenu {
    /** Where the entry grid starts and how it is laid out, matching a six-row chest. */
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    public static final int COLUMNS = 9;
    public static final int ROWS = 6;
    /** Player inventory slots: main inventory at menu slots 0-26, hotbar at 27-35. */
    public static final int HOTBAR_START = 27;

    public enum Button {
        LEFT, RIGHT, SHIFT
    }

    private final @Nullable StorehouseBlockEntity storehouse;
    private final Player player;
    private long sentVersion = -1;
    /** On the client: the entries last received from the server. */
    private List<StorehouseEntry> clientEntries = List.of();

    /** The client side, whose entries arrive by payload. */
    public StorehouseMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    public StorehouseMenu(int containerId, Inventory playerInventory, @Nullable StorehouseBlockEntity storehouse) {
        super(StorehouseContent.MENU.get(), containerId);
        this.storehouse = storehouse;
        this.player = playerInventory.player;
        int inventoryY = GRID_Y + ROWS * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + column, GRID_X + column * 18, inventoryY + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, GRID_X + column * 18, inventoryY + 58));
        }
    }

    public List<StorehouseEntry> clientEntries() {
        return clientEntries;
    }

    public void setClientEntries(List<StorehouseEntry> entries) {
        this.clientEntries = List.copyOf(entries);
    }

    @Override
    public boolean stillValid(Player player) {
        return storehouse == null
                || !storehouse.isRemoved() && player.canInteractWithBlock(storehouse.getBlockPos(), 4.0);
    }

    /** Shift-click on an inventory slot stores the whole stack. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (storehouse == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        storehouse.insert(stack, stack.getCount());
        slot.set(ItemStack.EMPTY);
        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        settling(player, () -> super.clicked(slotId, button, clickType, player));
    }

    /**
     * A click on the entry grid. With an empty cursor, LEFT takes a stack of {@code prototype} to the cursor, RIGHT
     * half a stack, and SHIFT as many as fit into the inventory. With items on the cursor, LEFT stores them all and
     * RIGHT one of them.
     */
    public void act(Player player, ItemStack prototype, Button button) {
        if (storehouse == null) {
            return;
        }
        settling(player, () -> {
            ItemStack carried = getCarried();
            if (button == Button.SHIFT) {
                takeIntoInventory(player, prototype);
            } else if (!carried.isEmpty()) {
                int amount = button == Button.LEFT ? carried.getCount() : 1;
                storehouse.insert(carried, amount);
                carried.shrink(amount);
                setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
            } else {
                int stack = prototype.getMaxStackSize();
                setCarried(storehouse.extract(prototype, button == Button.LEFT ? stack : (stack + 1) / 2));
            }
        });
    }

    private void takeIntoInventory(Player player, ItemStack prototype) {
        while (true) {
            ItemStack taken = storehouse.extract(prototype, prototype.getMaxStackSize());
            if (taken.isEmpty()) {
                return;
            }
            player.getInventory().add(taken);
            if (!taken.isEmpty()) {
                storehouse.insert(taken, taken.getCount());
                return;
            }
        }
    }

    /** Runs a player-driven change and settles the change in stored items with that player. */
    private void settling(Player player, Runnable change) {
        if (storehouse == null) {
            change.run();
            return;
        }
        long before = storehouse.total();
        change.run();
        long delta = storehouse.total() - before;
        if (delta != 0 && storehouse.getLevel() instanceof ServerLevel serverLevel) {
            settle(serverLevel, player.getUUID(), delta);
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        sendContentsIfChanged();
    }

    @Override
    public void sendAllDataToRemote() {
        super.sendAllDataToRemote();
        sentVersion = -1;
        sendContentsIfChanged();
    }

    private void sendContentsIfChanged() {
        if (storehouse != null && player instanceof ServerPlayer serverPlayer && serverPlayer.connection != null
                && storehouse.version() != sentVersion) {
            sentVersion = storehouse.version();
            PacketDistributor.sendToPlayer(serverPlayer, new StorehouseContentsPayload(containerId, storehouse.entries()));
        }
    }

    private void settle(ServerLevel level, UUID player, long delta) {
        VillageData village = owner(level, storehouse.getBlockPos());
        if (village == null) {
            return;
        }
        long debt = village.debt(player);
        if (delta < 0) {
            village.setDebt(player, debt - delta);
        } else {
            long repaid = Math.min(debt, delta);
            village.setDebt(player, debt - repaid);
            if (delta > repaid) {
                village.ledger().record(player, ContributionCategory.DEPOSIT, delta - repaid);
            }
        }
        VillageRegistry.get(level).setDirty();
    }

    /** The village whose storehouse is at the given position, or null when it belongs to none. */
    public static @Nullable VillageData owner(ServerLevel level, BlockPos storehousePos) {
        for (VillageData village : VillageRegistry.get(level).all()) {
            if (storehousePos.equals(village.storehousePos())) {
                return village;
            }
        }
        return null;
    }

    /** Adds items that left the storehouse outside a menu action to the player's debt with its village. */
    public static void chargeDebt(ServerLevel level, BlockPos storehousePos, UUID player, long amount) {
        VillageData village = amount > 0 ? owner(level, storehousePos) : null;
        if (village != null) {
            village.setDebt(player, village.debt(player) + amount);
            VillageRegistry.get(level).setDirty();
        }
    }
}
