package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.UUID;
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
        VillageRegistry registry = VillageRegistry.get(level);
        for (VillageData village : registry.all()) {
            if (storehouse.getBlockPos().equals(village.storehousePos())) {
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
                registry.setDirty();
                return;
            }
        }
    }
}
