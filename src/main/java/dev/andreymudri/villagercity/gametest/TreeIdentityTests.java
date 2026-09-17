package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.job.LumberjackJob;
import dev.andreymudri.villagercity.job.PlacedLogs;
import dev.andreymudri.villagercity.job.TreeFinder;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Which logs count as a tree: placed logs and structures never do, neighbouring trees are separate, felling resumes. */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class TreeIdentityTests {
    private static final BlockPos BELL = new BlockPos(44, 1, 44);
    private static final BlockPos STORE = new BlockPos(6, 1, 40);
    /** Absolute positions where a protection mod cancels placements. */
    private static final Set<BlockPos> PROTECTED_PLACE = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (PROTECTED_PLACE.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    @SuppressWarnings("removal")
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_player")
    public static void playerPlacedLogIsRememberedUntilBroken(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(new BlockPos(10, 1, 10));
        boolean cancelled = placeAs(level, player, pos, Blocks.OAK_LOG.defaultBlockState());
        boolean remembered = PlacedLogs.get(level).contains(pos);
        boolean broken = player.gameMode.destroyBlock(pos);
        boolean forgotten = !PlacedLogs.get(level).contains(pos);

        BlockPos guarded = helper.absolutePos(new BlockPos(12, 1, 10));
        PROTECTED_PLACE.add(guarded);
        boolean guardedCancelled = placeAs(level, player, guarded, Blocks.OAK_LOG.defaultBlockState());
        PROTECTED_PLACE.remove(guarded);
        boolean guardedRemembered = PlacedLogs.get(level).contains(guarded);
        PlacedLogs.get(level).remove(guarded);
        level.getServer().getPlayerList().remove(player);

        helper.assertTrue(!cancelled && remembered, "player-placed log not remembered");
        helper.assertTrue(broken && forgotten, "broken log still remembered");
        helper.assertTrue(guardedCancelled && !guardedRemembered, "cancelled placement remembered");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_citizen")
    public static void citizenPlacedLogIsRemembered(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager villager = GameTestSupport.spawnVillager(helper, 10, 1, 10);
        villager.getInventory().addItem(new ItemStack(Items.OAK_LOG, 1));
        BlockPos target = helper.absolutePos(new BlockPos(12, 1, 10));
        ScriptedJob job = new ScriptedJob(new PlaceBlock(target, Blocks.OAK_LOG.defaultBlockState(), Items.OAK_LOG));
        CitizenTestSupport.enroll(villager, village, JobType.BUILDER, ItemStack.EMPTY, job);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.results.equals(List.of(Task.Status.SUCCESS)), "place results " + job.results);
            helper.assertTrue(PlacedLogs.get(helper.getLevel()).contains(target), "citizen-placed log not remembered");
            PlacedLogs.get(helper.getLevel()).remove(target);
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void placedLogsSurviveSave(GameTestHelper helper) {
        PlacedLogs logs = new PlacedLogs();
        BlockPos pos = new BlockPos(123, -45, 6789);
        logs.add(pos);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        PlacedLogs loaded = PlacedLogs.load(logs.save(new CompoundTag(), registries), registries);
        helper.assertTrue(loaded.contains(pos) && !loaded.contains(pos.above()), "placed logs lost in save");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_frames")
    public static void treesTouchingPlacedLogsAreNotTrees(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        LumberjackTests.plantTree(helper, new BlockPos(4, 1, 6));
        frame(helper, 6, 3, Blocks.OAK_LOG, placed);
        LumberjackTests.plantTree(helper, new BlockPos(4, 1, 20));
        frame(helper, 6, 17, Blocks.COBBLESTONE, placed);
        LumberjackTests.plantTree(helper, new BlockPos(4, 1, 34));
        frame(helper, 6, 31, Blocks.STRIPPED_OAK_LOG, placed);
        LumberjackTests.plantTree(helper, new BlockPos(30, 1, 10));
        for (int y = 1; y <= 3; y++) {
            BlockPos post = new BlockPos(32, y, 10);
            helper.setBlock(post, Blocks.OAK_LOG);
            placed.add(helper.absolutePos(post));
        }
        placed.forEach(PlacedLogs.get(level)::add);
        List<String> accepted = new ArrayList<>();
        for (BlockPos base : List.of(new BlockPos(4, 1, 6), new BlockPos(6, 1, 3), new BlockPos(4, 1, 20), new BlockPos(4, 1, 34), new BlockPos(32, 1, 10))) {
            if (TreeFinder.trunk(level, helper.absolutePos(base)).isPresent()) {
                accepted.add(base.toShortString());
            }
        }
        boolean lonelyTreeAccepted = TreeFinder.trunk(level, helper.absolutePos(new BlockPos(30, 1, 10))).isPresent();
        placed.forEach(PlacedLogs.get(level)::remove);
        helper.assertTrue(accepted.isEmpty(), "accepted as trees: " + accepted);
        helper.assertTrue(lonelyTreeAccepted, "a tree next to (not touching) a placed post was rejected");
        helper.succeed();
    }

    /** A 6x6 ring of oak logs at y=4 on four 3-high corner posts of the given block, touching the tree at its west edge. */
    private static void frame(GameTestHelper helper, int minX, int minZ, Block post, List<BlockPos> placed) {
        int maxX = minX + 5;
        int maxZ = minZ + 5;
        for (int x : new int[] {minX, maxX}) {
            for (int z : new int[] {minZ, maxZ}) {
                for (int y = 1; y <= 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    helper.setBlock(pos, post);
                    if (post == Blocks.OAK_LOG || post == Blocks.STRIPPED_OAK_LOG) {
                        placed.add(helper.absolutePos(pos));
                    }
                }
            }
        }
        for (int i = 0; i < 6; i++) {
            for (BlockPos pos : List.of(new BlockPos(minX + i, 4, minZ), new BlockPos(minX + i, 4, maxZ), new BlockPos(minX, 4, minZ + i), new BlockPos(maxX, 4, minZ + i))) {
                helper.setBlock(pos, Blocks.OAK_LOG);
                placed.add(helper.absolutePos(pos));
            }
        }
        helper.setBlock(new BlockPos(minX - 1, 4, minZ + 3), Blocks.OAK_LOG);
        placed.add(helper.absolutePos(new BlockPos(minX - 1, 4, minZ + 3)));
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_structure")
    public static void logsInsideStructuresAreNotTrees(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.SWAMP_HUT);
        BlockPos origin = helper.absolutePos(new BlockPos(24, 1, 24));
        ChunkPos chunkPos = new ChunkPos(origin);
        StructureStart start = structure.generate(level.registryAccess(), level.getChunkSource().getGenerator(),
                level.getChunkSource().getGenerator().getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed(), chunkPos, 0, level, biome -> true);
        helper.assertTrue(start.isValid(), "swamp hut did not generate");
        BoundingBox box = start.getBoundingBox();
        ChunkAccess startChunk = level.getChunk(chunkPos.x, chunkPos.z);
        startChunk.setStartForStructure(structure, start);
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.getChunk(cx, cz).addReferenceForStructure(structure, chunkPos.toLong());
            }
        }
        BlockPos inside = new BlockPos(box.getCenter().getX(), box.minY(), box.getCenter().getZ());
        BlockPos outside = new BlockPos(box.maxX() + 3, box.minY(), box.maxZ() + 3);
        boolean insideFlagged = TreeFinder.insideStructure(level, inside);
        boolean outsideFlagged = TreeFinder.insideStructure(level, outside);
        startChunk.setStartForStructure(structure, StructureStart.INVALID_START);
        helper.assertTrue(insideFlagged, "position inside a swamp hut piece not flagged");
        helper.assertTrue(!outsideFlagged, "position outside the swamp hut flagged");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_pairs", timeoutTicks = 400)
    public static void neighbouringVanillaTreesAreSeparateTrees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(20, 1, 24));
        StringBuilder problems = new StringBuilder();
        for (ResourceKey<ConfiguredFeature<?, ?>> species : List.of(TreeFeatures.DARK_OAK, TreeFeatures.CHERRY, TreeFeatures.FANCY_OAK, TreeFeatures.ACACIA)) {
            for (int distance = 3; distance <= 6; distance++) {
                for (int seed = 0; seed < 8; seed++) {
                    clear(level, a);
                    BlockPos b = a.offset(distance, 0, 0);
                    if (!grow(level, species, seed, a) || !grow(level, species, seed + 100, b)) {
                        continue;
                    }
                    Optional<TreeFinder.Tree> treeA = accepted(level, a);
                    Optional<TreeFinder.Tree> treeB = accepted(level, b);
                    String name = species.location().getPath() + " d=" + distance + " seed=" + seed;
                    if (treeA.isEmpty() || treeB.isEmpty()) {
                        problems.append(' ').append(name).append(treeA.isEmpty() ? " A-rejected" : "").append(treeB.isEmpty() ? " B-rejected" : "");
                    } else if (sharesTrunk(treeA.get(), treeB.get())) {
                        problems.append(' ').append(name).append(" trunk-taken");
                    }
                }
            }
        }
        clear(level, a);
        helper.assertTrue(problems.isEmpty(), "neighbouring trees:" + problems);
        helper.succeed();
    }

    /** True when either tree claims a log in the other's trunk columns (its logs on the base layer, extended upward). */
    private static boolean sharesTrunk(TreeFinder.Tree a, TreeFinder.Tree b) {
        return claimsTrunkOf(a, b) || claimsTrunkOf(b, a);
    }

    private static boolean claimsTrunkOf(TreeFinder.Tree tree, TreeFinder.Tree other) {
        Set<BlockPos> columns = new HashSet<>();
        for (BlockPos pos : other.logs()) {
            if (pos.getY() == other.base().getY()) {
                columns.add(new BlockPos(pos.getX(), 0, pos.getZ()));
            }
        }
        for (BlockPos pos : tree.logs()) {
            if (columns.contains(new BlockPos(pos.getX(), 0, pos.getZ()))) {
                return true;
            }
        }
        return false;
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_resume", timeoutTicks = 4000)
    public static void interruptedFellingIsFinishedLater(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos base = new BlockPos(26, 1, 26);
        tallTree(helper, base);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager first = GameTestSupport.spawnVillager(helper, 26, 1, 20);
        CitizenTestSupport.enroll(first, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        Villager[] second = {null};
        helper.succeedWhen(() -> {
            int logs = logsIn(helper, base);
            if (second[0] == null && logs <= 6) {
                first.discard();
                second[0] = GameTestSupport.spawnVillager(helper, 26, 1, 20);
                CitizenTestSupport.enroll(second[0], village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
            }
            helper.assertTrue(second[0] != null && logs == 0, "logs left " + logs + (second[0] == null ? " (first lumberjack still felling)" : ""));
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_midbreak", timeoutTicks = 3000)
    public static void movedMidBreakLeavesNoFloatingLog(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        BlockPos base = new BlockPos(26, 1, 26);
        tallTree(helper, base);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 26, 1, 20);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        long[] state = {12, -1, 0};
        helper.succeedWhen(() -> {
            long now = helper.getTick();
            int logs = logsIn(helper, base);
            if (state[1] < 0 && logs < 12) {
                state[1] = now;
            }
            if (state[2] == 0 && state[1] >= 0 && now - state[1] >= 6) {
                state[2] = 1;
                BlockPos far = helper.absolutePos(new BlockPos(4, 1, 4));
                villager.teleportTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
            }
            helper.assertTrue(logs == 0, "logs left " + logs);
            VillageTestSupport.remove(helper, village);
        });
    }

    /** A 12-log oak column with a natural canopy at y 9..12 (radius 2). */
    static void tallTree(GameTestHelper helper, BlockPos base) {
        for (int y = 0; y < 12; y++) {
            helper.setBlock(base.above(y), Blocks.OAK_LOG);
        }
        for (int y = 9; y <= 12; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx != 0 || dz != 0 || y == 12) {
                        helper.setBlock(base.offset(dx, y, dz), Blocks.OAK_LEAVES);
                    }
                }
            }
        }
    }

    private static int logsIn(GameTestHelper helper, BlockPos base) {
        int logs = 0;
        for (int y = 0; y < 12; y++) {
            if (helper.getBlockState(base.above(y)).is(Blocks.OAK_LOG)) {
                logs++;
            }
        }
        return logs;
    }

    private static boolean placeAs(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        boolean cancelled = EventHooks.onBlockPlace(player, snapshot, Direction.UP);
        if (cancelled) {
            snapshot.restore(Block.UPDATE_ALL);
        }
        return cancelled;
    }

    private static boolean grow(ServerLevel level, ResourceKey<ConfiguredFeature<?, ?>> species, int seed, BlockPos origin) {
        ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(species);
        return feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed * 7919L + 13), origin);
    }

    private static Optional<TreeFinder.Tree> accepted(ServerLevel level, BlockPos origin) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos base = origin.offset(dx, 0, dz);
                if (level.getBlockState(base).is(BlockTags.LOGS)) {
                    Optional<TreeFinder.Tree> tree = TreeFinder.trunk(level, base);
                    if (tree.isPresent()) {
                        return tree;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static void clear(ServerLevel level, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, -1, -12), origin.offset(18, 40, 12))) {
            level.setBlock(pos, pos.getY() < origin.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
