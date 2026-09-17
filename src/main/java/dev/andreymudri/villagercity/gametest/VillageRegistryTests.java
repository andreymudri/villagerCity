package dev.andreymudri.villagercity.gametest;

import com.mojang.serialization.JsonOps;
import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageCodecs;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageDetector;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VillageRegistryTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_registry_roundtrip")
    public static void registryRoundTripsThroughNbt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 12, false);
        village.setStorehousePos(helper.absolutePos(new BlockPos(20, 1, 24)));
        village.addHouse(new BuildingRecord("villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(5, 1, 5)), new Vec3i(5, 5, 5)));
        village.addPlot(new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(30, 1, 30)), new Vec3i(5, 5, 5), UUID.randomUUID()));
        Plot unprepared = new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(38, 1, 8)), new Vec3i(5, 5, 5), null, 0L, 0, false);
        village.addPlot(unprepared);
        BlockPos pathCell = helper.absolutePos(new BlockPos(18, 2, 24));
        village.addPathCell(pathCell);
        BlockPos queuedHouse = helper.absolutePos(new BlockPos(40, 1, 40));
        village.queuePath(queuedHouse);
        BlockPos table = helper.absolutePos(new BlockPos(19, 1, 22));
        BlockPos furnace = helper.absolutePos(new BlockPos(19, 1, 26));
        village.setCraftingTablePos(table);
        village.setFurnacePos(furnace);
        village.ledger().record(UUID.randomUUID(), ContributionCategory.DEPOSIT, 42);
        BlockPos felling = helper.absolutePos(new BlockPos(10, 1, 30));
        village.startFelling(felling);
        BlockPos failedPlot = helper.absolutePos(new BlockPos(12, 1, 12));
        village.markPlotFailed(failedPlot);

        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        CompoundTag saved = registry.save(new CompoundTag(), helper.getLevel().registryAccess());
        VillageRegistry loaded = VillageRegistry.load(saved, helper.getLevel().registryAccess());
        VillageData copy = loaded.get(village.id());
        helper.assertTrue(copy != null, "village lost on reload");
        helper.assertTrue(copy.isFelling(felling), "remembered felling lost on reload");
        helper.assertTrue(copy.isFailedPlot(failedPlot), "failed plot lost on reload");
        helper.assertTrue(copy.plots().stream().anyMatch(plot -> plot.id().equals(unprepared.id()) && !plot.prepared()), "unprepared plot decoded as prepared: " + copy.plots());
        helper.assertTrue(copy.plots().stream().filter(plot -> !plot.id().equals(unprepared.id())).allMatch(Plot::prepared), "prepared plot decoded as unprepared: " + copy.plots());
        helper.assertTrue(copy.pathCells().contains(pathCell) && copy.isPathColumn(pathCell.getX(), pathCell.getZ()), "path cells lost on reload: " + copy.pathCells());
        helper.assertTrue(copy.pathQueue().contains(queuedHouse), "path queue lost on reload: " + copy.pathQueue());
        helper.assertTrue(table.equals(copy.craftingTablePos()), "crafting table lost on reload: " + copy.craftingTablePos());
        helper.assertTrue(furnace.equals(copy.furnacePos()), "furnace lost on reload: " + copy.furnacePos());
        String before = VillageCodecs.VILLAGE.encodeStart(JsonOps.INSTANCE, village).getOrThrow().toString();
        String after = VillageCodecs.VILLAGE.encodeStart(JsonOps.INSTANCE, copy).getOrThrow().toString();
        helper.assertTrue(before.equals(after), "round trip changed data:\n" + before + "\n" + after);
        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void plotSavedWithoutPreparedFlagLoadsPrepared(GameTestHelper helper) {
        Plot plot = new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(30, 1, 30)), new Vec3i(5, 5, 5), null, 0L, 0, false);
        CompoundTag tag = (CompoundTag) VillageCodecs.PLOT.encodeStart(NbtOps.INSTANCE, plot).getOrThrow();
        tag.remove("prepared");
        Plot legacy = VillageCodecs.PLOT.parse(NbtOps.INSTANCE, tag).getOrThrow();
        helper.assertTrue(legacy.prepared(), "plot saved before the prepared flag decoded as unprepared");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void addHouseQueuesAPathOnlyForVillageBlueprints(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 12);
        BlockPos built = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos vanilla = helper.absolutePos(new BlockPos(30, 1, 30));
        village.addHouse(new BuildingRecord("villagercity:blueprint/starter_house", built, new Vec3i(5, 5, 5)));
        village.addHouse(new BuildingRecord("minecraft:home", vanilla, new Vec3i(1, 1, 1)));
        helper.assertTrue(village.pathQueue().equals(List.of(built)), "path queue " + village.pathQueue());
        helper.assertTrue(village.removeQueuedPath(built) && village.pathQueue().isEmpty(), "queued path not removed: " + village.pathQueue());
        helper.assertTrue(!village.removeQueuedPath(built), "removed a path that was not queued");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_register")
    public static void detectorRegistersBellWithVillager(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        helper.setBlock(BELL, Blocks.BELL);
        GameTestSupport.spawnVillager(helper, 20, 1, 20);
        helper.succeedWhen(() -> {
            List<VillageData> registered = VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 16);
            VillageData village = VillageRegistry.get(helper.getLevel()).nearest(helper.absolutePos(BELL), 1);
            helper.assertTrue(village != null, "village not registered (scan returned " + registered.size() + ")");
            helper.assertTrue(village.radius() == VillageData.DEFAULT_RADIUS, "radius " + village.radius());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_lonely_bell")
    public static void detectorIgnoresBellWithoutVillagers(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        BlockPos bellAbs = helper.absolutePos(BELL);
        helper.getLevel().getEntitiesOfClass(Villager.class, new AABB(bellAbs).inflate(VillageDetector.VILLAGER_RADIUS)).forEach(Villager::discard);
        helper.setBlock(BELL, Blocks.BELL);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 16).isEmpty(), "registered a bell with no villagers");
            helper.assertTrue(VillageRegistry.get(helper.getLevel()).nearest(helper.absolutePos(BELL), 1) == null, "village exists");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_merge")
    public static void detectorMergesNearbyBells(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.BELL);
        helper.setBlock(new BlockPos(38, 1, 38), Blocks.BELL);
        GameTestSupport.spawnVillager(helper, 24, 1, 24);
        helper.runAfterDelay(5, () -> {
            List<VillageData> registered = VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 32);
            helper.assertTrue(registered.size() == 1, "expected one village, got " + registered.size());
            VillageTestSupport.remove(helper, registered.get(0));
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_detect_homes")
    public static void detectorAdoptsExistingHomes(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageTestSupport.removeVillagesNear(helper, BELL);
        helper.setBlock(BELL, Blocks.BELL);
        helper.setBlock(new BlockPos(10, 1, 11), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
        GameTestSupport.spawnVillager(helper, 20, 1, 20);
        helper.runAfterDelay(5, () -> {
            List<VillageData> registered = VillageDetector.scan(helper.getLevel(), helper.absolutePos(BELL), 16);
            helper.assertTrue(registered.size() == 1, "expected one village, got " + registered.size());
            helper.assertTrue(registered.get(0).houseCount() == 1, "houses " + registered.get(0).houseCount());
            VillageTestSupport.remove(helper, registered.get(0));
            helper.succeed();
        });
    }
}
