package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.craft.CraftStep;
import dev.andreymudri.villagercity.craft.SmeltInFurnace;
import dev.andreymudri.villagercity.craft.WorkshopService;
import dev.andreymudri.villagercity.job.ArtisanJob;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
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
    /** Two hundred ticks before the default villager schedule turns to REST at 12000. */
    private static final int EVENING = 11800;
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

    /** Turns the world clock on or off; {@code prepareArea} leaves it off, so a test about dusk has to ask for it. */
    private static void setDaylightCycle(GameTestHelper helper, boolean running) {
        helper.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(running, helper.getLevel().getServer());
    }

    /** Whether the villager has this item in hand: the trip is planned and under way, not merely decided on. */
    private static boolean carrying(Villager villager, Item item) {
        return Inventories.count(villager.getInventory(), stack -> stack.is(item)) > 0;
    }

    /**
     * What the furnace at this exact position holds. Tests that watch a furnace the village may walk away from have
     * to name it: {@link #furnaceSlot} follows the village to its next one, which is a different question.
     */
    private static ItemStack slotAt(GameTestHelper helper, BlockPos pos, int slot) {
        return helper.getLevel().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity
                ? entity.getItem(slot) : ItemStack.EMPTY;
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

    /**
     * Teleports the villager out of reach the first tick the batch is actually in the furnace, which is what a night
     * does: the brain walks it to bed and the task ticks again in the morning too far away to touch anything.
     */
    private static void interruptWhenLoaded(GameTestHelper helper, VillageData village, Villager villager, AtomicBoolean done) {
        if (done.get() || furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).isEmpty()) {
            return;
        }
        BlockPos bed = helper.absolutePos(new BlockPos(40, 1, 40));
        villager.getNavigation().stop();
        villager.teleportTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
        done.set(true);
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
     * A player's own glass sitting in the result slot, the very item the village wants, over order after order. The
     * village never starts a batch in a furnace that is not empty, so the player's stack is never touched -- and once
     * it has waited long enough it goes and smelts somewhere else rather than help itself. Anything it banks after
     * that is glass it made from its own eight sand, which is why the bound here is eight and not zero.
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
        BlockPos occupiedFurnace = village.furnacePos();
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
        AtomicBoolean reported = new AtomicBoolean();
        helper.onEachTick(() -> {
            String waiting = job.waitingFor();
            if (waiting != null && waiting.contains("glass") && waiting.contains("result")) {
                reported.set(true);
            }
        });
        helper.runAfterDelay(1500, () -> {
            long got = banked.get();
            int left = slotAt(helper, occupiedFurnace, SmeltInFurnace.SLOT_RESULT).getCount();
            boolean named = reported.get();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(left == 20, "the player's result slot went from 20 to " + left);
            helper.assertTrue(got <= 8, "the village banked " + got + " glass, more than its own eight sand could make,"
                    + " so some of it was the player's");
            helper.assertTrue(named, "the blocked result slot was never reported by slot and item");
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

        helper.runAfterDelay(300, () -> {
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
     * Fuel the village hands a shared furnace is spent. What is left of it once the batch is done is the same item
     * with the same count as a player's, so the village walks away from it rather than risk taking a stranger's coal
     * along with its own -- and it walks away from it even when it is unquestionably the village's own, because the
     * rule that makes that safe is "never touch that slot", not "work out whose it is".
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_fuel_spent", timeoutTicks = 1200)
    public static void fuelLeftInTheFurnaceIsNeverCarriedOff(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos furnace = village.furnacePos();
        // Two sand and four coal: one coal burns the batch through and three are still in the slot at the end.
        Villager villager = artisanRunning(helper, village, relative(helper, furnace).getX() + 1, relative(helper, furnace).getZ(),
                new ScriptedJob(new SmeltInFurnace(furnace, Items.SAND, 2, Items.COAL, 4)));
        villager.getInventory().addItem(new ItemStack(Items.SAND, 2));
        villager.getInventory().addItem(new ItemStack(Items.COAL, 4));

        helper.runAfterDelay(600, () -> {
            int fuelLeft = furnaceSlot(helper, village, SmeltInFurnace.SLOT_FUEL).getCount();
            int carriedCoal = Inventories.count(villager.getInventory(), stack -> stack.is(Items.COAL));
            int carriedGlass = Inventories.count(villager.getInventory(), stack -> stack.is(Items.GLASS));
            long bankedCoal = storehouse.count(Items.COAL);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(carriedGlass == 2, "the batch's own output was not collected: " + carriedGlass + " glass of 2");
            helper.assertTrue(fuelLeft == 3, "the fuel slot holds " + fuelLeft + " coal, not the 3 the batch left burning there");
            helper.assertTrue(carriedCoal == 0 && bankedCoal == 0, "the village emptied the fuel slot: " + carriedCoal
                    + " coal carried, " + bankedCoal + " banked");
            helper.succeed();
        });
    }

    /** A player's own smelt running in the shared furnace: input, fuel and output all theirs, none of it touched. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_player_smelt", timeoutTicks = 1200)
    public static void aPlayersRunningSmeltIsLeftAlone(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        // The same items the village itself smelts, in the same slots, in counts a village batch could have put
        // there. None of that is evidence of anything: the village did not watch it happen, so it is not its own.
        seedFurnace(helper, village, SmeltInFurnace.SLOT_INPUT, new ItemStack(Items.SAND, 4));
        seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, new ItemStack(Items.COAL, 4));
        seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.GLASS, 4));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.runAfterDelay(400, () -> {
            long glass = storehouse.count(Items.GLASS);
            long sand = storehouse.count(Items.SAND);
            String waiting = job.waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(glass == 0, "the village helped itself to " + glass + " glass of a player's smelt");
            helper.assertTrue(sand == 2, "the village loaded a furnace a player was using: sand " + sand + " of 2 left");
            helper.assertTrue(waiting != null && waiting.contains("furnace"),
                    "a furnace in use must be reported, but the artisan is waiting for " + waiting);
            helper.succeed();
        });
    }

    /**
     * The whole sequence with nothing hand-seeded but the player's part: a real order, a real trip, a night, and then
     * a player who empties the furnace and starts their own smelt in it. The village's receipt is now worthless and
     * has to be treated as such.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_player_reclaims", timeoutTicks = 2400)
    public static void aPlayerWhoTakesOverTheFurnaceAfterANightKeepsTheirSmelt(GameTestHelper helper) {
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
        AtomicBoolean tookOver = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!interrupted.get()) {
                interruptWhenLoaded(helper, village, villager, interrupted);
                return;
            }
            if (!tookOver.get() && !furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).isEmpty()) {
                // The player empties all three slots and puts their own smelt on.
                seedFurnace(helper, village, SmeltInFurnace.SLOT_INPUT, ItemStack.EMPTY);
                seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, ItemStack.EMPTY);
                seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.GLASS, 20));
                tookOver.set(true);
            }
        });
        helper.runAfterDelay(2000, () -> {
            boolean ran = interrupted.get() && tookOver.get();
            long banked = storehouse.count(Items.GLASS);
            int left = furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT).getCount();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(ran, "the sequence never happened: interrupted " + interrupted.get() + ", taken over " + tookOver.get());
            helper.assertTrue(banked == 0, "the village came back for a batch it had walked away from and banked "
                    + banked + " of the player's glass instead");
            helper.assertTrue(left == 20, "the player's result slot went from 20 to " + left);
            helper.succeed();
        });
    }

    /** Giving a batch up must not sweep a player's raw stock out of the input slot along with it. */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_player_input", timeoutTicks = 1200)
    public static void aPlayersRawStockIsNeverReclaimed(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        // Eight sand a player left in a cold furnace: exactly what the village smelts, and none of it the village's.
        seedFurnace(helper, village, SmeltInFurnace.SLOT_INPUT, new ItemStack(Items.SAND, 8));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.runAfterDelay(400, () -> {
            long sand = storehouse.count(Items.SAND);
            int left = furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).getCount();
            String waiting = job.waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(sand == 2, "the village mixed its own sand into a furnace holding a player's: "
                    + sand + " of 2 left");
            helper.assertTrue(left == 8, "the player's input slot went from 8 to " + left);
            helper.assertTrue(waiting != null && waiting.contains("furnace") && waiting.contains("input")
                            && waiting.contains("sand"),
                    "a furnace holding a player's stock must be named by slot and item, not silently skipped: " + waiting);
            helper.succeed();
        });
    }

    /**
     * A furnace a player starts using while the villager is already walking to it is not adopted on arrival. The
     * player's sand is the same item the village was about to load and half of what it was going to load, so nothing
     * about it looks out of place: the only thing that refuses it is the rule that a batch starts on an empty
     * furnace, and that has to be checked at the furnace, because the plan that passed back at the storehouse was
     * made before the player touched anything.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_walk_window_smelt", timeoutTicks = 1600)
    public static void aFurnaceFilledDuringTheWalkIsNotAdopted(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos shared = village.furnacePos();
        ArtisanJob job = new ArtisanJob();
        Villager villager = artisanRunning(helper, village, 22, 22, job);

        AtomicBoolean filled = new AtomicBoolean();
        AtomicBoolean adopted = new AtomicBoolean();
        helper.onEachTick(() -> {
            // Not merely once there are orders: once the sand is in the villager's hands. By then the trip is
            // planned and walking, so nothing but the check made at the furnace itself is left to catch this.
            if (!filled.get() && carrying(villager, Items.SAND)) {
                seedFurnace(helper, village, SmeltInFurnace.SLOT_INPUT, new ItemStack(Items.SAND, 1));
                seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, new ItemStack(Items.COAL, 1));
                filled.set(true);
            }
            // The village adding its own sand on top of the player's is what adoption looks like from outside.
            if (filled.get() && slotAt(helper, shared, SmeltInFurnace.SLOT_INPUT).getCount() > 1) {
                adopted.set(true);
            }
        });
        // Read before the village gives this furnace up as hopeless and goes to build itself another one.
        helper.runAfterDelay(500, () -> {
            boolean ran = filled.get();
            boolean claimed = adopted.get();
            long banked = storehouse.count(Items.GLASS);
            // The player's own coal smelts their own sand while the village stands off, so their one item is in the
            // furnace either as sand or as glass; what matters is that it is still theirs and still in there.
            int left = slotAt(helper, shared, SmeltInFurnace.SLOT_INPUT).getCount()
                    + slotAt(helper, shared, SmeltInFurnace.SLOT_RESULT).getCount();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(ran, "the villager never set off carrying the batch");
            helper.assertFalse(claimed, "the village loaded its batch into the player's smelt, waiting for " + job.waitingFor());
            helper.assertTrue(banked == 0, "the village banked " + banked + " glass smelted from a player's own sand and coal");
            helper.assertTrue(left == 1, "the player's one item went from 1 to " + left + " across the furnace");
            helper.succeed();
        });
    }

    /**
     * Output a player drops in while the villager is already walking is not counted as the batch's own, even when it
     * is exactly the item the batch was going to produce and well inside what it was going to produce of it. The
     * only thing that refuses it is the rule that a batch starts on a furnace with an empty result slot.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_walk_window_output", timeoutTicks = 1600)
    public static void outputDroppedInDuringTheWalkIsNotAdopted(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos shared = village.furnacePos();
        ArtisanJob job = new ArtisanJob();
        Villager villager = artisanRunning(helper, village, 22, 22, job);

        AtomicBoolean dropped = new AtomicBoolean();
        AtomicBoolean adopted = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!dropped.get() && carrying(villager, Items.SAND)) {
                seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.GLASS, 1));
                dropped.set(true);
            }
            if (dropped.get() && !slotAt(helper, shared, SmeltInFurnace.SLOT_INPUT).isEmpty()) {
                adopted.set(true);
            }
        });
        helper.runAfterDelay(500, () -> {
            boolean ran = dropped.get();
            boolean claimed = adopted.get();
            long banked = storehouse.count(Items.GLASS);
            int left = slotAt(helper, shared, SmeltInFurnace.SLOT_RESULT).getCount();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(ran, "the villager never set off carrying the batch");
            helper.assertFalse(claimed, "the village started a batch over the player's glass, waiting for " + job.waitingFor());
            helper.assertTrue(banked == 0, "the village banked " + banked + " of the player's glass, waiting for " + job.waitingFor());
            helper.assertTrue(left == 1, "the player's result slot went from 1 to " + left);
            helper.succeed();
        });
    }

    /**
     * An evening is no time to start a batch: the villager would be walked off to bed halfway through and leave the
     * village's stock sitting in a shared furnace overnight. The cheapest way not to have to recover from that is not
     * to get into it, so a batch that cannot finish before rest is not begun at all.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_daylight", timeoutTicks = 800)
    public static void noBatchIsStartedWithoutDaylightToFinishIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);
        // Nothing may sit between changing the clock and the callback that puts it back: an assertion thrown in
        // between would leave every later test in this run running at dusk.
        boolean cycled = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        setDaylightCycle(helper, true);
        // Two hundred ticks short of the villager's rest, against a batch that needs nine hundred.
        helper.getLevel().setDayTime(EVENING);

        helper.runAfterDelay(300, () -> {
            helper.getLevel().setDayTime(GameTestSupport.DAY_TIME);
            setDaylightCycle(helper, cycled);
            String waiting = job.waitingFor();
            boolean claimed = !furnaceSlot(helper, village, SmeltInFurnace.SLOT_FUEL).isEmpty();
            long sand = storehouse.count(Items.SAND);
            boolean furnaceUsed = !furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).isEmpty();
            VillageTestSupport.remove(helper, village);
            helper.assertFalse(claimed, "a batch was started with no daylight left to finish it: fuel slot "
                    + furnaceSlot(helper, village, SmeltInFurnace.SLOT_FUEL));
            helper.assertFalse(furnaceUsed, "the furnace was loaded on the eve of the villager's rest");
            helper.assertTrue(sand == 2, "sand left the storehouse for a batch that cannot finish: " + sand + " of 2");
            helper.assertTrue(waiting != null && waiting.contains("daylight"),
                    "the artisan must say it is out of daylight, but it is waiting for " + waiting);
            helper.succeed();
        });
    }

    /**
     * A clock that is not running is not an evening. With {@code doDaylightCycle} off the villager's schedule stays
     * where it is for ever, so refusing to smelt because rest is close by would idle the artisan for the rest of the
     * world rather than for one evening.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_frozen_clock", timeoutTicks = 2400)
    public static void aFrozenClockIsNoReasonToRefuseABatch(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = artisan(helper, village, 22, 22);
        // The same dusk as the test above, with the clock stopped: it will still be dusk in a thousand ticks.
        setDaylightCycle(helper, false);
        helper.getLevel().setDayTime(EVENING);

        // A fixed wait, not succeedWhen: the clock has to stay at dusk for the whole trip, and putting it back is
        // exactly what a per-tick callback would do on its first tick.
        helper.runAfterDelay(2000, () -> {
            helper.getLevel().setDayTime(GameTestSupport.DAY_TIME);
            long glass = storehouse.count(Items.GLASS);
            String waiting = job.waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(glass >= 2, "the artisan sat out a night that will never come: glass " + glass
                    + ", waiting for " + waiting);
            helper.succeed();
        });
    }

    /**
     * A world saved while the village still recorded what it had left in the furnace has that field in its works tag,
     * and must load anyway -- without it, and without carrying any of it forward. There is nothing to carry forward:
     * the whole point of deleting the record is that it was never evidence of anything.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_claim_old_save")
    public static void aSaveThatRecordedAFurnaceClaimStillLoads(GameTestHelper helper) {
        // Exactly the works tag the version with a receipt in it wrote.
        CompoundTag works = new CompoundTag();
        works.put("crafting_table", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, new BlockPos(1, 2, 3)).getOrThrow());
        works.put("furnace", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, new BlockPos(4, 5, 6)).getOrThrow());
        CompoundTag claim = new CompoundTag();
        claim.putString("input", "minecraft:sand");
        claim.putInt("input_count", 2);
        claim.putString("fuel", "minecraft:coal");
        claim.putInt("fuel_count", 1);
        claim.putString("output", "minecraft:glass");
        claim.putInt("output_count", 2);
        works.put("furnace_claim", claim);

        VillageWorks decoded = VillageWorks.CODEC.parse(NbtOps.INSTANCE, works).getOrThrow();

        helper.assertTrue(decoded.furnace().isPresent() && decoded.craftingTable().isPresent(),
                "a works tag with a receipt in it lost the rest of itself: " + decoded);
        // And the round trip back out drops it, so the field never reappears in a save this version writes.
        CompoundTag written = (CompoundTag) VillageWorks.CODEC.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow();
        helper.assertFalse(written.contains("furnace_claim"), "the village wrote a furnace receipt back out: " + written);
        helper.succeed();
    }

    /**
     * Output a player drops into the result slot while the batch is cooking is never taken, however much of it there
     * is and however exactly it matches what the village is making. The village counts one output for each of its own
     * input items it watches disappear, and a stack that appears without any input disappearing is worth nothing.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_drop_in", timeoutTicks = 1200)
    public static void outputDroppedInMidBatchIsNeverTaken(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos furnace = village.furnacePos();
        Villager villager = artisanRunning(helper, village, relative(helper, furnace).getX() + 1, relative(helper, furnace).getZ(),
                new ScriptedJob(new SmeltInFurnace(furnace, Items.SAND, 2, Items.COAL, 1)));
        villager.getInventory().addItem(new ItemStack(Items.SAND, 2));
        villager.getInventory().addItem(new ItemStack(Items.COAL, 1));

        AtomicBoolean dropped = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!dropped.get() && !furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).isEmpty()) {
                // A whole stack of the very item the batch makes, straight into the slot it will appear in.
                seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.GLASS, 20));
                dropped.set(true);
            }
        });
        helper.runAfterDelay(600, () -> {
            boolean ran = dropped.get();
            int carried = Inventories.count(villager.getInventory(), stack -> stack.is(Items.GLASS));
            int left = furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT).getCount();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(ran, "the player never got to drop anything in");
            helper.assertTrue(carried == 2, "the villager carried off " + carried + " glass, where only the 2 it"
                    + " watched its own sand become are its own");
            helper.assertTrue(left == 20, "the player's stack went from 20 to " + left);
            helper.succeed();
        });
    }

    /**
     * A hopper under the furnace takes the output as fast as it appears, with nobody doing anything wrong. The
     * village loses the batch -- there is nothing to be done about that -- and the point is what happens next: a
     * player's glass turning up in that same slot afterwards is not the village's, and no amount of "we put two sand
     * in and got nothing back" makes it so.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_hopper_present", timeoutTicks = 1600)
    public static void aHopperDrainingTheOutputLeavesTheVillageWithNothingToClaim(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos furnace = village.furnacePos();
        Villager villager = artisanRunning(helper, village, relative(helper, furnace).getX() + 1, relative(helper, furnace).getZ(),
                new ScriptedJob(new SmeltInFurnace(furnace, Items.SAND, 2, Items.COAL, 1)));
        villager.getInventory().addItem(new ItemStack(Items.SAND, 2));
        villager.getInventory().addItem(new ItemStack(Items.COAL, 1));

        AtomicLong drained = new AtomicLong();
        AtomicLong since = new AtomicLong();
        AtomicBoolean refilled = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (refilled.get()) {
                return;
            }
            if (drained.get() >= 2) {
                // The batch is gone. A while later -- a player is not a hopper and does not act on the same tick the
                // hopper empties the slot -- somebody smelts two of their own in the same furnace.
                if (since.incrementAndGet() > 20) {
                    seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.GLASS, 2));
                    refilled.set(true);
                }
                return;
            }
            ItemStack result = furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT);
            if (!result.isEmpty()) {
                drained.addAndGet(result.getCount());
                seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, ItemStack.EMPTY);
            }
        });
        helper.runAfterDelay(900, () -> {
            boolean ran = refilled.get();
            int carried = Inventories.count(villager.getInventory(), stack -> stack.is(Items.GLASS));
            int left = furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT).getCount();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(ran, "the hopper never took the batch: drained " + drained.get() + " of 2");
            helper.assertTrue(carried == 0, "the villager took " + carried + " glass it never watched itself make");
            helper.assertTrue(left == 2, "the player's glass went from 2 to " + left);
            helper.succeed();
        });
    }

    /**
     * The same hopper, emptying the furnace while the villager is away at its bed -- which is when a village that
     * writes down what it left behind is at its most confident and most wrong. Nothing is written down, so there is
     * nothing to come back for, and what a player smelts in the meantime stays theirs.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_hopper_away", timeoutTicks = 2400)
    public static void aFurnaceEmptiedWhileTheVillagerSleepsIsNeverReclaimed(GameTestHelper helper) {
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
        AtomicBoolean drained = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!interrupted.get()) {
                interruptWhenLoaded(helper, village, villager, interrupted);
                return;
            }
            if (!drained.get()) {
                // The hopper empties every slot while the villager is at its bed, then a player smelts their own two.
                seedFurnace(helper, village, SmeltInFurnace.SLOT_INPUT, ItemStack.EMPTY);
                seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, ItemStack.EMPTY);
                seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.GLASS, 2));
                drained.set(true);
            }
        });
        helper.runAfterDelay(2000, () -> {
            boolean ran = interrupted.get() && drained.get();
            long banked = storehouse.count(Items.GLASS);
            int left = furnaceSlot(helper, village, SmeltInFurnace.SLOT_RESULT).getCount();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(ran, "the sequence never happened: interrupted " + interrupted.get() + ", drained " + drained.get());
            helper.assertTrue(banked == 0, "the village banked " + banked + " of the player's glass for a batch it"
                    + " walked away from, waiting for " + job.waitingFor());
            helper.assertTrue(left == 2, "the player's glass went from 2 to " + left);
            helper.succeed();
        });
    }

    /**
     * An interrupted batch is over. The villager walks off to bed mid-smelt and the furnace keeps everything it
     * holds: the village neither takes it back nor counts it later. That is the cost of only ever claiming what it
     * watched itself make, and it is paid in the village's own sand.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_abandoned", timeoutTicks = 2400)
    public static void anInterruptedBatchIsAbandonedWhereItStands(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        ArtisanJob job = new ArtisanJob();
        Villager villager = artisanRunning(helper, village, 22, 22, job);
        BlockPos abandoned = village.furnacePos();

        AtomicBoolean interrupted = new AtomicBoolean();
        helper.onEachTick(() -> interruptWhenLoaded(helper, village, villager, interrupted));
        helper.runAfterDelay(1200, () -> {
            boolean ran = interrupted.get();
            int glass = helper.getLevel().getBlockEntity(abandoned) instanceof AbstractFurnaceBlockEntity entity
                    ? entity.getItem(SmeltInFurnace.SLOT_RESULT).getCount() : -1;
            long banked = storehouse.count(Items.GLASS);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(ran, "the batch was never interrupted; waiting for " + job.waitingFor());
            helper.assertTrue(glass == 2, "the abandoned furnace holds " + glass + " glass, not the 2 the batch left"
                    + " in it: the village went back for what it had walked away from");
            helper.assertTrue(banked == 0, "the village banked " + banked + " glass out of a batch nobody watched finish");
            helper.succeed();
        });
    }

    /**
     * A furnace the village cannot use must never be able to stop it working for good. It says what is in the way for
     * as long as it can stand to, and then builds itself another furnace and leaves that one, and everything in it,
     * for whoever put it there. Without this, one chunk unloading at the wrong moment would mean a player had to come
     * and empty a furnace by hand before the village could ever smelt again.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_furnace_escape", timeoutTicks = 2400)
    public static void aFurnaceThatStaysBlockedIsLeftBehind(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 4));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos blocked = village.furnacePos();
        seedFurnace(helper, village, SmeltInFurnace.SLOT_RESULT, new ItemStack(Items.IRON_INGOT, 20));
        ArtisanJob job = artisan(helper, village, 22, 22);

        AtomicBoolean named = new AtomicBoolean();
        helper.onEachTick(() -> {
            String waiting = job.waitingFor();
            if (waiting != null && waiting.contains(blocked.toShortString()) && waiting.contains("result")
                    && waiting.contains("iron_ingot")) {
                named.set(true);
            }
        });
        helper.succeedWhen(() -> {
            BlockPos now = village.furnacePos();
            helper.assertTrue(named.get(), "the blocked furnace was never named by position, slot and item");
            helper.assertTrue(now != null && !now.equals(blocked), "the village is still stuck on the furnace at "
                    + blocked.toShortString() + ", waiting for " + job.waitingFor());
            helper.assertTrue(storehouse.count(Items.GLASS) >= 2, "the village never smelted after moving its furnace:"
                    + " glass " + storehouse.count(Items.GLASS) + ", waiting for " + job.waitingFor());
            int leftBehind = helper.getLevel().getBlockEntity(blocked) instanceof AbstractFurnaceBlockEntity entity
                    ? entity.getItem(SmeltInFurnace.SLOT_RESULT).getCount() : -1;
            helper.assertTrue(leftBehind == 20, "the player's 20 iron ingots became " + leftBehind + " in the furnace"
                    + " the village walked away from");
            VillageTestSupport.remove(helper, village);
        });
    }

    /**
     * A batch is not started over a fuel a player left, even a fuel the village would have chosen itself. Inheriting
     * what is in that slot is how the village ends up burning a stranger's coal, and -- since it never empties that
     * slot -- how a lava bucket swapped for an empty one at ignition strands the batch on top of it.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_left_fuel", timeoutTicks = 1200)
    public static void aBatchIsNotStartedOverAFuelAPlayerLeft(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Map.of(Items.SAND, 2, Items.COAL, 8));
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        village.setCitizen(UUID.randomUUID(), JobType.BUILDER);
        plotShortOf(helper, village, blueprint, Blocks.GLASS);
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        // Coal: exactly what the village would have brought, and still not the village's to burn.
        seedFurnace(helper, village, SmeltInFurnace.SLOT_FUEL, new ItemStack(Items.COAL, 4));
        ArtisanJob job = artisan(helper, village, 22, 22);

        helper.runAfterDelay(400, () -> {
            int fuel = furnaceSlot(helper, village, SmeltInFurnace.SLOT_FUEL).getCount();
            boolean loaded = !furnaceSlot(helper, village, SmeltInFurnace.SLOT_INPUT).isEmpty();
            long sand = storehouse.count(Items.SAND);
            String waiting = job.waitingFor();
            VillageTestSupport.remove(helper, village);
            helper.assertFalse(loaded, "the village started a batch on top of a player's fuel");
            helper.assertTrue(fuel == 4, "the player's fuel slot went from 4 coal to " + fuel);
            helper.assertTrue(sand == 2, "sand left the storehouse for a furnace the village may not use: " + sand + " of 2");
            helper.assertTrue(waiting != null && waiting.contains("fuel") && waiting.contains("coal"),
                    "the fuel a player left must be named by slot and item, but the artisan is waiting for " + waiting);
            helper.succeed();
        });
    }

    /**
     * Fuel goes in only together with something to smelt. Fuel put into a shared furnace can never come out again --
     * nothing removes anything from that slot -- so handing it over for a batch that is not there is simply losing
     * it, and standing over a furnace the village has put nothing into is how it comes to call what turns up its own.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_no_batch", timeoutTicks = 800)
    public static void noFuelIsHandedOverWithoutABatchToSmelt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, RADIUS, false);
        BuilderTests.stockedStorehouse(helper, village, Map.of());
        WorkshopService.ensureWorkshop(helper.getLevel(), village);
        BlockPos furnace = village.furnacePos();
        // The trip was planned for two sand, and the storehouse had none left by the time the villager got there.
        Villager villager = artisanRunning(helper, village, relative(helper, furnace).getX() + 1, relative(helper, furnace).getZ(),
                new ScriptedJob(new SmeltInFurnace(furnace, Items.SAND, 2, Items.COAL, 1)));
        villager.getInventory().addItem(new ItemStack(Items.COAL, 1));

        helper.runAfterDelay(200, () -> {
            int inSlot = furnaceSlot(helper, village, SmeltInFurnace.SLOT_FUEL).getCount();
            int carried = Inventories.count(villager.getInventory(), stack -> stack.is(Items.COAL));
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(inSlot == 0, "coal went into the furnace for a batch that was never there: " + inSlot);
            helper.assertTrue(carried == 1, "the villager lost its coal: " + carried + " of 1 still in hand");
            helper.succeed();
        });
    }

    /**
     * However much the village is short of, one trip never puts more than {@link ArtisanJob#MAX_SMELT_BATCH} into a
     * shared furnace. A batch is everything the village stands to lose if the watch breaks, so it is kept small, and
     * the cap binds regardless of what the planner asked for. No shipped blueprint asks for more than two panes, so
     * this is asked of the sizing rule directly rather than through a village.
     */
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_artisan_batch_cap")
    public static void aSmeltingTripIsCappedAtTheBatchSize(GameTestHelper helper) {
        // The recipe is never read when a step is resized, so this asks the rule exactly what the loop asks it.
        CraftStep huge = new CraftStep(CraftStep.Kind.SMELT, null, Map.of(Items.SAND, 40), Items.GLASS, 40, 40);
        CraftStep small = new CraftStep(CraftStep.Kind.SMELT, null, Map.of(Items.SAND, 3), Items.GLASS, 3, 3);
        CraftStep planks = new CraftStep(CraftStep.Kind.CRAFT, null, Map.of(Items.OAK_LOG, 40), Items.OAK_PLANKS, 160, 40);

        CraftStep cappedSmelt = ArtisanJob.trimmed(huge);
        CraftStep keptSmelt = ArtisanJob.trimmed(small);
        CraftStep cappedCraft = ArtisanJob.trimmed(planks);

        helper.assertTrue(cappedSmelt.times() == ArtisanJob.MAX_SMELT_BATCH, "a 40-item smelt was trimmed to "
                + cappedSmelt.times() + ", not the batch cap of " + ArtisanJob.MAX_SMELT_BATCH);
        helper.assertTrue(cappedSmelt.inputs().get(Items.SAND) == ArtisanJob.MAX_SMELT_BATCH,
                "the trimmed smelt still withdraws " + cappedSmelt.inputs().get(Items.SAND) + " sand");
        helper.assertTrue(keptSmelt.times() == 3, "a batch already under the cap was resized to " + keptSmelt.times());
        helper.assertTrue(cappedCraft.times() > ArtisanJob.MAX_SMELT_BATCH, "the furnace batch cap was applied to a"
                + " crafting step as well: " + cappedCraft.times() + " runs");
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

        // Read it before the village runs out of patience and goes to build a furnace somewhere else.
        helper.runAfterDelay(400, () -> {
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
