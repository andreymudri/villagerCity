package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.LumberjackJob;
import dev.andreymudri.villagercity.job.Replant;
import dev.andreymudri.villagercity.job.TreeFinder;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class TreeTargetingTests {
    private static final BlockPos BELL = new BlockPos(40, 1, 40);
    private static final BlockPos STORE = new BlockPos(22, 1, 24);
    private static final BlockPos REPLANT_BELL = new BlockPos(2, 1, 2);
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

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_tree_wall")
    public static void rejectsLogWallTouchingCanopy(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int x = 26; x <= 31; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 26), Blocks.OAK_LOG);
            }
        }
        for (BlockPos leaf : List.of(new BlockPos(32, 3, 26), new BlockPos(32, 4, 26), new BlockPos(32, 3, 27), new BlockPos(32, 3, 25))) {
            helper.setBlock(leaf, Blocks.OAK_LEAVES);
        }
        Optional<TreeFinder.Tree> tree = TreeFinder.trunk(helper.getLevel(), helper.absolutePos(new BlockPos(26, 1, 26)));
        helper.assertTrue(tree.isEmpty(), "log wall accepted as a tree with " + tree.map(t -> t.logs().size()).orElse(0) + " logs");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_tree_post")
    public static void rejectsPostWithFewLeaves(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(26, y, 26), Blocks.OAK_LOG);
        }
        helper.setBlock(new BlockPos(27, 3, 26), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(25, 3, 26), Blocks.OAK_LEAVES);
        Optional<TreeFinder.Tree> tree = TreeFinder.trunk(helper.getLevel(), helper.absolutePos(new BlockPos(26, 1, 26)));
        helper.assertTrue(tree.isEmpty(), "post with two leaves accepted as a tree");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_tree_ok")
    public static void acceptsPlantedTree(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        LumberjackTests.plantTree(helper, new BlockPos(26, 1, 26));
        Optional<TreeFinder.Tree> tree = TreeFinder.trunk(helper.getLevel(), helper.absolutePos(new BlockPos(26, 1, 26)));
        helper.assertTrue(tree.isPresent(), "planted tree rejected");
        helper.assertTrue(tree.get().logs().size() == 5, "planted tree logs " + tree.get().logs().size());
        for (int y = 1; y <= 5; y++) {
            helper.setBlock(new BlockPos(26, y, 26), Blocks.AIR);
        }
        helper.assertTrue(TreeFinder.trunk(helper.getLevel(), helper.absolutePos(new BlockPos(26, 1, 26))).isEmpty(), "felled tree's stump still a tree");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_tree_unreachable", timeoutTicks = 2000)
    public static void skipsUnreachableTree(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(26, y, 26), Blocks.DIRT);
        }
        LumberjackTests.plantTree(helper, new BlockPos(26, 4, 26));
        BlockPos reachable = new BlockPos(22, 1, 34);
        LumberjackTests.plantTree(helper, reachable);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, reachable);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_tree_protected", timeoutTicks = 2000)
    public static void skipsProtectedTree(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos near = new BlockPos(26, 1, 26);
        BlockPos far = new BlockPos(22, 1, 34);
        LumberjackTests.plantTree(helper, near);
        LumberjackTests.plantTree(helper, far);
        List<BlockPos> guarded = List.of(near, near.above(1), near.above(2), near.above(3), near.above(4));
        guarded.forEach(pos -> PROTECTED_BREAK.add(helper.absolutePos(pos)));
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, far);
            guarded.forEach(pos -> PROTECTED_BREAK.remove(helper.absolutePos(pos)));
            VillageTestSupport.remove(helper, village);
            guarded.forEach(pos -> helper.assertBlockPresent(Blocks.OAK_LOG, pos));
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_tree_replant")
    public static void replantRespectsGriefing(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, REPLANT_BELL, 8, false);
        BlockPos cell = new BlockPos(12, 1, 10);
        helper.setBlock(cell.below(), Blocks.DIRT);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean previous = rule.get();
        rule.set(false, helper.getLevel().getServer());
        ScriptedJob job = new ScriptedJob(new Replant(helper.absolutePos(cell), Items.OAK_SAPLING));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertFalse(job.results.isEmpty(), "task still running");
            rule.set(previous, helper.getLevel().getServer());
            VillageTestSupport.remove(helper, village);
            helper.assertBlockPresent(Blocks.AIR, cell);
            int carried = Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_SAPLING));
            helper.assertTrue(carried == 1, "saplings carried " + carried);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_tree_replant_event")
    public static void replantRespectsPlaceEvent(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.assertTrue(helper.getLevel().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING), "mobGriefing is off; this test needs the default");
        VillageData village = VillageTestSupport.freshVillage(helper, REPLANT_BELL, 8, false);
        BlockPos cell = new BlockPos(12, 1, 10);
        helper.setBlock(cell.below(), Blocks.DIRT);
        BlockPos cellAbs = helper.absolutePos(cell);
        PROTECTED_PLACE.add(cellAbs);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
        ScriptedJob job = new ScriptedJob(new Replant(cellAbs, Items.OAK_SAPLING));
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertFalse(job.results.isEmpty(), "task still running");
            PROTECTED_PLACE.remove(cellAbs);
            VillageTestSupport.remove(helper, village);
            helper.assertBlockPresent(Blocks.AIR, cell);
            int carried = Inventories.count(villager.getInventory(), s -> s.is(Items.OAK_SAPLING));
            helper.assertTrue(carried == 1, "saplings carried " + carried);
        });
    }
}
