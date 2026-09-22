package dev.andreymudri.villagercity.gametest;

import com.mojang.serialization.JsonOps;
import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.command.VillageCommand;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.ContributionCategory;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageCodecs;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageDetector;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.VillageWorks;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
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
        village.setCraftingTablePos(table);
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
    public static void plotCopiesKeepThePreparedFlag(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 12);
        UUID plotId = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        village.addPlot(new Plot(plotId, "villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(30, 1, 30)), new Vec3i(5, 5, 5), null, 0L, 0, false));
        village.assignPlot(plotId, builder);
        helper.assertTrue(!village.plots().get(0).prepared(), "assignPlot prepared the plot: " + village.plots());
        village.releasePlot(plotId, 100L, true);
        helper.assertTrue(!village.plots().get(0).prepared(), "releasePlot prepared the plot: " + village.plots());
        village.assignPlot(plotId, builder);
        helper.assertTrue(!village.plots().get(0).prepared(), "second assignPlot prepared the plot: " + village.plots());
        helper.assertTrue(village.releasePlotsBuiltBy(builder), "releasePlotsBuiltBy released nothing: " + village.plots());
        helper.assertTrue(!village.plots().get(0).prepared(), "releasePlotsBuiltBy prepared the plot: " + village.plots());
        village.markPlotPrepared(plotId);
        helper.assertTrue(village.plots().get(0).prepared(), "markPlotPrepared left the plot unprepared: " + village.plots());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void workshopBlocksCountAsOccupied(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 12);
        BlockPos table = helper.absolutePos(new BlockPos(19, 1, 22));
        Footprint tableFootprint = Footprint.of(table, new Vec3i(1, 1, 1));
        helper.assertTrue(!village.occupiedFootprints().contains(tableFootprint),
                "workshop footprint occupied before the workshop was set: " + village.occupiedFootprints());
        village.setCraftingTablePos(table);
        helper.assertTrue(village.occupiedFootprints().contains(tableFootprint), "crafting table not occupied: " + village.occupiedFootprints());
        village.setCraftingTablePos(null);
        helper.assertTrue(!village.occupiedFootprints().contains(tableFootprint),
                "workshop footprint still occupied after clearing the workshop: " + village.occupiedFootprints());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aFinishedHouseQueuesNoPath(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 12);
        BlockPos built = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos vanilla = helper.absolutePos(new BlockPos(30, 1, 30));
        village.addHouse(new BuildingRecord("villagercity:blueprint/starter_house", built, new Vec3i(5, 5, 5)));
        village.addHouse(new BuildingRecord("minecraft:home", vanilla, new Vec3i(1, 1, 1)));
        helper.assertTrue(village.pathQueue().isEmpty(), "a finished house queued a path: " + village.pathQueue());
        // A queue an older save still holds can be drained.
        village.queuePath(built);
        helper.assertTrue(village.removeQueuedPath(built) && village.pathQueue().isEmpty(), "queued path not removed: " + village.pathQueue());
        helper.assertTrue(!village.removeQueuedPath(built), "removed a path that was not queued");
        helper.succeed();
    }

    /** Three street cells, two in a run at hop 1 and one grown from its end at hop 2, eastward from the bell. */
    private static VillageData villageWithThreeStreetCells(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 12);
        village.addStreetCell(helper.absolutePos(new BlockPos(26, 2, 24)), 1);
        village.addStreetCell(helper.absolutePos(new BlockPos(27, 2, 24)), 1);
        village.addStreetCell(helper.absolutePos(new BlockPos(28, 3, 24)), 2);
        return village;
    }

    private static VillageData roundTrip(VillageData village) {
        CompoundTag saved = (CompoundTag) VillageCodecs.VILLAGE.encodeStart(NbtOps.INSTANCE, village).getOrThrow();
        return VillageCodecs.VILLAGE.parse(NbtOps.INSTANCE, saved).getOrThrow();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void streetCellsRoundTripWithTheirHops(GameTestHelper helper) {
        VillageData village = villageWithThreeStreetCells(helper);
        List<StreetCell> expected = List.of(
                new StreetCell(helper.absolutePos(new BlockPos(26, 2, 24)), 1),
                new StreetCell(helper.absolutePos(new BlockPos(27, 2, 24)), 1),
                new StreetCell(helper.absolutePos(new BlockPos(28, 3, 24)), 2));
        helper.assertTrue(village.streets().equals(expected), "street cells recorded wrong: " + village.streets());
        VillageData copy = roundTrip(village);
        helper.assertTrue(copy.streets().equals(expected), "street cells or hops lost on reload: " + copy.streets());
        helper.assertTrue(expected.stream().allMatch(cell -> copy.pathCells().contains(cell.pos())),
                "street cells are not path cells after reload: " + copy.pathCells());
        helper.assertTrue(copy.deepestHops() == 2, "deepest hops after reload: " + copy.deepestHops());
        helper.succeed();
    }

    /**
     * Two runs grow east from the bell (hop 1, then hop 2 from its end) and one grows west (hop 1): the frontier is the
     * last cell of the eastern hop-2 run and the last cell of the western run, and nothing else.
     */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void streetEndsAreOnlyTheFrontier(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 12);
        for (int x = 25; x <= 27; x++) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, 2, 24)), 1);
        }
        for (int x = 28; x <= 30; x++) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, 2 + x - 27, 24)), 2);
        }
        for (int x = 23; x >= 21; x--) {
            village.addStreetCell(helper.absolutePos(new BlockPos(x, 2, 24)), 1);
        }
        List<StreetCell> expected = List.of(
                new StreetCell(helper.absolutePos(new BlockPos(30, 5, 24)), 2),
                new StreetCell(helper.absolutePos(new BlockPos(21, 2, 24)), 1));
        helper.assertTrue(village.streetEnds().equals(expected), "street ends " + village.streetEnds() + ", expected " + expected);
        helper.assertTrue(village.deepestHops() == 2, "deepest hops " + village.deepestHops());
        helper.assertTrue(roundTrip(village).streetEnds().equals(expected), "street ends changed on reload");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aSaveWithoutStreetsLoadsWithAnEmptyGraph(GameTestHelper helper) {
        VillageData village = villageWithThreeStreetCells(helper);
        village.recordOpenedDoor(helper.absolutePos(new BlockPos(5, 2, 5)));
        CompoundTag saved = (CompoundTag) VillageCodecs.VILLAGE.encodeStart(NbtOps.INSTANCE, village).getOrThrow();
        // Exactly what a save from before the street graph holds: no streets in the works, no opened doors.
        saved.getCompound("works").remove("streets");
        saved.remove("opened_doors");
        helper.assertTrue(saved.getCompound("works").contains("path_cells"), "the old save lost its path cells: " + saved);

        VillageData old = VillageCodecs.VILLAGE.parse(NbtOps.INSTANCE, saved).getOrThrow();
        helper.assertTrue(old.streets().isEmpty() && old.streetEnds().isEmpty() && old.deepestHops() == 0,
                "an old save loaded with a street graph: " + old.streets());
        helper.assertTrue(old.openedDoors().isEmpty(), "an old save loaded with opened doors: " + old.openedDoors());
        helper.assertTrue(old.pathCells().size() == 3, "an old save lost its path cells: " + old.pathCells());
        VillageWorks works = VillageWorks.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        helper.assertTrue(works.streets().isEmpty(), "an empty works tag loaded with streets: " + works);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void aRecordedOpenedDoorSurvivesAReload(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 12);
        BlockPos door = helper.absolutePos(new BlockPos(5, 2, 5));
        village.recordOpenedDoor(door);
        VillageData copy = roundTrip(village);
        helper.assertTrue(copy.openedDoors().equals(List.of(door)), "opened door lost on reload: " + copy.openedDoors());
        helper.assertTrue(copy.clearOpenedDoor(door) && copy.openedDoors().isEmpty(), "opened door not cleared: " + copy.openedDoors());
        helper.assertTrue(!copy.clearOpenedDoor(door), "cleared a door that was not recorded");
        helper.assertTrue(roundTrip(copy).openedDoors().isEmpty(), "a cleared door came back on reload");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void theVillageCommandCountsTheStreets(GameTestHelper helper) {
        VillageData village = villageWithThreeStreetCells(helper);
        String text = String.join("\n", VillageCommand.describe(helper.getLevel(), village));
        helper.assertTrue(text.contains("streets: 3 cells, 1 ends, deepest 2 hops"), "missing streets:\n" + text);
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
