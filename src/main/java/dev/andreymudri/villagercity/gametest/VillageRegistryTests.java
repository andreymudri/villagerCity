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
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
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
        village.ledger().record(UUID.randomUUID(), ContributionCategory.DEPOSIT, 42);

        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        CompoundTag saved = registry.save(new CompoundTag(), helper.getLevel().registryAccess());
        VillageRegistry loaded = VillageRegistry.load(saved, helper.getLevel().registryAccess());
        VillageData copy = loaded.get(village.id());
        helper.assertTrue(copy != null, "village lost on reload");
        String before = VillageCodecs.VILLAGE.encodeStart(JsonOps.INSTANCE, village).getOrThrow().toString();
        String after = VillageCodecs.VILLAGE.encodeStart(JsonOps.INSTANCE, copy).getOrThrow().toString();
        helper.assertTrue(before.equals(after), "round trip changed data:\n" + before + "\n" + after);
        VillageTestSupport.remove(helper, village);
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
