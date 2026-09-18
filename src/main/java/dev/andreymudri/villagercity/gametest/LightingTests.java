package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.LamplighterJob;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The lamplighter lights village ground until no column is dark enough for monsters to spawn, and the starter house
 * carries its own torch. The tests that scan the ground use {@code skyAccess}, so the test area has no barrier ceiling
 * for the heightmap to land on.
 */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class LightingTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    private static final int RADIUS = 12;
    private static final int TIMEOUT = 4000;
    private static final int NIGHT = 18000;
    private static final int PATH_X = 28;

    /** Ground columns within the radius, outside the village's own footprints, where a zombie could spawn in the dark. */
    private static List<BlockPos> darkColumns(GameTestHelper helper, VillageData village) {
        ServerLevel level = helper.getLevel();
        BlockPos center = village.center();
        List<Footprint> occupied = village.occupiedFootprints();
        List<BlockPos> dark = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (dx * dx + dz * dz > RADIUS * RADIUS || occupied.stream().anyMatch(f -> f.contains(x, z))) {
                    continue;
                }
                BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, center.getY(), z));
                if (level.getBlockState(feet.below()).isValidSpawn(level, feet.below(), EntityType.ZOMBIE)
                        && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                        && level.getBrightness(LightLayer.BLOCK, feet) == 0) {
                    dark.add(feet.subtract(helper.absolutePos(BlockPos.ZERO)));
                }
            }
        }
        return dark;
    }

    private static int torchesAround(GameTestHelper helper) {
        int torches = 0;
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                for (int y = 1; y <= 3; y++) {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.TORCH)) {
                        torches++;
                    }
                }
            }
        }
        return torches;
    }

    /** Night falls; the villager works through it because its rest schedule is cleared (a resting citizen yields). */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lighting_dark_village", timeoutTicks = TIMEOUT, skyAccess = true)
    public static void lightsADarkVillageUntilNothingCanSpawn(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.getLevel().setDayTime(NIGHT);
        helper.runAtTickTime(TIMEOUT - 2, () -> helper.getLevel().setDayTime(GameTestSupport.DAY_TIME));
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of(Items.TORCH, 64));
        helper.assertFalse(darkColumns(helper, village).isEmpty(), "the village starts lit");
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        villager.getBrain().setSchedule(Schedule.EMPTY);
        LamplighterJob job = new LamplighterJob();
        CitizenTestSupport.enroll(villager, village, JobType.LAMPLIGHTER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            List<BlockPos> dark = darkColumns(helper, village);
            helper.assertTrue(dark.isEmpty(), dark.size() + " dark columns, first " + (dark.isEmpty() ? "" : dark.get(0)));
            helper.assertTrue("the village is lit".equals(job.waitingFor()), "waiting for " + job.waitingFor());
            helper.getLevel().setDayTime(GameTestSupport.DAY_TIME);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lighting_paths", timeoutTicks = TIMEOUT, skyAccess = true)
    public static void neverPlacesATorchOnAPath(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of(Items.TORCH, 64));
        for (int z = BELL.getZ() - RADIUS; z <= BELL.getZ() + RADIUS; z++) {
            village.addPathCell(helper.absolutePos(new BlockPos(PATH_X, 1, z)));
        }
        // The villager starts on the path, so the nearest dark spot is a path column.
        Villager villager = GameTestSupport.spawnVillager(helper, PATH_X, 1, 14);
        CitizenTestSupport.enroll(villager, village, JobType.LAMPLIGHTER, ItemStack.EMPTY, new LamplighterJob());
        helper.succeedWhen(() -> {
            for (int z = BELL.getZ() - RADIUS; z <= BELL.getZ() + RADIUS; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.assertFalse(helper.getBlockState(new BlockPos(PATH_X, y, z)).is(Blocks.TORCH), "torch on the path at " + PATH_X + " " + y + " " + z);
                }
            }
            List<BlockPos> dark = darkColumns(helper, village);
            helper.assertTrue(dark.isEmpty(), dark.size() + " dark columns, first " + (dark.isEmpty() ? "" : dark.get(0)));
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_lighting_no_torches", timeoutTicks = 400, skyAccess = true)
    public static void waitsForTorches(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 4));
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        LamplighterJob job = new LamplighterJob();
        CitizenTestSupport.enroll(villager, village, JobType.LAMPLIGHTER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue("torches".equals(job.waitingFor()), "waiting for " + job.waitingFor());
            helper.assertTrue(village.darkSpotCount() > 0, "dark spots " + village.darkSpotCount());
            helper.assertTrue(torchesAround(helper) == 0, "torches placed " + torchesAround(helper));
            VillageTestSupport.remove(helper, village);
        });
    }

    /** The blueprint hangs a wall torch inside the house, on a wall the blueprint itself provides, and costs one torch. */
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void theStarterHouseNeedsATorch(GameTestHelper helper) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        helper.assertTrue(blueprint.requiredMaterials().getOrDefault(Items.TORCH, 0) == 1, "torches " + blueprint.requiredMaterials().get(Items.TORCH));
        BlueprintPlacement torch = blueprint.placements().stream()
                .filter(p -> p.state().is(Blocks.WALL_TORCH))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no wall torch in the starter house"));
        Direction facing = torch.state().getValue(WallTorchBlock.FACING);
        BlockPos wall = torch.offset().relative(facing.getOpposite());
        helper.assertTrue(wall.getX() >= 0 && wall.getX() < 5 && wall.getZ() >= 0 && wall.getZ() < 5, "torch hangs outside the house on " + wall);
        helper.assertTrue(torch.offset().getX() > 0 && torch.offset().getX() < 4 && torch.offset().getZ() > 0 && torch.offset().getZ() < 4,
                "torch outside the interior at " + torch.offset());
        BlockPos origin = new BlockPos(10, 1, 10);
        for (BlueprintPlacement placement : blueprint.placements()) {
            helper.getLevel().setBlock(helper.absolutePos(origin.offset(placement.offset())), placement.state(), Block.UPDATE_CLIENTS);
        }
        BlockPos placed = origin.offset(torch.offset());
        helper.assertTrue(helper.getBlockState(placed).canSurvive(helper.getLevel(), helper.absolutePos(placed)), "the wall torch has no wall to hang on");
        helper.assertTrue(helper.getBlockState(placed.relative(facing.getOpposite())).isSolidRender(helper.getLevel(), helper.absolutePos(placed.relative(facing.getOpposite()))),
                "the torch's wall is not solid");
        helper.succeed();
    }
}
