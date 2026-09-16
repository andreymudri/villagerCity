package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageTicker;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class ProtectionTests {
    private static final BlockPos BELL = new BlockPos(2, 1, 2);
    private static final BlockPos VILLAGE_BELL = new BlockPos(24, 1, 24);
    /** Absolute positions a protection mod would guard; the listeners below cancel block changes there. */
    private static final Set<BlockPos> PROTECTED_BREAK = ConcurrentHashMap.newKeySet();
    private static final Set<BlockPos> PROTECTED_PLACE = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((LivingDestroyBlockEvent e) -> {
            if (PROTECTED_BREAK.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (PROTECTED_PLACE.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    private static void setMobGriefing(GameTestHelper helper, boolean value) {
        helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(value, helper.getLevel().getServer());
    }

    private static boolean mobGriefing(GameTestHelper helper) {
        return helper.getLevel().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_protect_break_griefing")
    public static void breakBlockRespectsMobGriefing(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        BlockPos log = new BlockPos(12, 1, 10);
        helper.setBlock(log, Blocks.OAK_LOG);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        boolean previous = mobGriefing(helper);
        setMobGriefing(helper, false);
        ScriptedJob job = new ScriptedJob(new BreakBlock(helper.absolutePos(log)));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), job);
        helper.succeedWhen(() -> {
            helper.assertFalse(job.results.isEmpty(), "task still running");
            setMobGriefing(helper, previous);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(job.results.equals(List.of(Task.Status.FAILED)), "results " + job.results);
            helper.assertBlockPresent(Blocks.OAK_LOG, log);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_protect_break_event")
    public static void breakBlockRespectsCancelledDestroyEvent(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.assertTrue(mobGriefing(helper), "mobGriefing is off; this test needs the default");
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        BlockPos log = new BlockPos(12, 1, 10);
        helper.setBlock(log, Blocks.OAK_LOG);
        BlockPos logAbs = helper.absolutePos(log);
        PROTECTED_BREAK.add(logAbs);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        ScriptedJob job = new ScriptedJob(new BreakBlock(logAbs));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), job);
        helper.succeedWhen(() -> {
            helper.assertFalse(job.results.isEmpty(), "task still running");
            PROTECTED_BREAK.remove(logAbs);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(job.results.equals(List.of(Task.Status.FAILED)), "results " + job.results);
            helper.assertBlockPresent(Blocks.OAK_LOG, log);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_protect_place_event")
    public static void placeBlockRespectsCancelledPlaceEvent(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.assertTrue(mobGriefing(helper), "mobGriefing is off; this test needs the default");
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        BlockPos target = new BlockPos(12, 1, 10);
        BlockPos targetAbs = helper.absolutePos(target);
        PROTECTED_PLACE.add(targetAbs);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 1));
        ScriptedJob job = new ScriptedJob(new PlaceBlock(targetAbs, Blocks.COBBLESTONE.defaultBlockState(), Items.COBBLESTONE));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertFalse(job.results.isEmpty(), "task still running");
            PROTECTED_PLACE.remove(targetAbs);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(job.results.equals(List.of(Task.Status.FAILED)), "results " + job.results);
            helper.assertBlockPresent(Blocks.AIR, target);
            int carried = Inventories.count(villager.getInventory(), s -> s.is(Items.COBBLESTONE));
            helper.assertTrue(carried == 1, "cobblestone carried " + carried);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_protect_storehouse_rails")
    public static void storehouseIsNotPlacedOverPlayerBlocks(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, VILLAGE_BELL, 8, false);
        for (int x = 20; x <= 28; x++) {
            for (int z = 20; z <= 28; z++) {
                if (x != VILLAGE_BELL.getX() || z != VILLAGE_BELL.getZ()) {
                    helper.setBlock(x, 1, z, Blocks.POWERED_RAIL);
                }
            }
        }
        VillageTicker.tickVillage(helper.getLevel(), village);
        BlockPos placed = village.storehousePos();
        VillageTestSupport.remove(helper, village);
        for (int x = 20; x <= 28; x++) {
            for (int z = 20; z <= 28; z++) {
                if (x != VILLAGE_BELL.getX() || z != VILLAGE_BELL.getZ()) {
                    helper.assertBlockPresent(Blocks.POWERED_RAIL, new BlockPos(x, 1, z));
                }
            }
        }
        if (placed != null) {
            BlockPos rel = placed.subtract(helper.absolutePos(BlockPos.ZERO));
            boolean onRail = rel.getY() == 1 && rel.getX() >= 20 && rel.getX() <= 28 && rel.getZ() >= 20 && rel.getZ() <= 28
                    && !(rel.getX() == VILLAGE_BELL.getX() && rel.getZ() == VILLAGE_BELL.getZ());
            helper.assertFalse(onRail, "storehouse placed on a rail cell " + rel);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_protect_storehouse_drop")
    public static void storehouseDropsNothingWhenBroken(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, VILLAGE_BELL, 8, false);
        VillageTicker.tickVillage(helper.getLevel(), village);
        BlockPos pos = village.storehousePos();
        if (pos == null) {
            VillageTestSupport.remove(helper, village);
            helper.fail("no storehouse placed");
            return;
        }
        helper.getLevel().destroyBlock(pos, true);
        helper.runAfterDelay(5, () -> {
            VillageTestSupport.remove(helper, village);
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3),
                    e -> e.getItem().is(StorehouseContent.ITEM.get()));
            helper.assertTrue(drops.isEmpty(), "storehouse dropped itself: " + drops.size() + " item entities");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_protect_storehouse_griefing")
    public static void storehouseRespectsMobGriefing(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, VILLAGE_BELL, 8, false);
        boolean previous = mobGriefing(helper);
        setMobGriefing(helper, false);
        BlockPos placed;
        try {
            VillageTicker.tickVillage(helper.getLevel(), village);
            placed = village.storehousePos();
        } finally {
            setMobGriefing(helper, previous);
            VillageTestSupport.remove(helper, village);
        }
        helper.assertTrue(placed == null, "storehouse placed with mobGriefing off at " + placed);
        helper.succeed();
    }
}
