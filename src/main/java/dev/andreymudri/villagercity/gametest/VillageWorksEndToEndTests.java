package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.job.LamplighterJob;
import dev.andreymudri.villagercity.job.PaverJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The whole village at once: every job hired from unemployed villagers, working a hillside with no help. */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VillageWorksEndToEndTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    private static final int RADIUS = 12;
    /**
     * Three terrace levels by x modulo 6. Any seven columns in a row hold both a 0 and a 2, so no plot with its margin
     * is flat anywhere on the hill: the builder can only build where the paver has levelled.
     */
    private static final int[] TERRACE = {0, 0, 1, 1, 2, 2};

    /** Raises dirt terraces over the whole area; column x's first free y is {@code 1 + TERRACE[x % 6]}. */
    private static void terraces(GameTestHelper helper) {
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                for (int y = 1; y <= TERRACE[x % TERRACE.length]; y++) {
                    helper.setBlock(x, y, z, Blocks.DIRT);
                }
            }
        }
    }

    /**
     * What a player brings a new village: raw materials, and the goods the village cannot make. Logs for the planks,
     * the door, the bed frame, the sticks and the crafting table; cobblestone for the walls and for fill; glass,
     * because the village does not smelt; wool and dye for the bed; coal for the torches.
     */
    private static Map<Item, Integer> playerSupplies() {
        Map<Item, Integer> supplies = new LinkedHashMap<>();
        supplies.put(Items.OAK_LOG, 64);
        supplies.put(Items.COBBLESTONE, 64);
        supplies.put(Items.GLASS, 2);
        supplies.put(Items.WHITE_WOOL, 3);
        supplies.put(Items.RED_DYE, 3);
        supplies.put(Items.COAL, 8);
        return supplies;
    }

    /**
     * Ground inside the village square where a zombie could spawn: the same test the lamplighter uses, written out
     * here so the check does not lean on the code it checks. Columns under a house, plot or the storehouse are not
     * village ground. The test runs with {@code skyAccess}: under the default barrier ceiling the heightmap lands on
     * the barrier, and every column reads as lit.
     */
    private static int darkColumns(GameTestHelper helper, VillageData village) {
        ServerLevel level = helper.getLevel();
        List<Footprint> occupied = village.occupiedFootprints();
        BlockPos center = village.center();
        int dark = 0;
        for (int x = center.getX() - RADIUS; x <= center.getX() + RADIUS; x++) {
            for (int z = center.getZ() - RADIUS; z <= center.getZ() + RADIUS; z++) {
                int cx = x;
                int cz = z;
                if (occupied.stream().anyMatch(footprint -> footprint.contains(cx, cz))) {
                    continue;
                }
                BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, center.getY(), z));
                BlockPos ground = feet.below();
                if (level.getBlockState(ground).isValidSpawn(level, ground, EntityType.ZOMBIE)
                        && isOpen(level, feet) && isOpen(level, feet.above())
                        && level.getBrightness(LightLayer.BLOCK, feet) == 0) {
                    dark++;
                }
            }
        }
        return dark;
    }

    /**
     * Fails unless the village has street cells and every one of them has a recorded path cell on either hand across
     * the street: east and west, or north and south.
     */
    private static void assertThreeWideStreets(GameTestHelper helper, VillageData village) {
        List<StreetCell> streets = village.streets();
        helper.assertFalse(streets.isEmpty(), "the village laid no street");
        List<BlockPos> paths = village.pathCells();
        for (StreetCell cell : streets) {
            BlockPos pos = cell.pos();
            boolean eastWest = paths.contains(pos.east()) && paths.contains(pos.west());
            boolean northSouth = paths.contains(pos.north()) && paths.contains(pos.south());
            helper.assertTrue(eastWest || northSouth, "the street cell at " + StreetTests.relative(helper, pos).toShortString()
                    + " is not three cells wide");
        }
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /**
     * A managed village on a terraced hillside, five unemployed villagers and a storehouse of player supplies. With
     * nobody's help it hires all five jobs, lays a three-wide street, levels a plot, builds a house on it and lights its
     * ground until no monster could spawn there.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_works_e2e_hill", timeoutTicks = 36000, skyAccess = true)
    public static void aHillVillageGrowsOnItsOwn(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        terraces(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, true);
        // The bare hill is dark, so the lighting condition below is earned and not true from the start.
        int darkAtStart = darkColumns(helper, village);
        if (darkAtStart == 0) {
            VillageTestSupport.remove(helper, village);
            helper.fail("the bare hill has no dark ground, so this test cannot tell a lit village from an unlit one");
        }
        for (int i = 0; i < 5; i++) {
            int x = 22 + i;
            GameTestSupport.spawnVillager(helper, x, 1 + TERRACE[x % TERRACE.length], 22);
        }
        AtomicBoolean stocked = new AtomicBoolean();
        AtomicBoolean sawUnprepared = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockPos storehouse = village.storehousePos();
            if (!stocked.get() && storehouse != null
                    && helper.getLevel().getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) {
                playerSupplies().forEach((item, count) -> entity.insertFromCitizen(new ItemStack(item, count)));
                stocked.set(true);
            }
            if (village.plots().stream().anyMatch(plot -> !plot.prepared())) {
                sawUnprepared.set(true);
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(stocked.get(), "the village never placed its storehouse");
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount() + ", plots "
                    + village.plots().stream().map(Plot::prepared).toList() + ", artisan orders " + village.artisanOrders());
            helper.assertTrue(sawUnprepared.get(), "the house went up on a plot the paver never had to level");
            assertThreeWideStreets(helper, village);
            int dark = darkColumns(helper, village);
            helper.assertTrue(dark == 0, dark + " columns of village ground are still dark enough to spawn monsters");
            VillageTestSupport.remove(helper, village);
        });
    }

    /** The slope {@link #aSlopeVillageBuildsTwoHousesOnItsStreets} stands on: one block up for every four south. */
    private static int slope(int x, int z) {
        return z / 4;
    }

    /**
     * What {@link #aSlopeVillageBuildsTwoHousesOnItsStreets} starts with: the finished materials of two starter houses,
     * torches for the lamplighter, dirt and cobblestone for the paver's fill and oak planks for its bridges.
     */
    private static Map<Item, Integer> slopeSupplies(Blueprint blueprint) {
        Map<Item, Integer> supplies = new LinkedHashMap<>();
        blueprint.requiredMaterials().forEach((item, count) -> supplies.merge(item, count * 2, Integer::sum));
        supplies.merge(Items.TORCH, 64, Integer::sum);
        supplies.merge(Items.DIRT, 256, Integer::sum);
        supplies.merge(Items.COBBLESTONE, 64, Integer::sum);
        supplies.merge(Items.OAK_PLANKS, 64, Integer::sum);
        return supplies;
    }

    private static void stockedStorehouse(GameTestHelper helper, VillageData village, BlockPos relative, Map<Item, Integer> supplies) {
        helper.setBlock(relative, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(relative));
        StorehouseBlockEntity storehouse = helper.getBlockEntity(relative);
        supplies.forEach((item, count) -> {
            int left = count;
            while (left > 0) {
                int stack = Math.min(left, new ItemStack(item).getMaxStackSize());
                storehouse.insertFromCitizen(new ItemStack(item, stack));
                left -= stack;
            }
        });
    }

    /** Spawns a villager standing on the slope and puts it on the village's roster running {@code job}. */
    private static Villager enroll(GameTestHelper helper, VillageData village, int x, int z, JobType type, ItemStack tool,
            Job job) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, slope(x, z) + 1, z);
        CitizenTestSupport.enroll(villager, village, type, tool, job);
        village.setCitizen(villager.getUUID(), type);
        return villager;
    }

    /** Open columns between two footprints along whichever of x and z they are farther apart on. */
    private static int gap(Footprint a, Footprint b) {
        int x = Math.max(b.minX() - a.maxX(), a.minX() - b.maxX()) - 1;
        int z = Math.max(b.minZ() - a.maxZ(), a.minZ() - b.maxZ()) - 1;
        return Math.max(x, z);
    }

    /**
     * An unmanaged village on a slope climbing one block in four, with a storehouse, a paver, a builder and a
     * lamplighter on its roster. The paver lays three-wide streets from the bell; the builder builds two houses beside
     * them, each at the height of a street it touches and at least {@link PlotRules#HOUSE_GAP} columns from the other,
     * every block as the blueprint has it; the lamplighter lights the village until no monster could spawn there.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_works_e2e_slope", timeoutTicks = 36000, skyAccess = true)
    public static void aSlopeVillageBuildsTwoHousesOnItsStreets(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        StreetTests.terrain(helper, VillageWorksEndToEndTests::slope);
        BlockPos bell = new BlockPos(BELL.getX(), slope(BELL.getX(), BELL.getZ()) + 1, BELL.getZ());
        VillageData village = VillageTestSupport.freshVillage(helper, bell, RADIUS, false);
        int darkAtStart = darkColumns(helper, village);
        if (darkAtStart == 0) {
            VillageTestSupport.remove(helper, village);
            helper.fail("the bare slope has no dark ground, so this test cannot tell a lit village from an unlit one");
        }
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BlockPos storehouse = new BlockPos(18, slope(18, 30) + 1, 30);
        stockedStorehouse(helper, village, storehouse, slopeSupplies(blueprint));
        enroll(helper, village, 22, 20, JobType.PAVER, new ItemStack(Items.STONE_PICKAXE), new PaverJob());
        enroll(helper, village, 26, 20, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        enroll(helper, village, 24, 28, JobType.LAMPLIGHTER, ItemStack.EMPTY, new LamplighterJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 2, "houses " + village.houseCount() + ", streets " + village.streets().size()
                    + ", plots " + village.plots().stream().map(plot -> StreetTests.relative(helper, plot.origin()).toShortString()
                    + (plot.prepared() ? " prepared" : " unprepared")).toList());
            assertThreeWideStreets(helper, village);
            List<BuildingRecord> houses = village.houses();
            for (BuildingRecord house : houses) {
                StreetGrowthTests.assertOnAStreet(helper, village, house);
                for (BlueprintPlacement placement : blueprint.placements()) {
                    BlockPos pos = house.origin().offset(placement.offset());
                    helper.assertTrue(helper.getLevel().getBlockState(pos).is(placement.state().getBlock()), "wrong block at "
                            + StreetTests.relative(helper, pos).toShortString() + ": " + helper.getLevel().getBlockState(pos));
                }
            }
            int gap = gap(houses.get(0).footprint(), houses.get(1).footprint());
            helper.assertTrue(gap >= PlotRules.HOUSE_GAP, "only " + gap + " columns lie between the two houses");
            int dark = darkColumns(helper, village);
            helper.assertTrue(dark == 0, dark + " columns of village ground are still dark enough to spawn monsters");
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(VillageRegistry.get(helper.getLevel()).all().stream().noneMatch(v -> v.id().equals(village.id())),
                    "the village is still registered");
        });
    }
}
