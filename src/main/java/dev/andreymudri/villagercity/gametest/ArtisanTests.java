package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.craft.SmeltInFurnace;
import dev.andreymudri.villagercity.craft.WorkshopService;
import dev.andreymudri.villagercity.job.ArtisanJob;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.VillageWorks;
import dev.andreymudri.villagercity.village.VillageWorks.FurnaceClaim;
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
     * A night interrupts a trip mid-smelt: the brain walks the villager to bed, so the task fails out of reach with
     * the whole batch still in the furnace and the storehouse emptied of the sand that paid for it. The next trip has
     * to finish that batch from the village's recorded claim alone, because there is nothing left to re-plan it from.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_interrupted", timeoutTicks = TRIP_TIMEOUT)
    public static void aTripInterruptedMidSmeltIsResumedAndTheOrderCompletes(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = new ArtisanJob();
        Villager villager = artisanRunning(helper, village, 22, 22, job);

        AtomicBoolean interrupted = new AtomicBoolean();
        helper.onEachTick(() -> {
            // The first tick the batch is actually in the furnace, put the villager where a bed would: far out of
            // reach. The task fails there, and the sand it withdrew is gone from the storehouse for good.
            if (!interrupted.get() && !furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).isEmpty()) {
                BlockPos bed = helper.absolutePos(new BlockPos(40, 1, 40));
                villager.getNavigation().stop();
                villager.teleportTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
                interrupted.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(interrupted.get(), "the smelt was never interrupted; waiting for " + job.waitingFor());
            helper.assertTrue(storehouse.count(Items.GLASS) >= 2,
                    "glass " + storehouse.count(Items.GLASS) + ", waiting for " + job.waitingFor()
                            + ", claim " + village.furnaceClaim()
                            + ", furnace input " + furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT)
                            + ", result " + furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT));
            helper.assertTrue(storehouse.count(Items.SAND) == 0,
                    "the storehouse still holds " + storehouse.count(Items.SAND) + " sand, so the batch was re-bought rather than resumed");
            helper.assertTrue(village.furnaceClaim() == null, "the claim outlived the batch: " + village.furnaceClaim());
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * A player's own glass sitting in the result slot, the very item the village wants, over order after order. The
     * village never opens a claim on a furnace that is not empty, so none of it is ever banked.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_player_output", timeoutTicks = 1600)
    public static void aPlayersOutputIsNeverTakenOverRepeatedOrders(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 8, Items.COAL, 8));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.GLASS, 20));
        ArtisanJob job = artisan(helper, village, 22, 22);

        // A builder consuming every pane the storehouse gets, so the glass order comes back trip after trip.
        AtomicLong banked = new AtomicLong();
        helper.onEachTick(() -> {
            ItemStack taken = storehouse.extractForCitizen(Items.GLASS, 64);
            if (!taken.isEmpty()) {
                banked.addAndGet(taken.getCount());
            }
        });
        helper.runAfterDelay(1500, () -> {
            long stolen = banked.get();
            int left = furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT).getCount();
            long sand = storehouse.count(Items.SAND);
            String waiting = job.waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(stolen == 0, "the village banked " + stolen + " of the player's glass over repeated orders");
            helper.assertTrue(left == 20, "the player's result slot went from 20 to " + left);
            helper.assertTrue(sand == 8, "the village smelted into a furnace it had no claim on: sand " + sand + " of 8");
            helper.assertTrue(waiting != null && waiting.contains("glass"),
                    "the blocked result slot must be reported by item, but the artisan is waiting for " + waiting);
            helper.succeed();
        });
    }

    /**
     * One item of a fuel the storehouse cannot match must not wedge the furnace shut for good: it is named in
     * {@code waitingFor} so a player knows what to take out, and the village smelts as soon as it is gone.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_foreign_fuel", timeoutTicks = 2400)
    public static void aFuelTheStorehouseCannotMatchIsReportedAndThenCleared(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, new ItemStack(Items.BAMBOO, 1));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.runAfterDelay(600, () -> {
            String waiting = job.waitingFor();
            long sand = storehouse.count(Items.SAND);
            boolean untouched = furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).isEmpty() && sand == 2;
            boolean named = waiting != null && waiting.contains("bamboo") && waiting.contains("fuel");
            if (!named || !untouched) {
                VillageTestSupport.remove(helper, village);
            }
            helper.assertTrue(named, "the foreign fuel must be named with its slot, but the artisan is waiting for " + waiting);
            helper.assertTrue(untouched, "a doomed trip ran anyway: sand " + sand + " of 2, furnace input "
                    + furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT));
            // The player takes the bamboo back out; nothing else changes.
            seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, ItemStack.EMPTY);
            helper.succeedWhen(() -> {
                helper.assertTrue(storehouse.count(Items.GLASS) >= 2,
                        "the village never smelted after the bamboo was removed: glass " + storehouse.count(Items.GLASS)
                                + ", waiting for " + job.waitingFor());
                VillageTestSupport.remove(helper, village);
            });
        });
    }

    /**
     * A furnace slot holds 64 at most and {@code AbstractFurnaceBlockEntity.setItem} truncates silently, so a top-up
     * bigger than that would destroy fuel the villager already paid for. It stays in the villager's hands instead.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_fuel_cap", timeoutTicks = 400)
    public static void aFuelTopUpIsCappedAtAStack(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos furnace = village.furnacePos();
        seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, new ItemStack(Items.BAMBOO, 1));
        // Diamond is the input on purpose: nothing smelts it, so the furnace never lights and the fuel slot holds
        // exactly what the top-up put there. With a real smeltable the fuel burns down while the count is read.
        FurnaceClaim claim = new FurnaceClaim(Items.DIAMOND, 4, Items.BAMBOO, 80, Items.DIAMOND_BLOCK, 4);
        Villager villager = artisanRunning(helper, village, relative(helper, furnace).getX() + 1, relative(helper, furnace).getZ(),
                new ScriptedJob(new SmeltInFurnace(furnace, claim)));
        villager.getInventory().addItem(new ItemStack(Items.DIAMOND, 4));
        villager.getInventory().addItem(new ItemStack(Items.BAMBOO, 64));
        villager.getInventory().addItem(new ItemStack(Items.BAMBOO, 15));

        helper.runAfterDelay(80, () -> {
            int inSlot = furnaceSlot(helper, village, SmeltInFurnace.SLOT_FUEL).getCount();
            int carried = Inventories.count(villager.getInventory(), stack -> stack.is(Items.BAMBOO));
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(inSlot + carried == 80, "bamboo was destroyed: " + inSlot + " in the slot plus " + carried
                    + " carried, from the 80 the villager paid for");
            helper.assertTrue(inSlot == 64, "the fuel slot holds " + inSlot + ", not the stack it was capped to");
            helper.succeed();
        });
    }

    /** A player's renamed smeltable topped up by the village keeps its name: a top-up grows the stack, it does not replace it. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_components", timeoutTicks = 400)
    public static void aTopUpKeepsTheComponentsOfTheStackAlreadyInTheSlot(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos furnace = village.furnacePos();
        Component souvenir = Component.literal("Beach Souvenir");
        ItemStack named = new ItemStack(Items.SAND, 1);
        named.set(DataComponents.CUSTOM_NAME, souvenir);
        seedFurnace(helper, village, SmeltInFurnace.SLOT_INPUT, named);
        FurnaceClaim claim = new FurnaceClaim(Items.SAND, 4, Items.COAL, 1, Items.GLASS, 4);
        Villager villager = artisanRunning(helper, village, relative(helper, furnace).getX() + 1, relative(helper, furnace).getZ(),
                new ScriptedJob(new SmeltInFurnace(furnace, claim)));
        villager.getInventory().addItem(new ItemStack(Items.SAND, 3));
        villager.getInventory().addItem(new ItemStack(Items.COAL, 1));

        helper.runAfterDelay(80, () -> {
            ItemStack slot = furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT);
            Component name = slot.get(DataComponents.CUSTOM_NAME);
            int count = slot.getCount();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(count >= 1, "the input slot was emptied instead of topped up");
            helper.assertTrue(souvenir.equals(name), "the player's item name was rewritten to " + name);
            helper.succeed();
        });
    }

    /** The claim is village data, so it has to survive a save: it round-trips with everything else the village keeps. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_claim_codec")
    public static void aFurnaceClaimRoundTripsThroughTheVillageCodec(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        village.setFurnacePos(helper.absolutePos(new BlockPos(19, 1, 26)));
        FurnaceClaim claim = new FurnaceClaim(Items.SAND, 5, Items.COAL, 2, Items.GLASS, 5);
        village.setFurnaceClaim(claim);

        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        CompoundTag saved = registry.save(new CompoundTag(), helper.getLevel().registryAccess());
        VillageRegistry loaded = VillageRegistry.load(saved, helper.getLevel().registryAccess());
        VillageData copy = loaded.get(village.id());
        VillageTestSupport.remove(helper, village);

        helper.assertTrue(copy != null, "village lost on reload");
        helper.assertTrue(claim.equals(copy.furnaceClaim()), "furnace claim lost on reload: " + copy.furnaceClaim());
        helper.succeed();
    }

    /** A world saved before the claim existed has no such field, and must still load with no claim at all. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_claim_old_save")
    public static void anOldSaveWithNoFurnaceClaimLoads(GameTestHelper helper) {
        // Exactly the works tag an older save wrote: workshop positions, no furnace_claim field anywhere.
        CompoundTag works = new CompoundTag();
        works.put("crafting_table", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, new BlockPos(1, 2, 3)).getOrThrow());
        works.put("furnace", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, new BlockPos(4, 5, 6)).getOrThrow());

        VillageWorks decoded = VillageWorks.CODEC.parse(NbtOps.INSTANCE, works).getOrThrow();

        helper.assertTrue(decoded.furnaceClaim().isEmpty(), "an old save decoded a claim from nothing: " + decoded.furnaceClaim());
        helper.assertTrue(decoded.furnace().isPresent() && decoded.craftingTable().isPresent(),
                "the rest of an old works tag was lost: " + decoded);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        village.setFurnacePos(helper.absolutePos(new BlockPos(19, 1, 26)));
        boolean noClaim = village.furnaceClaim() == null;
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(noClaim, "a fresh village started out with a claim");
        helper.succeed();
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
