package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
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

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /**
     * A managed village on a terraced hillside, five unemployed villagers and a storehouse of player supplies. With
     * nobody's help it hires all five jobs, levels a plot, builds a house on it and lights its ground until no monster
     * could spawn there.
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
            int dark = darkColumns(helper, village);
            helper.assertTrue(dark == 0, dark + " columns of village ground are still dark enough to spawn monsters");
            VillageTestSupport.remove(helper, village);
        });
    }
}
