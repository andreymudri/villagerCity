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
import java.util.List;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
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

    /** Puts a stack straight into a village furnace slot, the way a player or an interrupted trip leaves one behind. */
    private static void seedFurnace(GameTestHelper helper, VillageData village, int slot, ItemStack stack) {
        AbstractFurnaceBlockEntity furnace = furnaceOf(helper, village);
        if (furnace == null) {
            throw new IllegalStateException("no furnace to seed; place the workshop first");
        }
        furnace.setItem(slot, stack);
        furnace.setChanged();
    }

    /** What the village furnace holds in a slot right now, or an empty stack when there is no furnace. */
    private static ItemStack furnaceSlot(GameTestHelper helper, VillageData village, int slot) {
        AbstractFurnaceBlockEntity furnace = furnaceOf(helper, village);
        return furnace == null ? ItemStack.EMPTY : furnace.getItem(slot);
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

        // A table somebody took away is forgotten and placed again. The freed cell comes first in the fixed search
        // order, so the replacement lands back on it: only a village that failed to forget the old position would
        // still count it as occupied and put the table somewhere else.
        helper.setBlock(relative(helper, table), Blocks.AIR);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos replaced = village.craftingTablePos();
        helper.assertTrue(replaced != null, "the broken crafting table was never replaced");
        helper.assertBlockPresent(Blocks.CRAFTING_TABLE, relative(helper, replaced));
        helper.assertTrue(table.equals(replaced), "the replacement table went to " + replaced + " instead of the freed cell " + table);

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

    /** A protection mod cancelling EntityPlaceEvent leaves no workshop block behind and no position recorded. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_workshop_event")
    public static void workshopRespectsCancelledPlaceEvent(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        BlockPos table;
        BlockPos furnace;
        cancelAllPlacements = true;
        try {
            WorkshopService.ensureWorkshop(helper.getLevel(), village);
            table = village.craftingTablePos();
            furnace = village.furnacePos();
        } finally {
            cancelAllPlacements = false;
            VillageTestSupport.remove(helper, village);
        }
        helper.assertTrue(table == null && furnace == null,
                "workshop recorded although placement was cancelled: table " + table + ", furnace " + furnace);
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
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        BlockPos storehouse = village.storehousePos();
        // Every cell the search would otherwise take first is a laid path, so only the outermost ring is left.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                village.addPathCell(storehouse.offset(dx, 0, dz));
            }
        }

        WorkshopService.ensureWorkshop(helper.getLevel(), village);

        BlockPos table = village.craftingTablePos();
        BlockPos furnace = village.furnacePos();
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(table != null && furnace != null, "workshop not placed beside the path: table " + table + ", furnace " + furnace);
        for (BlockPos pos : new BlockPos[] {table, furnace}) {
            int horizontal = Math.max(Math.abs(pos.getX() - storehouse.getX()), Math.abs(pos.getZ() - storehouse.getZ()));
            helper.assertTrue(horizontal == WorkshopService.SEARCH_RADIUS,
                    "workshop block at " + relative(helper, pos) + " stands on a laid path column");
        }
        helper.succeed();
    }

    /** Players stand next to the storehouse: a workshop block is never set in the cell a living entity occupies. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_workshop_occupied")
    public static void neverPlacesTheWorkshopInsideAVillager(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        BlockPos storehouse = village.storehousePos();
        // The first cell the fixed search order takes, proved by placesAWorkshopByTheStorehouse: stand in it.
        BlockPos taken = storehouse.offset(-1, 0, -1);
        Villager bystander = GameTestSupport.spawnVillager(helper, relative(helper, taken).getX(), 1, relative(helper, taken).getZ());
        double stoodAt = bystander.getY();

        WorkshopService.ensureWorkshop(helper.getLevel(), village);

        BlockPos table = village.craftingTablePos();
        BlockPos furnace = village.furnacePos();
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(table != null && furnace != null, "workshop not placed beside the bystander: table " + table + ", furnace " + furnace);
        helper.assertFalse(taken.equals(table), "the crafting table was set inside the villager at " + relative(helper, taken));
        helper.assertFalse(taken.equals(furnace), "the furnace was set inside the villager at " + relative(helper, taken));
        helper.assertBlockNotPresent(Blocks.CRAFTING_TABLE, relative(helper, taken));
        helper.assertBlockNotPresent(Blocks.FURNACE, relative(helper, taken));
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

    /**
     * A night interrupts a smelt, so the artisan walks up to a furnace already holding a part of its own batch and a
     * fuel it did not choose. It finishes that batch rather than refusing the furnace, which is the only thing that
     * keeps the sand already in there on the village's books.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_resume", timeoutTicks = TRIP_TIMEOUT)
    public static void resumesASmeltAnEarlierTripLeftInTheFurnace(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        // What an interrupted trip leaves behind: one of the two sand already in, burning oak planks the storehouse
        // has none of, while the storehouse holds coal the artisan would otherwise have picked.
        seedFurnace(helper, village, SmeltInFurnace.SLOT_INPUT, new ItemStack(Items.SAND, 1));
        seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, new ItemStack(Items.OAK_PLANKS, 4));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.GLASS) >= 2,
                    "glass " + storehouse.count(Items.GLASS) + ", waiting for " + job.waitingFor()
                            + ", furnace input " + furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT)
                            + ", fuel " + furnaceSlot(helper, village, SmeltInFurnace.SLOT_FUEL));
            helper.assertTrue(storehouse.count(Items.SAND) >= 1,
                    "the stranded sand was not used: the storehouse is down to " + storehouse.count(Items.SAND) + " of 2");
            VillageTestSupport.remove(helper, village);
        });
    }

    /** A player's smelting in the village furnace is neither carried off nor counted as this batch's output. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_player_furnace", timeoutTicks = 1200)
    public static void leavesAPlayersSmeltingInTheFurnaceAlone(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.IRON_INGOT, 20));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.runAfterDelay(900, () -> {
            long stolen = storehouse.count(Items.IRON_INGOT);
            int left = furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT).getCount();
            long sand = storehouse.count(Items.SAND);
            String waiting = job.waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(stolen == 0, "the artisan put " + stolen + " of the player's iron ingots into the storehouse");
            helper.assertTrue(left == 20, "the player's result slot went from 20 to " + left);
            helper.assertTrue(sand == 2, "sand was loaded into a furnace the artisan had no business using: " + sand + " of 2 left");
            helper.assertTrue(waiting != null && waiting.contains("furnace"),
                    "a blocked furnace must be reported, but the artisan is waiting for " + waiting);
            helper.succeed();
        });
    }

    /** A smelt it cannot run today holds nothing up: the artisan takes the next order it can actually work on. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_skips_blocked", timeoutTicks = TRIP_TIMEOUT)
    public static void worksOnAnotherOrderWhileTheFurnaceIsBlocked(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        // Twenty logs, because a plank order of 57 needs fifteen of them before the planner has a step for it at all.
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village,
                Map.of(Items.SAND, 2, Items.COAL, 4, Items.OAK_LOG, 20));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        // Glass (2) is the smaller order, so the glass smelt is the first step planned; planks (57) come after it.
        plotShortOf(helper, village, blueprint, Blocks.GLASS, Blocks.OAK_PLANKS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.IRON_INGOT, 20));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.succeedWhen(() -> {
            helper.assertTrue(storehouse.count(Items.OAK_PLANKS) >= 4,
                    "planks " + storehouse.count(Items.OAK_PLANKS) + ": the blocked glass smelt froze the orders behind it,"
                            + " waiting for " + job.waitingFor() + ", orders " + village.artisanOrders());
            helper.assertTrue(storehouse.count(Items.IRON_INGOT) == 0, "the artisan took the player's iron ingots");
            VillageTestSupport.remove(helper, village);
        });
    }
}
