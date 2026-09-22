package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.craft.VillageDemand;
import dev.andreymudri.villagercity.craft.WorkshopService;
import dev.andreymudri.villagercity.job.ArtisanJob;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageWorks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The village keeps a crafting table by its storehouse, and the artisan crafts whatever the builder and the
 * lamplighter are short of, one storehouse-workshop-storehouse trip per step.
 * <p>
 * It does not smelt, and there are no tests here for smelting because there is no smelting: the tests that used to
 * exercise it went with the feature. What is tested instead is that nothing brings it back -- no furnace is placed,
 * one standing nearby is left alone, and an order the village would have had to smelt for is reported as the item a
 * player must bring.
 */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class ArtisanTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    private static final BlockPos PLOT = new BlockPos(8, 1, 8);
    private static final int RADIUS = 12;
    private static final int TRIP_TIMEOUT = 2400;
    /** While set, every EntityPlaceEvent is cancelled; only set and cleared within one synchronous test body. */
    private static volatile boolean cancelAllPlacements;

    static {
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent event) -> {
            if (cancelAllPlacements) {
                event.setCanceled(true);
            }
        });
    }

    /** The relative position of an absolute one, as the area's own coordinates. */
    private static BlockPos relative(GameTestHelper helper, BlockPos absolute) {
        return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    /**
     * Places the whole starter house at {@link #PLOT} except every placement of {@code missing}, and records the plot
     * as a prepared one held by a builder that is not in the test, so the artisan sees exactly that shortfall.
     */
    private static void plotShortOf(GameTestHelper helper, VillageData village, Blueprint blueprint, Block... missing) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(PLOT);
        List<Block> skipped = List.of(missing);
        for (BlueprintPlacement placement : blueprint.placements()) {
            if (skipped.stream().anyMatch(block -> placement.state().is(block))) {
                continue;
            }
            level.setBlock(origin.offset(placement.offset()), placement.state(), Block.UPDATE_CLIENTS);
        }
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), origin, blueprint.size(), UUID.randomUUID()));
    }

    /**
     * As {@link #plotShortOf}, but only the FIRST placement of {@code one} is left out, so the plot is short of exactly
     * one of it on top of every placement of {@code all}.
     */
    private static void plotShortOfOne(GameTestHelper helper, VillageData village, Blueprint blueprint, Block one, Block... all) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(PLOT);
        List<Block> skipped = List.of(all);
        boolean oneSkipped = false;
        for (BlueprintPlacement placement : blueprint.placements()) {
            if (skipped.stream().anyMatch(block -> placement.state().is(block))) {
                continue;
            }
            if (!oneSkipped && placement.state().is(one)) {
                oneSkipped = true;
                continue;
            }
            level.setBlock(origin.offset(placement.offset()), placement.state(), Block.UPDATE_CLIENTS);
        }
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), origin, blueprint.size(), UUID.randomUUID()));
    }

    /** Puts a stack straight into the furnace at this position, the way a player leaves one behind. */
    private static void seedFurnace(GameTestHelper helper, BlockPos pos, int slot, ItemStack stack) {
        if (!(helper.getLevel().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            throw new IllegalStateException("no furnace at " + pos + " to seed");
        }
        furnace.setItem(slot, stack);
        furnace.setChanged();
    }

    /** What the furnace at this position holds in a slot, or an empty stack when there is no furnace there. */
    private static ItemStack slotAt(GameTestHelper helper, BlockPos pos, int slot) {
        return helper.getLevel().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity
                ? entity.getItem(slot) : ItemStack.EMPTY;
    }

    /** Whether any furnace stands within the workshop search area around the storehouse. */
    private static @Nullable BlockPos furnaceNear(GameTestHelper helper, BlockPos storehouse) {
        int r = WorkshopService.SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(storehouse.offset(-r, -WorkshopService.VERTICAL_REACH, -r),
                storehouse.offset(r, WorkshopService.VERTICAL_REACH, r))) {
            if (helper.getLevel().getBlockState(pos).is(Blocks.FURNACE)) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** Spawns an artisan of the village, on the roster and running a real {@link ArtisanJob}. */
    private static ArtisanJob artisan(GameTestHelper helper, VillageData village, int x, int z) {
        ArtisanJob job = new ArtisanJob();
        artisanRunning(helper, village, x, z, job);
        return job;
    }

    /** Spawns an artisan of the village running {@code job}, and hands back the villager itself. */
    private static Villager artisanRunning(GameTestHelper helper, VillageData village, int x, int z, Job job) {
        Villager villager = GameTestSupport.spawnVillager(helper, x, 1, z);
        CitizenTestSupport.enroll(villager, village, JobType.ARTISAN, ItemStack.EMPTY, job);
        village.setCitizen(villager.getUUID(), JobType.ARTISAN);
        return villager;
    }

    /** The village puts a crafting table beside its storehouse -- and only that -- and replaces it once it is gone. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_workshop")
    public static void placesAWorkshopByTheStorehouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity stock = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 2));
        BlockPos storehouse = village.storehousePos();

        WorkshopService.ensureWorkshop(helper.getLevel(), village);

        BlockPos table = village.craftingTablePos();
        helper.assertTrue(table != null, "workshop not placed");
        helper.assertTrue(stock.count(Items.OAK_LOG) == 1, "the table cost " + (2 - stock.count(Items.OAK_LOG)) + " logs, not 1");
        helper.assertBlockPresent(Blocks.CRAFTING_TABLE, relative(helper, table));
        int horizontal = Math.max(Math.abs(table.getX() - storehouse.getX()), Math.abs(table.getZ() - storehouse.getZ()));
        helper.assertTrue(horizontal <= WorkshopService.SEARCH_RADIUS && horizontal > 0,
                "workshop block " + horizontal + " blocks from the storehouse");
        helper.assertTrue(Math.abs(table.getY() - storehouse.getY()) <= WorkshopService.VERTICAL_REACH,
                "workshop block " + (table.getY() - storehouse.getY()) + " blocks above the storehouse");

        // A table somebody took away is forgotten and placed again. The freed cell comes first in the fixed search
        // order, so the replacement lands back on it: only a village that failed to forget the old position would
        // still count it as occupied and put the table somewhere else.
        helper.setBlock(relative(helper, table), Blocks.AIR);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos replaced = village.craftingTablePos();
        helper.assertTrue(replaced != null, "the broken crafting table was never replaced");
        helper.assertBlockPresent(Blocks.CRAFTING_TABLE, relative(helper, replaced));
        helper.assertTrue(table.equals(replaced), "the replacement table went to " + replaced + " instead of the freed cell " + table);
        helper.assertTrue(stock.count(Items.OAK_LOG) == 0, "the replacement table was not paid for: " + stock.count(Items.OAK_LOG) + " logs left");

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
    public static void aVillageWithLogsGlassWoolAndDyeBuildsAHouse(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        Map<Item, Integer> raw = new LinkedHashMap<>();
        raw.put(Items.OAK_LOG, 60);
        raw.put(Items.COBBLESTONE, 25);
        // Glass, not sand: the village cannot smelt, so the panes come in from a player's own furnace. The coal is
        // for torches, which are crafted.
        raw.put(Items.GLASS, 2);
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

    /**
     * The workshop is a crafting table and nothing else. No furnace is placed anywhere near the storehouse, and the
     * village considers its workshop finished without one: the artisan works from the first tick rather than waiting
     * for a block that is never coming.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_no_furnace", timeoutTicks = TRIP_TIMEOUT)
    public static void noFurnaceIsEverPlacedAndTheWorkshopIsCompleteWithoutOne(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 10));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.OAK_DOOR);
        BlockPos storehousePos = village.storehousePos();
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        // Watched every tick, not just at the end: a furnace placed and broken again in between still counts.
        AtomicBoolean everPlaced = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (furnaceNear(helper, storehousePos) != null) {
                everPlaced.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertFalse(everPlaced.get(), "the village placed a furnace by its storehouse");
            helper.assertTrue(storehouse.count(Items.OAK_DOOR) >= 1, "the artisan never got to work without a furnace:"
                    + " waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * A village that needs glass and has sand asks for GLASS. It cannot turn the one into the other any more, so the
     * sand on its shelves is of no use to it, and naming it would send a player to dig a beach for nothing. This is
     * the whole user-facing surface of dropping smelting, and it is the easiest thing to get wrong.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_asks_for_glass", timeoutTicks = TRIP_TIMEOUT)
    public static void aVillageShortOfGlassAsksForGlassAndNotSand(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        // Sand and coal it cannot use for the glass, and logs it can use for the door: the glass order is unbuildable
        // while the door order beside it goes ahead.
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village,
                Map.of(Items.SAND, 16, Items.COAL, 8, Items.OAK_LOG, 10));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        // One glass and the door, so the orders tie at one each and glass sorts first by id: the unbuildable step is
        // planned AHEAD of the door's, and only an artisan that skips past it ever reaches the door.
        plotShortOfOne(helper, village, blueprint, Blocks.GLASS, Blocks.OAK_DOOR);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            String waiting = job.waitingFor();
            List<String> orders = village.artisanOrders();
            helper.assertTrue(!orders.isEmpty() && orders.get(0).startsWith("glass "),
                    "the glass order is not first, so this test no longer puts the smelting step ahead: " + orders);
            helper.assertTrue(storehouse.count(Items.OAK_DOOR) >= 1, "the unbuildable glass order froze the door"
                    + " behind it: waiting for " + waiting + ", orders " + village.artisanOrders());
            // Checked before the message, so that a village which tries to run the smelting step says so in its own
            // words rather than failing the wording assertion for a second reason.
            helper.assertTrue(storehouse.count(Items.SAND) == 16, "the village spent sand on a step it cannot run: "
                    + storehouse.count(Items.SAND) + " of 16 left");
            helper.assertTrue(waiting != null && waiting.contains("glass"),
                    "the village must ask for the glass it needs, but it is waiting for " + waiting);
            helper.assertFalse(waiting.contains("sand"), "the village asked for sand, which it can do nothing with: " + waiting);
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * The same rule where the smelted good is not the order itself: a lamplighter wants torches, the storehouse has
     * logs and no coal, and the only route to a torch runs through charcoal. The village asks for the CHARCOAL it
     * cannot make, not for the logs it already has and not for the torches it would rather have been given -- the
     * skipped step's own output is what a player has to bring for the rest of the plan to run.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_asks_for_charcoal", timeoutTicks = 3600)
    public static void aVillageWithNoCoalAsksForCharcoalAndNotLogs(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 20));
        village.setCitizen(UUID.randomUUID(), JobType.LAMPLIGHTER);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            String waiting = job.waitingFor();
            helper.assertTrue(waiting != null && waiting.contains("charcoal"),
                    "the village must ask for the charcoal the torches need, but it is waiting for " + waiting
                            + ", orders " + village.artisanOrders());
            helper.assertFalse(waiting.contains("oak_log"), "the village asked for the logs it is already holding: " + waiting);
            helper.assertTrue(storehouse.count(Items.TORCH) == 0, "the village made " + storehouse.count(Items.TORCH)
                    + " torches with no coal and no way to smelt any");
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * A furnace standing beside the storehouse with a player's smelt in it is not the village's business. It is never
     * emptied, never loaded, never recorded and never broken -- the village walks past it for as long as it runs.
     * Tidying away a block a player may be using is the class of thing dropping smelting exists to prevent.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_furnace_ignored", timeoutTicks = TRIP_TIMEOUT)
    public static void aFurnaceByTheStorehouseIsNeverTouched(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village,
                Map.of(Items.OAK_LOG, 10, Items.SAND, 8, Items.COAL, 8));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS, Blocks.OAK_DOOR);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        // A player's furnace, right where the village used to put its own, mid-smelt.
        BlockPos furnace = helper.absolutePos(new BlockPos(20, 1, 26));
        helper.setBlock(new BlockPos(20, 1, 26), Blocks.FURNACE);
        seedFurnace(helper, furnace, 0, new ItemStack(Items.SAND, 4));
        seedFurnace(helper, furnace, 1, new ItemStack(Items.COAL, 2));
        seedFurnace(helper, furnace, 2, new ItemStack(Items.GLASS, 6));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.OAK_DOOR) >= 1, "the artisan never worked: waiting for "
                    + job.waitingFor() + ", orders " + village.artisanOrders());
            helper.assertBlockPresent(Blocks.FURNACE, new BlockPos(20, 1, 26));
            // The furnace is lit and smelting the player's own sand, so input and output move between themselves;
            // what may not happen is either of them leaving the furnace, or the village's stock joining them.
            int input = slotAt(helper, furnace, 0).getCount();
            int fuel = slotAt(helper, furnace, 1).getCount();
            int output = slotAt(helper, furnace, 2).getCount();
            helper.assertTrue(input + output == 10, "the player's ten items became " + (input + output)
                    + " across the furnace: input " + input + ", result " + output);
            helper.assertTrue(fuel <= 2, "the village put fuel into a player's furnace: " + fuel);
            helper.assertTrue(storehouse.count(Items.GLASS) == 0, "the village banked " + storehouse.count(Items.GLASS)
                    + " of the player's glass");
            helper.assertTrue(storehouse.count(Items.SAND) == 8, "the village carried its sand off to a furnace: "
                    + storehouse.count(Items.SAND) + " of 8");
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * A broken crafting table drops itself. The village pays for each one it sets -- a log, or four planks without
     * one -- so breaking it turns village stock into a table instead of making one from nothing, and an empty
     * storehouse gets no table at all.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_table_price")
    public static void aBrokenTableIsNotAFreeTable(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity stock = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 1, Items.OAK_PLANKS, 7));

        int tables = 0;
        for (int round = 0; round < 5; round++) {
            WorkshopService.ensureWorkshop(helper.getLevel(), village);
            BlockPos table = village.craftingTablePos();
            if (table == null) {
                break;
            }
            tables++;
            helper.getLevel().destroyBlock(table, true);
        }
        long logs = stock.count(Items.OAK_LOG);
        long planks = stock.count(Items.OAK_PLANKS);
        VillageTestSupport.remove(helper, village);

        // One log pays for the first table, four of the seven planks for the second; three planks buy nothing.
        helper.assertTrue(tables == 2, tables + " tables set from one log and seven planks, not 2");
        helper.assertTrue(logs == 0 && planks == 3, "paid the wrong way: " + logs + " logs and " + planks + " planks left");
        helper.succeed();
    }

    /**
     * Differently named planks are separate storehouse entries. The village still takes the full four for a table,
     * or breaking the table would turn one plank into a crafting table's worth of them.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_table_named_planks")
    public static void namedPlanksStillPayTheFullPrice(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity stock = BuilderTests.stockedStorehouse(helper, village, Map.of());
        for (String name : List.of("a", "b", "c", "d")) {
            ItemStack plank = new ItemStack(Items.OAK_PLANKS);
            plank.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            stock.insert(plank, 1);
        }

        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos table = village.craftingTablePos();
        long planks = stock.count(Items.OAK_PLANKS);
        VillageTestSupport.remove(helper, village);

        helper.assertTrue(table != null, "four named planks did not pay for a table");
        helper.assertTrue(planks == 0, "the table cost " + (4 - planks) + " planks, not 4");
        helper.succeed();
    }

    /**
     * The lamplighter's stock is sixteen torches. The torches already held count toward it once, not twice: a village
     * holding eight goes on to make eight more.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_torch_stock", timeoutTicks = TRIP_TIMEOUT)
    public static void theLamplightersStockFillsToSixteen(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village,
                Map.of(Items.OAK_LOG, 1, Items.TORCH, 8, Items.COAL, 4, Items.STICK, 4));
        village.setCitizen(UUID.randomUUID(), JobType.LAMPLIGHTER);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            long torches = storehouse.count(Items.TORCH);
            helper.assertTrue(torches >= VillageDemand.TORCH_STOCK, "the torch stock stopped at " + torches
                    + ": waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            VillageTestSupport.remove(helper, village);
        });
    }

    /** Planks the builder is counted on having never pay for a table: the village waits for spare stock instead. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_table_reserved")
    public static void aTableIsNeverPaidForWithTheBuildersPlanks(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity stock = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_PLANKS, 8));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.OAK_PLANKS);

        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos table = village.craftingTablePos();
        long planks = stock.count(Items.OAK_PLANKS);
        VillageTestSupport.remove(helper, village);

        helper.assertTrue(table == null, "a table was paid for with planks the builder is waiting on, at " + table);
        helper.assertTrue(planks == 8, "the builder's planks went from 8 to " + planks);
        helper.succeed();
    }

    /**
     * A lamplighter's sixteen torches and the house's one are a single order. With too little coal for the whole order
     * the artisan still makes the torches the coal it has will make, rather than skipping the step and leaving the
     * builder waiting on a torch the storehouse could have paid for.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_torch_with_little_coal", timeoutTicks = TRIP_TIMEOUT)
    public static void littleCoalStillMakesTheHousesTorch(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 20, Items.COAL, 1));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        village.setCitizen(UUID.randomUUID(), JobType.LAMPLIGHTER);
        plotShortOf(helper, village, blueprint, Blocks.WALL_TORCH);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.TORCH) >= 1, "one coal made no torch: waiting for "
                    + job.waitingFor() + ", orders " + village.artisanOrders());
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * A world saved when the village still recorded a furnace position has that key in its works tag, and must load
     * anyway -- with the key ignored rather than carried forward. A record field the village never sets again is dead
     * state, and dead state is how a deleted feature comes back.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_old_save")
    public static void anOldSaveThatRecordedAFurnaceLoads(GameTestHelper helper) {
        // Exactly the works tag the version that placed a furnace wrote.
        CompoundTag works = new CompoundTag();
        works.put("crafting_table", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, new BlockPos(1, 2, 3)).getOrThrow());
        works.put("furnace", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, new BlockPos(4, 5, 6)).getOrThrow());

        VillageWorks decoded = VillageWorks.CODEC.parse(NbtOps.INSTANCE, works).getOrThrow();

        helper.assertTrue(decoded.craftingTable().isPresent(),
                "a works tag with a furnace position in it lost the rest of itself: " + decoded);
        CompoundTag saved = (CompoundTag) VillageWorks.CODEC.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow();
        helper.assertTrue(!saved.contains("furnace"), "the old furnace position was carried into the next save: " + saved);
        helper.succeed();
    }

    /**
     * Everything the starter house needs that is not smelted, the village still makes for itself: the planks and the
     * door from its logs, and the torch from the coal a player brought. Only the glass comes in ready-made.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_crafts_the_rest", timeoutTicks = TRIP_TIMEOUT)
    public static void theVillageStillCraftsEverythingItDoesNotHaveToSmelt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village,
                Map.of(Items.OAK_LOG, 20, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        village.setCitizen(UUID.randomUUID(), JobType.LAMPLIGHTER);
        plotShortOf(helper, village, blueprint, Blocks.OAK_DOOR, Blocks.OAK_PLANKS, Blocks.WALL_TORCH);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            long doors = storehouse.count(Items.OAK_DOOR);
            long planks = storehouse.count(Items.OAK_PLANKS);
            long torches = storehouse.count(Items.TORCH);
            helper.assertTrue(doors >= 1 && planks >= 4 && torches >= 4, "the village stopped crafting what it can"
                    + " still make: doors " + doors + ", planks " + planks + ", torches " + torches
                    + ", waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            VillageTestSupport.remove(helper, village);
        });
    }

    /** With doMobGriefing off the village places no workshop at all, and records no position for one. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_griefing")
    public static void respectsMobGriefingForTheWorkshop(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        // Stocked, so it is the game rule and not an empty storehouse that keeps the table out.
        StorehouseBlockEntity stock = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 1));
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean previous = rule.get();

        rule.set(false, helper.getLevel().getServer());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos table = village.craftingTablePos();
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

        helper.assertTrue(table == null, "recorded a workshop with mob griefing off: table " + table);
        helper.assertTrue(placed == 0, placed + " workshop blocks placed with mob griefing off");
        helper.assertTrue(stock.count(Items.OAK_LOG) == 1, "charged for a table it did not place");
        helper.succeed();
    }

    /** A protection mod cancelling EntityPlaceEvent leaves no workshop block behind and no position recorded. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_workshop_event")
    public static void workshopRespectsCancelledPlaceEvent(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity stock = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 1));
        BlockPos table;
        cancelAllPlacements = true;
        try {
            WorkshopService.ensureWorkshop(helper.getLevel(), village);
            table = village.craftingTablePos();
        } finally {
            cancelAllPlacements = false;
            VillageTestSupport.remove(helper, village);
        }
        helper.assertTrue(table == null, "workshop recorded although placement was cancelled: table " + table);
        helper.assertTrue(stock.count(Items.OAK_LOG) == 1, "charged for a table a protection mod refused");
        for (int x = 0; x < GameTestSupport.AREA_SIZE; x++) {
            for (int z = 0; z < GameTestSupport.AREA_SIZE; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.assertBlockNotPresent(Blocks.CRAFTING_TABLE, new BlockPos(x, y, z));
                    helper.assertBlockNotPresent(Blocks.FURNACE, new BlockPos(x, y, z));
                }
            }
        }
        helper.succeed();
    }

    /** A laid path is walked on, not built on: the workshop goes to the first cell that is not a path column. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_workshop_path")
    public static void theWorkshopStaysOffLaidPaths(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 1));
        BlockPos storehouse = village.storehousePos();
        // Every cell the search would otherwise take first is a laid path, so only the outermost ring is left.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                village.addPathCell(storehouse.offset(dx, 0, dz));
            }
        }

        WorkshopService.ensureWorkshop(helper.getLevel(), village);

        BlockPos table = village.craftingTablePos();
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(table != null, "workshop not placed beside the path");
        int horizontal = Math.max(Math.abs(table.getX() - storehouse.getX()), Math.abs(table.getZ() - storehouse.getZ()));
        helper.assertTrue(horizontal == WorkshopService.SEARCH_RADIUS,
                "workshop block at " + relative(helper, table) + " stands on a laid path column");
        helper.succeed();
    }

    /** Players stand next to the storehouse: a workshop block is never set in the cell a living entity occupies. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_workshop_occupied")
    public static void neverPlacesTheWorkshopInsideAVillager(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 1));
        BlockPos storehouse = village.storehousePos();
        // The first cell the fixed search order takes, proved by placesAWorkshopByTheStorehouse: stand in it.
        BlockPos taken = storehouse.offset(-1, 0, -1);
        Villager bystander = GameTestSupport.spawnVillager(helper, relative(helper, taken).getX(), 1, relative(helper, taken).getZ());
        double stoodAt = bystander.getY();

        WorkshopService.ensureWorkshop(helper.getLevel(), village);

        BlockPos table = village.craftingTablePos();
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(table != null, "workshop not placed beside the bystander");
        helper.assertFalse(taken.equals(table), "the crafting table was set inside the villager at " + relative(helper, taken));
        helper.assertBlockNotPresent(Blocks.CRAFTING_TABLE, relative(helper, taken));
        helper.assertTrue(bystander.getY() <= stoodAt, "the villager was shoved up to " + bystander.getY() + " from " + stoodAt);
        helper.succeed();
    }

    /**
     * A trip that failed left the artisan holding stock and its eight slots full. It has to put that back before
     * planning anything, or every withdrawal from now on overflows the inventory and nothing is ever crafted again.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_carrying", timeoutTicks = TRIP_TIMEOUT)
    public static void storesWhatAFailedTripLeftItHoldingBeforePlanning(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.OAK_LOG, 10));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.OAK_DOOR);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);
        Villager villager = helper.getLevel().getEntitiesOfClass(Villager.class, new AABB(helper.absolutePos(new BlockPos(22, 1, 22))).inflate(2)).get(0);
        for (Item filler : List.of(Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT,
                Items.LAPIS_LAZULI, Items.QUARTZ, Items.AMETHYST_SHARD, Items.ECHO_SHARD)) {
            villager.getInventory().addItem(new ItemStack(filler, 1));
        }
        helper.assertFalse(villager.getInventory().canAddItem(new ItemStack(Items.OAK_LOG)), "the artisan's inventory is not full");

        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.OAK_DOOR) >= 1,
                    "no door in the storehouse, waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            VillageTestSupport.remove(helper, village);
        });
    }

}
