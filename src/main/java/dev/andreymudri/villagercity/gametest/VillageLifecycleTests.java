package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageTicker;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VillageLifecycleTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    private static long countJob(List<Villager> villagers, JobType job) {
        return villagers.stream()
                .map(v -> v.getExistingData(CitizenAttachments.CITIZEN.get()).map(CitizenData::job).orElse(JobType.NONE))
                .filter(job::equals)
                .count();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_assign")
    public static void placesStorehouseAndAssignsOneLumberjackAndOneBuilder(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        List<Villager> villagers = List.of(
                GameTestSupport.spawnVillager(helper, 20, 1, 20),
                GameTestSupport.spawnVillager(helper, 28, 1, 20),
                GameTestSupport.spawnVillager(helper, 20, 1, 28));
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(village.storehousePos() != null, "no storehouse position");
        helper.assertTrue(helper.getLevel().getBlockState(village.storehousePos()).is(StorehouseContent.BLOCK.get()), "storehouse block missing");
        helper.assertTrue(countJob(villagers, JobType.LUMBERJACK) == 1, "lumberjacks " + countJob(villagers, JobType.LUMBERJACK));
        helper.assertTrue(countJob(villagers, JobType.BUILDER) == 1, "builders " + countJob(villagers, JobType.BUILDER));
        Villager lumberjack = villagers.stream()
                .filter(v -> v.getExistingData(CitizenAttachments.CITIZEN.get()).map(d -> d.job() == JobType.LUMBERJACK).orElse(false))
                .findFirst().orElseThrow();
        helper.assertTrue(lumberjack.getData(CitizenAttachments.CITIZEN).tool().is(Items.STONE_AXE), "lumberjack has no axe");
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(countJob(villagers, JobType.LUMBERJACK) == 1 && countJob(villagers, JobType.BUILDER) == 1, "second tick changed jobs");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_storehouse")
    public static void replacesMissingStorehouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        VillageTicker.tickVillage(helper.getLevel(), village);
        BlockPos first = village.storehousePos();
        helper.assertTrue(first != null, "no storehouse");
        helper.getLevel().destroyBlock(first, false);
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(village.storehousePos() != null, "storehouse not replaced");
        helper.assertTrue(helper.getLevel().getBlockState(village.storehousePos()).is(StorehouseContent.BLOCK.get()), "replacement block missing");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_skip")
    public static void skipsNitwitsBabiesAndEmployedVillagers(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager nitwit = GameTestSupport.spawnVillager(helper, 20, 1, 20);
        nitwit.setVillagerData(nitwit.getVillagerData().setProfession(VillagerProfession.NITWIT));
        Villager baby = GameTestSupport.spawnVillager(helper, 28, 1, 20);
        baby.setAge(-24000);
        Villager farmer = GameTestSupport.spawnVillager(helper, 20, 1, 28);
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        VillageTicker.tickVillage(helper.getLevel(), village);
        List<Villager> villagers = List.of(nitwit, baby, farmer);
        helper.assertTrue(countJob(villagers, JobType.LUMBERJACK) + countJob(villagers, JobType.BUILDER) == 0, "assigned an ineligible villager");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_life_refill")
    public static void refillsJobAfterCitizenDies(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager a = GameTestSupport.spawnVillager(helper, 20, 1, 20);
        Villager b = GameTestSupport.spawnVillager(helper, 28, 1, 20);
        VillageTicker.tickVillage(helper.getLevel(), village);
        Villager lumberjack = countJob(List.of(a), JobType.LUMBERJACK) == 1 ? a : b;
        lumberjack.discard();
        Villager replacement = GameTestSupport.spawnVillager(helper, 20, 1, 28);
        VillageTicker.tickVillage(helper.getLevel(), village);
        helper.assertTrue(countJob(List.of(replacement), JobType.LUMBERJACK) == 1, "lumberjack not replaced");
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }
}
