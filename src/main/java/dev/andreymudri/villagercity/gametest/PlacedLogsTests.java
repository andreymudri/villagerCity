package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.job.PlacedLogs;
import dev.andreymudri.villagercity.job.TreeFinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** How {@link PlacedLogs} follows a placed log: breaks that did not happen, pistons, multi-block placements, bone meal. */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PlacedLogsTests {
    /** Absolute positions where a protection mod cancels breaks. */
    private static final Set<BlockPos> PROTECTED_BREAK = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (PROTECTED_BREAK.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_break")
    public static void onlyABreakThatRemovedTheLogForgetsIt(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos broken = log(helper, 10, 10);
        BlockPos guarded = log(helper, 12, 10);
        BlockPos queried = log(helper, 14, 10);
        boolean brokenDone = player.gameMode.destroyBlock(broken);
        PROTECTED_BREAK.add(guarded);
        boolean guardedDone = player.gameMode.destroyBlock(guarded);
        PROTECTED_BREAK.remove(guarded);
        // Another mod asking whether the player may break the block, without breaking it.
        NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, queried, level.getBlockState(queried), player));
        level.getServer().getPlayerList().remove(player);
        helper.assertTrue(brokenDone && !guardedDone, "break results " + brokenDone + " " + guardedDone);
        helper.runAfterDelay(2, () -> {
            PlacedLogs logs = PlacedLogs.get(level);
            boolean brokenForgotten = !logs.contains(broken);
            boolean guardedKept = logs.contains(guarded);
            boolean queriedKept = logs.contains(queried);
            List.of(guarded, queried).forEach(logs::remove);
            helper.assertTrue(brokenForgotten, "broken log still remembered");
            helper.assertTrue(guardedKept, "log whose break was cancelled forgotten");
            helper.assertTrue(queriedKept, "log forgotten on a break query that removed nothing");
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_sweep")
    public static void sweepForgetsLogsRemovedWithoutAnEvent(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        BlockPos burnt = log(helper, 10, 10);
        BlockPos standing = log(helper, 12, 10);
        level.setBlock(burnt, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        PlacedLogs logs = PlacedLogs.get(level);
        logs.sweep(level);
        boolean burntForgotten = !logs.contains(burnt);
        boolean standingKept = logs.contains(standing);
        logs.remove(standing);
        helper.assertTrue(burntForgotten, "removed log still remembered after a sweep");
        helper.assertTrue(standingKept, "standing log forgotten by a sweep");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_replace")
    public static void placingANonLogForgetsTheLog(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(10, 1, 10));
        placeAs(level, player, List.of(pos), Blocks.OAK_LOG.defaultBlockState());
        boolean remembered = PlacedLogs.get(level).contains(pos);
        placeAs(level, player, List.of(pos), Blocks.STONE.defaultBlockState());
        boolean forgotten = !PlacedLogs.get(level).contains(pos);
        level.getServer().getPlayerList().remove(player);
        helper.assertTrue(remembered, "placed log not remembered");
        helper.assertTrue(forgotten, "log replaced by a placed stone still remembered");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_multi")
    public static void multiBlockPlacementRemembersEveryLog(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        List<BlockPos> positions = List.of(helper.absolutePos(new BlockPos(10, 1, 10)), helper.absolutePos(new BlockPos(10, 2, 10)), helper.absolutePos(new BlockPos(10, 3, 10)));
        placeAs(level, player, positions, Blocks.OAK_LOG.defaultBlockState());
        List<BlockPos> missing = positions.stream().filter(pos -> !PlacedLogs.get(level).contains(pos)).toList();
        positions.forEach(PlacedLogs.get(level)::remove);
        level.getServer().getPlayerList().remove(player);
        helper.assertTrue(missing.isEmpty(), "multi-placed logs not remembered: " + missing);
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_bonemeal", timeoutTicks = 200)
    public static void treeGrownWithBoneMealIsNotPlaced(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos sapling = helper.absolutePos(new BlockPos(24, 1, 24));
        // A log once placed and burnt here, not swept yet.
        PlacedLogs.get(level).add(sapling);
        level.setBlock(sapling, Blocks.OAK_SAPLING.defaultBlockState().setValue(SaplingBlock.STAGE, 1), Block.UPDATE_ALL);
        for (int attempt = 0; attempt < 200 && !level.getBlockState(sapling).is(BlockTags.LOGS); attempt++) {
            ItemStack boneMeal = new ItemStack(Items.BONE_MEAL, 64);
            player.setItemInHand(InteractionHand.MAIN_HAND, boneMeal);
            player.gameMode.useItemOn(player, level, boneMeal, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(sapling), Direction.UP, sapling, false));
        }
        level.getServer().getPlayerList().remove(player);
        helper.assertTrue(level.getBlockState(sapling).is(BlockTags.LOGS), "sapling never grew");
        List<BlockPos> remembered = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(sapling.offset(-8, 0, -8), sapling.offset(8, 16, 8))) {
            if (PlacedLogs.get(level).contains(pos)) {
                remembered.add(pos.immutable());
            }
        }
        remembered.forEach(PlacedLogs.get(level)::remove);
        helper.assertTrue(remembered.isEmpty(), "bone-meal tree remembered as placed at " + remembered);
        helper.assertTrue(TreeFinder.trunk(level, sapling).isPresent(), "bone-meal tree not accepted");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_piston", timeoutTicks = 100)
    public static void pistonsCarryTheEntryWithTheLog(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        BlockPos piston = new BlockPos(10, 1, 10);
        helper.setBlock(piston, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        BlockPos start = log(helper, 11, 10);
        BlockPos pushed = start.east();
        BlockPos power = new BlockPos(9, 1, 10);
        helper.startSequence()
                .thenExecute(() -> helper.setBlock(power, Blocks.REDSTONE_BLOCK))
                .thenExecuteAfter(6, () -> {
                    helper.assertTrue(level.getBlockState(pushed).is(Blocks.OAK_LOG), "log not pushed");
                    helper.assertTrue(PlacedLogs.get(level).contains(pushed) && !PlacedLogs.get(level).contains(start), "entry did not follow the pushed log");
                    helper.setBlock(power, Blocks.AIR);
                })
                .thenExecuteAfter(6, () -> {
                    helper.assertTrue(level.getBlockState(start).is(Blocks.OAK_LOG), "log not pulled back");
                    helper.assertTrue(PlacedLogs.get(level).contains(start) && !PlacedLogs.get(level).contains(pushed), "entry did not follow the pulled log");
                    PlacedLogs.get(level).remove(start);
                })
                .thenSucceed();
    }

    /** An oak log at (x, 1, z), remembered as placed; returns its absolute position. */
    private static BlockPos log(GameTestHelper helper, int x, int z) {
        BlockPos pos = new BlockPos(x, 1, z);
        helper.setBlock(pos, Blocks.OAK_LOG);
        BlockPos absolute = helper.absolutePos(pos);
        PlacedLogs.get(helper.getLevel()).add(absolute);
        return absolute;
    }

    /** Places the state at every position as one player placement: a single or a multi-block place event. */
    private static void placeAs(ServerLevel level, ServerPlayer player, List<BlockPos> positions, BlockState state) {
        List<BlockSnapshot> snapshots = new ArrayList<>();
        for (BlockPos pos : positions) {
            snapshots.add(BlockSnapshot.create(level.dimension(), level, pos));
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
        if (snapshots.size() == 1) {
            EventHooks.onBlockPlace(player, snapshots.get(0), Direction.UP);
        } else {
            EventHooks.onMultiBlockPlace(player, snapshots, Direction.UP);
        }
    }
}
