package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class StorehouseTests {
    private static final BlockPos STORE = new BlockPos(20, 1, 24);

    private static StorehouseBlockEntity place(GameTestHelper helper) {
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        return helper.getBlockEntity(STORE);
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void citizenInsertAndExtract(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        helper.assertTrue(storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 64)).isEmpty(), "first stack rejected");
        helper.assertTrue(storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 10)).isEmpty(), "second stack rejected");
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 74, "count " + storehouse.count(Items.OAK_LOG));
        helper.assertTrue(storehouse.hasAll(Map.of(Items.OAK_LOG, 74)), "hasAll false");
        helper.assertFalse(storehouse.hasAll(Map.of(Items.OAK_LOG, 75)), "hasAll true for too many");
        ItemStack taken = storehouse.extractForCitizen(Items.OAK_LOG, 70);
        helper.assertTrue(taken.getCount() == 64, "extract is capped at one stack, got " + taken.getCount());
        helper.assertTrue(storehouse.count(Items.OAK_LOG) == 10, "left " + storehouse.count(Items.OAK_LOG));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_storehouse_ledger")
    public static void playerDepositsAreCreditedCitizenOnesAreNot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 12, false);
        StorehouseBlockEntity storehouse = place(helper);
        village.setStorehousePos(helper.absolutePos(STORE));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        storehouse.startOpen(player);
        storehouse.setItem(0, new ItemStack(Items.OAK_LOG, 10));
        storehouse.insertFromCitizen(new ItemStack(Items.COBBLESTONE, 5));
        storehouse.stopOpen(player);

        long credited = village.ledger().total(player.getUUID(), ContributionCategory.DEPOSIT);
        helper.assertTrue(credited == 10, "credited " + credited);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void dropsContentsWhenBroken(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StorehouseBlockEntity storehouse = place(helper);
        storehouse.insertFromCitizen(new ItemStack(Items.OAK_LOG, 7));
        helper.getLevel().destroyBlock(helper.absolutePos(STORE), false);
        helper.succeedWhen(() -> helper.assertEntityPresent(EntityType.ITEM, STORE, 2.0));
    }
}
