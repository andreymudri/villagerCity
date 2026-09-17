package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;

/**
 * Credits storehouse deposits to the player whose click moved them. Withdrawals become that player's
 * debt, and later deposits repay the debt before they earn credit, so taking items out and putting them
 * back earns nothing. Changes that do not come from a click (citizens, hoppers) credit no one.
 */
public class StorehouseMenu extends ChestMenu {
    private final StorehouseBlockEntity storehouse;

    public StorehouseMenu(int containerId, Inventory playerInventory, StorehouseBlockEntity storehouse) {
        super(MenuType.GENERIC_9x3, containerId, playerInventory, storehouse, 3);
        this.storehouse = storehouse;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        long before = storedCount();
        super.clicked(slotId, button, clickType, player);
        long delta = storedCount() - before;
        if (delta != 0 && storehouse.getLevel() instanceof ServerLevel serverLevel) {
            settle(serverLevel, player.getUUID(), delta);
        }
    }

    private long storedCount() {
        long total = 0;
        for (int i = 0; i < StorehouseBlockEntity.SIZE; i++) {
            total += storehouse.getItem(i).getCount();
        }
        return total;
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

    /** Adds items that left the storehouse outside a menu click to the player's debt with its village. */
    public static void chargeDebt(ServerLevel level, BlockPos storehousePos, UUID player, long amount) {
        VillageData village = amount > 0 ? owner(level, storehousePos) : null;
        if (village != null) {
            village.setDebt(player, village.debt(player) + amount);
            VillageRegistry.get(level).setDirty();
        }
    }
}
