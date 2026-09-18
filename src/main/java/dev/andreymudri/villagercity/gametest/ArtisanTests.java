package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.craft.SmeltInFurnace;
import dev.andreymudri.villagercity.craft.WorkshopService;
import dev.andreymudri.villagercity.job.ArtisanJob;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The village keeps a crafting table and a furnace by its storehouse, and the artisan turns raw stock into whatever
 * the builder and the lamplighter are short of, one storehouse-workshop-storehouse trip per step.
 */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class ArtisanTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    private static final BlockPos PLOT = new BlockPos(8, 1, 8);
    private static final int RADIUS = 12;
    private static final int TRIP_TIMEOUT = 2400;

    /** The relative position of an absolute one, as the area's own coordinates. */
    private static BlockPos relative(GameTestHelper helper, BlockPos absolute) {
        return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    /**
     * Places the whole starter house at {@link #PLOT} except every placement of {@code missing}, and records the plot
     * as a prepared one held by a builder that is not in the test, so the artisan sees exactly that shortfall.
     */
    private static void plotShortOf(GameTestHelper helper, VillageData village, Blueprint blueprint, Block missing) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(PLOT);
        for (BlueprintPlacement placement : blueprint.placements()) {
            if (placement.state().is(missing)) {
                continue;
            }
            level.setBlock(origin.offset(placement.offset()), placement.state(), Block.UPDATE_CLIENTS);
        }
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), origin, blueprint.size(), UUID.randomUUID()));
    }

    /** Spawns an artisan of the village, on the roster and running a real {@link ArtisanJob}. */
    private static ArtisanJob artisan(GameTestHelper helper, VillageData village, int x, int z) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, 1, z);
        ArtisanJob job = new ArtisanJob();
        CitizenTestSupport.enroll(villager, village, JobType.ARTISAN, ItemStack.EMPTY, job);
        village.setCitizen(villager.getUUID(), JobType.ARTISAN);
        return job;
    }

    private static @Nullable AbstractFurnaceBlockEntity furnaceOf(GameTestHelper helper, VillageData village) {
        BlockPos pos = village.furnacePos();
        return pos != null && helper.getLevel().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity ? entity : null;
    }

    /** The village puts a crafting table and a furnace beside its storehouse, and replaces either one once it is gone. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_workshop")
    public static void placesAWorkshopByTheStorehouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        BlockPos storehouse = village.storehousePos();

        WorkshopService.ensureWorkshop(helper.getLevel(), village);

        BlockPos table = village.craftingTablePos();
        BlockPos furnace = village.furnacePos();
        helper.assertTrue(table != null && furnace != null, "workshop not placed: table " + table + ", furnace " + furnace);
        helper.assertFalse(table.equals(furnace), "table and furnace share the cell " + table);
        helper.assertBlockPresent(Blocks.CRAFTING_TABLE, relative(helper, table));
        helper.assertBlockPresent(Blocks.FURNACE, relative(helper, furnace));
        for (BlockPos pos : new BlockPos[] {table, furnace}) {
            int horizontal = Math.max(Math.abs(pos.getX() - storehouse.getX()), Math.abs(pos.getZ() - storehouse.getZ()));
            helper.assertTrue(horizontal <= WorkshopService.SEARCH_RADIUS && horizontal > 0, "workshop block " + horizontal + " blocks from the storehouse");
            helper.assertTrue(Math.abs(pos.getY() - storehouse.getY()) <= WorkshopService.VERTICAL_REACH,
                    "workshop block " + (pos.getY() - storehouse.getY()) + " blocks above the storehouse");
        }

        // A table somebody took away is forgotten and placed again, wherever the next free spot happens to be.
        helper.setBlock(relative(helper, table), Blocks.AIR);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos replaced = village.craftingTablePos();
        helper.assertTrue(replaced != null, "the broken crafting table was never replaced");
        helper.assertBlockPresent(Blocks.CRAFTING_TABLE, relative(helper, replaced));
        helper.assertFalse(replaced.equals(village.furnacePos()), "the replacement table took the furnace's cell");

        VillageTestSupport.remove(helper, village);
        helper.succeed();
    }

    /** With a plot short of its door and nothing but logs in stock, the artisan crafts the door into the storehouse. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_door", timeoutTicks = TRIP_TIMEOUT)
    public static void craftsADoorFromLogsIntoTheStorehouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 10));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.OAK_DOOR);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.OAK_DOOR) >= 1,
                    "no door in the storehouse, waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            VillageTestSupport.remove(helper, village);
        });
    }

    /** With a plot short of its glass, the artisan loads the village furnace with sand and fuel and stores the glass. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_smelt", timeoutTicks = TRIP_TIMEOUT)
    public static void smeltsGlassInTheFurnace(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 4, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        AtomicBoolean furnaceLoaded = new AtomicBoolean();
        helper.onEachTick(() -> {
            AbstractFurnaceBlockEntity furnace = furnaceOf(helper, village);
            if (furnace != null && !furnace.getItem(SmeltInFurnace.SLOT_INPUT).isEmpty()) {
                furnaceLoaded.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.GLASS) >= 2,
                    "glass " + storehouse.count(Items.GLASS) + ", waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            helper.assertTrue(furnaceLoaded.get(), "the glass never went through the village furnace");
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * The storehouse holds a whole starter house except its door, so every plank in it is spoken for. The artisan must
     * craft the door's planks from the spare logs instead of spending the builder's.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_reserved", timeoutTicks = TRIP_TIMEOUT)
    public static void neverUsesTheBuildersPlanks(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        Map<Item, Integer> stock = new LinkedHashMap<>(blueprint.requiredMaterials());
        int reservedPlanks = stock.get(Items.OAK_PLANKS);
        stock.remove(Items.OAK_DOOR);
        stock.merge(Items.OAK_LOG, 8, Integer::sum);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, stock);
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        AtomicLong lowestPlanks = new AtomicLong(Long.MAX_VALUE);
        helper.onEachTick(() -> lowestPlanks.updateAndGet(seen -> Math.min(seen, storehouse.count(Items.OAK_PLANKS))));
        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.OAK_DOOR) >= 1,
                    "no door in the storehouse, waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            helper.assertTrue(lowestPlanks.get() >= reservedPlanks,
                    "the builder's planks were spent: stock fell to " + lowestPlanks.get() + " of " + reservedPlanks);
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * The whole point of the artisan: a storehouse of raw materials alone, with no planks, door, bed, glass or torch
     * in it, still becomes a finished house.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_village", timeoutTicks = 24000)
    public static void aVillageWithOnlyLogsSandWoolAndDyeBuildsAHouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        Map<Item, Integer> raw = new LinkedHashMap<>();
        raw.put(Items.OAK_LOG, 60);
        raw.put(Items.COBBLESTONE, 25);
        raw.put(Items.SAND, 2);
        raw.put(Items.WHITE_WOOL, 3);
        raw.put(Items.RED_DYE, 3);
        raw.put(Items.COAL, 1);
        BuilderTests.stockedStorehouse(helper, village, raw);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);

        Villager builder = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        BuilderJob builderJob = new BuilderJob();
        CitizenTestSupport.enroll(builder, village, JobType.BUILDER, ItemStack.EMPTY, builderJob);
        village.setCitizen(builder.getUUID(), JobType.BUILDER);
        ArtisanJob artisanJob = artisan(helper, village, 26, 22);

        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount()
                    + ", builder waiting for " + builderJob.waitingFor()
                    + ", artisan waiting for " + artisanJob.waitingFor() + ", orders " + village.artisanOrders());
            VillageTestSupport.remove(helper, village);
        });
    }

    /** With doMobGriefing off the village places no workshop at all, and records no position for one. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_griefing")
    public static void respectsMobGriefingForTheWorkshop(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean previous = rule.get();

        rule.set(false, helper.getLevel().getServer());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos table = village.craftingTablePos();
        BlockPos furnace = village.furnacePos();
        int placed = 0;
        for (int dx = -WorkshopService.SEARCH_RADIUS; dx <= WorkshopService.SEARCH_RADIUS; dx++) {
            for (int dz = -WorkshopService.SEARCH_RADIUS; dz <= WorkshopService.SEARCH_RADIUS; dz++) {
                BlockPos pos = relative(helper, village.storehousePos()).offset(dx, 0, dz);
                if (helper.getBlockState(pos).is(Blocks.CRAFTING_TABLE) || helper.getBlockState(pos).is(Blocks.FURNACE)) {
                    placed++;
                }
            }
        }
        rule.set(previous, helper.getLevel().getServer());
        VillageTestSupport.remove(helper, village);

        helper.assertTrue(table == null && furnace == null, "recorded a workshop with mob griefing off: table " + table + ", furnace " + furnace);
        helper.assertTrue(placed == 0, placed + " workshop blocks placed with mob griefing off");
        helper.succeed();
    }
}
