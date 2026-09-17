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
import net.neoforged.neoforge.event.level.ChunkEvent;
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

    @SuppressWarnings("removal")
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

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_sweep_ticks", timeoutTicks = PlacedLogs.SWEEP_TICKS * 2)
    public static void theSweepRunsOnItsOwn(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        BlockPos burnt = log(helper, 10, 10);
        level.setBlock(burnt, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.succeedWhen(() -> helper.assertTrue(!PlacedLogs.get(level).contains(burnt), "removed log still remembered"));
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

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_piston_rows", timeoutTicks = 100)
    public static void pistonsCarryRowsAndSlimeAttachedLogs(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        PlacedLogs logs = PlacedLogs.get(level);
        helper.setBlock(new BlockPos(10, 2, 30), Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(11, 2, 30), Blocks.SLIME_BLOCK);
        BlockPos side = new BlockPos(11, 2, 31);
        helper.setBlock(side, Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(10, 1, 40), Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        List<BlockPos> row = List.of(new BlockPos(11, 1, 40), new BlockPos(12, 1, 40), new BlockPos(13, 1, 40));
        row.forEach(pos -> helper.setBlock(pos, Blocks.OAK_LOG));
        List<BlockPos> tracked = new ArrayList<>(row);
        tracked.add(side);
        tracked.forEach(pos -> logs.add(helper.absolutePos(pos)));
        helper.startSequence()
                .thenExecute(() -> {
                    helper.setBlock(new BlockPos(9, 2, 30), Blocks.REDSTONE_BLOCK);
                    helper.setBlock(new BlockPos(9, 1, 40), Blocks.REDSTONE_BLOCK);
                })
                .thenExecuteAfter(1, () -> {
                    // Still moving: neither the sweep nor a break check may drop them now.
                    logs.sweep(level);
                })
                .thenExecuteAfter(6, () -> {
                    List<String> wrong = entries(helper, logs, tracked, tracked.stream().map(BlockPos::east).toList());
                    helper.setBlock(new BlockPos(9, 2, 30), Blocks.AIR);
                    helper.setBlock(new BlockPos(9, 1, 40), Blocks.AIR);
                    helper.assertTrue(wrong.isEmpty(), "after the push: " + wrong);
                })
                .thenExecuteAfter(6, () -> {
                    // The sticky piston pulls back only the log it touches.
                    List<String> wrong = entries(helper, logs, tracked, List.of(side, row.get(0), row.get(2), row.get(2).east()));
                    tracked.forEach(pos -> {
                        logs.remove(helper.absolutePos(pos));
                        logs.remove(helper.absolutePos(pos.east()));
                    });
                    helper.assertTrue(wrong.isEmpty(), "after the pull: " + wrong);
                })
                .thenSucceed();
    }

    /** Problems when the logs that started at {@code starts} are not remembered exactly at {@code expected}. */
    private static List<String> entries(GameTestHelper helper, PlacedLogs logs, List<BlockPos> starts, List<BlockPos> expected) {
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : expected) {
            if (!helper.getBlockState(pos).is(Blocks.OAK_LOG) || !logs.contains(helper.absolutePos(pos))) {
                wrong.add("missing " + pos.toShortString() + " block=" + helper.getBlockState(pos).getBlock() + " entry=" + logs.contains(helper.absolutePos(pos)));
            }
        }
        for (BlockPos start : starts) {
            for (BlockPos pos : List.of(start, start.east())) {
                if (!expected.contains(pos) && logs.contains(helper.absolutePos(pos))) {
                    wrong.add("stale " + pos.toShortString());
                }
            }
        }
        return wrong;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_unloaded")
    public static void sweepNeitherLoadsNorForgetsUnloadedLogs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos far = helper.absolutePos(new BlockPos(24, 1, 24)).offset(160_000, 0, 160_000);
        PlacedLogs logs = PlacedLogs.get(level);
        logs.add(far);
        logs.sweep(level);
        boolean kept = logs.contains(far);
        boolean loaded = level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) != null;
        logs.remove(far);
        helper.assertTrue(!loaded, "sweep loaded the chunk of an unloaded log");
        helper.assertTrue(kept, "sweep forgot a log in an unloaded chunk");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_dirty")
    public static void changesAreSaved(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        PlacedLogs logs = new PlacedLogs();
        BlockPos pos = helper.absolutePos(new BlockPos(10, 1, 10));
        logs.add(pos);
        boolean dirtyAfterAdd = logs.isDirty();
        logs.setDirty(false);
        logs.remove(pos);
        boolean dirtyAfterRemove = logs.isDirty();
        helper.assertTrue(dirtyAfterAdd && dirtyAfterRemove, "not marked for saving: add " + dirtyAfterAdd + ", remove " + dirtyAfterRemove);

        // A chunk holding a remembered log unloads: its entry is written now, not only with the next autosave.
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.OAK_LOG);
        PlacedLogs shared = PlacedLogs.get(level);
        shared.add(pos);
        PlacedLogs.onChunkUnload(new ChunkEvent.Unload(level.getChunk(pos)));
        boolean savedOnUnload = !shared.isDirty();
        shared.remove(pos);
        helper.assertTrue(savedOnUnload, "placed logs not saved when their chunk unloaded");
        helper.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_placed_fungus", timeoutTicks = 200)
    public static void fungusGrownWithBoneMealIsNotPlaced(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos fungus = helper.absolutePos(new BlockPos(24, 1, 24));
        level.setBlock(fungus.below(), Blocks.CRIMSON_NYLIUM.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(fungus, Blocks.CRIMSON_FUNGUS.defaultBlockState(), Block.UPDATE_ALL);
        for (int attempt = 0; attempt < 200 && !level.getBlockState(fungus).is(BlockTags.LOGS); attempt++) {
            ItemStack boneMeal = new ItemStack(Items.BONE_MEAL, 64);
            player.setItemInHand(InteractionHand.MAIN_HAND, boneMeal);
            player.gameMode.useItemOn(player, level, boneMeal, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(fungus), Direction.UP, fungus, false));
        }
        level.getServer().getPlayerList().remove(player);
        boolean grew = level.getBlockState(fungus).is(BlockTags.LOGS);
        List<BlockPos> remembered = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(fungus.offset(-4, 0, -4), fungus.offset(4, 30, 4))) {
            if (PlacedLogs.get(level).contains(pos)) {
                remembered.add(pos.immutable());
            }
            if (pos.getY() >= fungus.getY()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        remembered.forEach(PlacedLogs.get(level)::remove);
        helper.assertTrue(grew, "fungus never grew");
        helper.assertTrue(remembered.isEmpty(), "bone-meal fungus remembered as placed at " + remembered);
        helper.succeed();
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
