package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.LumberjackJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class LumberjackTests {
    private static final BlockPos BELL = new BlockPos(40, 1, 40);
    private static final BlockPos STORE = new BlockPos(22, 1, 24);

    /** Five-log oak with a 3x3 canopy on its top three logs and a cap. */
    static void plantTree(GameTestHelper helper, BlockPos base) {
        for (int y = 0; y < 5; y++) {
            helper.setBlock(base.above(y), Blocks.OAK_LOG);
        }
        for (int y = 2; y <= 5; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos leaf = base.offset(dx, y, dz);
                    if (!(dx == 0 && dz == 0 && y < 5)) {
                        helper.setBlock(leaf, Blocks.OAK_LEAVES);
                    }
                }
            }
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_chop", timeoutTicks = 1500)
    public static void chopsTreeStoresLogsAndReplants(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        BlockPos base = new BlockPos(26, 1, 26);
        plantTree(helper, base);
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        villager.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        CitizenData data = CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            StorehouseBlockEntity storehouse = helper.getBlockEntity(STORE);
            helper.assertTrue(storehouse.count(Items.OAK_LOG) == 5, "stored logs " + storehouse.count(Items.OAK_LOG));
            helper.assertBlockPresent(Blocks.OAK_SAPLING, base);
            helper.assertTrue(data.tool().getDamageValue() == 5, "axe damage " + data.tool().getDamageValue());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lumberjack_posts", timeoutTicks = 300)
    public static void ignoresLogsWithoutLeaves(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(26, y, 26), Blocks.OAK_LOG);
        }
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.runAfterDelay(200, () -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(26, 1, 26));
            helper.assertTrue(villager.getData(CitizenAttachments.RUNTIME).currentTask() == null, "lumberjack is working on a post");
            VillageTestSupport.remove(helper, village);
            helper.succeed();
        });
    }
}
