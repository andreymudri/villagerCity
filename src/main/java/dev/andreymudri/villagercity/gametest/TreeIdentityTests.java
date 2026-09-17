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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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

        BlockPos guarded = helper.absolutePos(new BlockPos(12, 1, 10));
        PROTECTED_PLACE.add(guarded);
        boolean guardedCancelled = placeAs(level, player, guarded, Blocks.OAK_LOG.defaultBlockState());
        PROTECTED_PLACE.remove(guarded);
        boolean guardedRemembered = PlacedLogs.get(level).contains(guarded);
        PlacedLogs.get(level).remove(guarded);
        level.getServer().getPlayerList().remove(player);

        helper.assertTrue(!cancelled && remembered, "player-placed log not remembered");
        helper.assertTrue(guardedCancelled && !guardedRemembered, "cancelled placement remembered");
        helper.assertTrue(broken, "placed log not broken");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(!PlacedLogs.get(level).contains(pos), "broken log still remembered");
            helper.succeed();
        });
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
        plantAbsolute(level, inside);
        plantAbsolute(level, outside);
        boolean insideTree = TreeFinder.trunk(level, inside).isPresent();
        boolean outsideTree = TreeFinder.trunk(level, outside).isPresent();
        startChunk.setStartForStructure(structure, StructureStart.INVALID_START);
        boolean insideTreeWithoutHut = TreeFinder.trunk(level, inside).isPresent();
        for (BlockPos base : List.of(inside, outside)) {
            for (BlockPos pos : BlockPos.betweenClosed(base.offset(-1, 0, -1), base.offset(1, 5, 1))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        helper.assertTrue(insideFlagged, "position inside a swamp hut piece not flagged");
        helper.assertTrue(!outsideFlagged, "position outside the swamp hut flagged");
        helper.assertTrue(!insideTree && insideTreeWithoutHut, "tree inside the swamp hut accepted (without the hut: " + insideTreeWithoutHut + ")");
        helper.assertTrue(outsideTree, "tree outside the swamp hut rejected");
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

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_log_above")
    public static void aTreeNeedsALogOfItsKindInThe3x3AboveItsBase(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        // Leaning like an acacia: the second log stands diagonally above the base.
        BlockPos leaning = new BlockPos(10, 1, 10);
        helper.setBlock(leaning, Blocks.OAK_LOG);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(leaning.offset(1, y, 1), Blocks.OAK_LOG);
        }
        canopy(helper, leaning.offset(1, 3, 1));
        // A ground log whose only log above stands two blocks east, reached through the log beside it.
        BlockPos beside = new BlockPos(30, 1, 10);
        helper.setBlock(beside, Blocks.OAK_LOG);
        helper.setBlock(beside.east(), Blocks.OAK_LOG);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(beside.offset(2, y, 0), Blocks.OAK_LOG);
        }
        canopy(helper, beside.offset(2, 3, 0));
        // Another kind above does not count.
        BlockPos mixed = new BlockPos(10, 1, 30);
        helper.setBlock(mixed, Blocks.OAK_LOG);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(mixed.above(y), Blocks.BIRCH_LOG);
        }
        canopy(helper, mixed.above(3));
        helper.assertTrue(TreeFinder.trunk(level, helper.absolutePos(leaning)).isPresent(), "leaning tree rejected");
        helper.assertTrue(TreeFinder.trunk(level, helper.absolutePos(beside)).isEmpty(), "log with no log in the 3x3 above accepted");
        helper.assertTrue(TreeFinder.trunk(level, helper.absolutePos(beside.east())).isPresent(), "log with a log diagonally above rejected");
        helper.assertTrue(TreeFinder.trunk(level, helper.absolutePos(mixed)).isEmpty(), "oak log under birch logs accepted");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_max_trunk")
    public static void aTreeHasAtMost256Logs(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        // 2x2 trunks of 64 layers (256 logs) and 65 layers (260 logs).
        BlockPos fits = new BlockPos(10, 1, 10);
        BlockPos tooBig = new BlockPos(30, 1, 10);
        for (BlockPos base : List.of(fits, tooBig)) {
            int layers = base == fits ? 64 : 65;
            for (int y = 0; y < layers; y++) {
                for (BlockPos pos : BlockPos.betweenClosed(base.above(y), base.offset(1, y, 1))) {
                    helper.setBlock(pos, Blocks.OAK_LOG);
                }
            }
            canopy(helper, base.above(layers));
        }
        boolean fitsAccepted = TreeFinder.trunk(level, helper.absolutePos(fits)).isPresent();
        boolean tooBigAccepted = TreeFinder.trunk(level, helper.absolutePos(tooBig)).isPresent();
        for (BlockPos pos : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(8, 1, 8)), helper.absolutePos(new BlockPos(33, 67, 13)))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        helper.assertTrue(fitsAccepted, "256-log tree rejected");
        helper.assertTrue(!tooBigAccepted, "260-log tree accepted");
        helper.succeed();
    }

    /** Natural oak leaves in a 3x3 around and above the top log. */
    private static void canopy(GameTestHelper helper, BlockPos top) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(top.offset(dx, 1, dz), Blocks.OAK_LEAVES);
                if (dx != 0 || dz != 0) {
                    helper.setBlock(top.offset(dx, 0, dz), Blocks.OAK_LEAVES);
                }
            }
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_corners", timeoutTicks = 400)
    public static void everyCornerOfA2x2TrunkNamesTheSameTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(24, 1, 24));
        StringBuilder problems = new StringBuilder();
        for (ResourceKey<ConfiguredFeature<?, ?>> species : List.of(TreeFeatures.DARK_OAK, TreeFeatures.MEGA_SPRUCE, TreeFeatures.MEGA_JUNGLE_TREE)) {
            for (int seed = 0; seed < 4; seed++) {
                clear(level, origin);
                if (!grow(level, species, seed, origin)) {
                    continue;
                }
                Set<BlockPos> bases = new HashSet<>();
                Set<Set<BlockPos>> sizes = new HashSet<>();
                BlockPos expected = null;
                for (BlockPos corner : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(1, 0, 1))) {
                    if (!level.getBlockState(corner).is(BlockTags.LOGS)) {
                        continue;
                    }
                    if (expected == null) {
                        expected = corner.immutable();
                    }
                    Optional<TreeFinder.Tree> tree = TreeFinder.trunk(level, corner);
                    if (tree.isPresent()) {
                        bases.add(tree.get().base());
                        sizes.add(Set.copyOf(tree.get().logs()));
                    }
                }
                String name = species.location().getPath() + "#" + seed;
                if (bases.size() != 1 || !bases.contains(expected) || sizes.size() != 1) {
                    problems.append(' ').append(name).append(" bases=").append(bases).append(" log sets=").append(sizes.size()).append(" expected=").append(expected);
                }
            }
        }
        clear(level, origin);
        helper.assertTrue(problems.isEmpty(), "2x2 trunks:" + problems);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_farm", timeoutTicks = 400)
    public static void everyTreeOfADenseDarkOakFarmIsATree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(new BlockPos(14, 1, 14));
        for (BlockPos pos : BlockPos.betweenClosed(corner.offset(-6, -1, -6), corner.offset(22, 40, 22))) {
            level.setBlock(pos, pos.getY() < corner.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        List<BlockPos> grown = new ArrayList<>();
        int seed = 0;
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                BlockPos base = corner.offset(i * 3, 0, j * 3);
                if (grow(level, TreeFeatures.DARK_OAK, seed++, base)) {
                    grown.add(base);
                }
            }
        }
        List<String> rejected = new ArrayList<>();
        long started = System.nanoTime();
        for (BlockPos base : grown) {
            if (TreeFinder.trunk(level, base).isEmpty()) {
                rejected.add(base.toShortString());
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        for (BlockPos pos : BlockPos.betweenClosed(corner.offset(-6, -1, -6), corner.offset(22, 40, 22))) {
            level.setBlock(pos, pos.getY() < corner.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        helper.assertTrue(grown.size() >= 10, "only " + grown.size() + " dark oaks grew");
        helper.assertTrue(rejected.isEmpty(), rejected.size() + " of " + grown.size() + " farm trees rejected: " + rejected);
        helper.assertTrue(millis < 200, "walking " + grown.size() + " farm trees took " + millis + " ms");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_recheck", timeoutTicks = 3000)
    public static void logsReplacedAfterPlanningAreLeftStanding(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        BlockPos base = new BlockPos(26, 1, 26);
        tallTree(helper, base);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 26, 1, 20);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        BlockPos birch = base.above(3);
        BlockPos placed = base.above(5);
        boolean[] replaced = {false};
        helper.succeedWhen(() -> {
            if (!replaced[0] && logsIn(helper, base) < 12) {
                replaced[0] = true;
                helper.setBlock(birch, Blocks.BIRCH_LOG);
                PlacedLogs.get(level).add(helper.absolutePos(placed));
            }
            int oak = 0;
            for (int y = 0; y < 12; y++) {
                if (y != 5 && helper.getBlockState(base.above(y)).is(Blocks.OAK_LOG)) {
                    oak++;
                }
            }
            helper.assertTrue(replaced[0] && oak == 0, "tree logs left " + oak);
            helper.assertTrue(helper.getBlockState(birch).is(Blocks.BIRCH_LOG), "birch log put in after planning was chopped");
            helper.assertTrue(helper.getBlockState(placed).is(Blocks.OAK_LOG), "placed log put in after planning was chopped");
            PlacedLogs.get(level).remove(helper.absolutePos(placed));
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_stumps", timeoutTicks = 3000)
    public static void aFellingInterruptedAtTheLastLayerIsFinished(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(26, 1, 26));
        Optional<TreeFinder.Tree> grown = Optional.empty();
        for (int seed = 0; seed < 16 && grown.isEmpty(); seed++) {
            clear(level, origin);
            if (grow(level, TreeFeatures.DARK_OAK, seed, origin)) {
                grown = accepted(level, origin);
            }
        }
        helper.assertTrue(grown.isPresent(), "no dark oak grew");
        TreeFinder.Tree tree = grown.get();
        List<BlockPos> stumps = tree.logs().stream().filter(pos -> pos.getY() == tree.base().getY()).toList();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, 1, -12), origin.offset(18, 40, 12))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        boolean foundUnremembered = TreeFinder.findNearest(level, origin, village).isPresent();
        village.startFelling(tree.base());
        boolean foundRemembered = TreeFinder.findNearest(level, origin, village).isPresent();
        helper.assertTrue(stumps.size() == 4, "dark oak ground layer " + stumps);
        helper.assertTrue(!foundUnremembered, "stumps of a tree nobody was felling accepted");
        helper.assertTrue(foundRemembered, "stumps of a remembered felling not found");
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 26, 1, 20);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            List<BlockPos> left = stumps.stream().filter(pos -> level.getBlockState(pos).is(BlockTags.LOGS)).toList();
            helper.assertTrue(left.isEmpty(), "stumps left " + left);
            helper.assertTrue(village.felling().isEmpty(), "felling still remembered " + village.felling());
            VillageTestSupport.remove(helper, village);
        });
    }

    @SuppressWarnings("removal")
    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_touching")
    public static void treesTouchingPlacedLogsOfAnyKindAreNotTrees(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        // A player strips a trunk log with an axe.
        BlockPos strippedBase = new BlockPos(4, 1, 4);
        LumberjackTests.plantTree(helper, strippedBase);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos stripped = helper.absolutePos(strippedBase.above(2));
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        player.setItemInHand(InteractionHand.MAIN_HAND, axe);
        player.gameMode.useItemOn(player, level, axe, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(stripped), Direction.NORTH, stripped, false));
        level.getServer().getPlayerList().remove(player);
        helper.assertTrue(level.getBlockState(stripped).is(Blocks.STRIPPED_OAK_LOG), "trunk log not stripped");
        placed.add(stripped);
        if (TreeFinder.trunk(level, helper.absolutePos(strippedBase)).isPresent()) {
            accepted.add("stripped trunk");
        }
        // Placed beams of other log blocks resting against a trunk.
        int z = 14;
        for (Block kind : List.of(Blocks.SPRUCE_LOG, Blocks.STRIPPED_OAK_LOG, Blocks.OAK_WOOD)) {
            BlockPos base = new BlockPos(4, 1, z);
            LumberjackTests.plantTree(helper, base);
            for (int x = 5; x <= 9; x++) {
                helper.setBlock(new BlockPos(x, 3, z), kind);
                placed.add(helper.absolutePos(new BlockPos(x, 3, z)));
                PlacedLogs.get(level).add(helper.absolutePos(new BlockPos(x, 3, z)));
            }
            if (TreeFinder.trunk(level, helper.absolutePos(base)).isPresent()) {
                accepted.add(kind.getDescriptionId() + " beam");
            }
            z += 10;
        }
        // A placed post whose top touches a branch tip, standing on its own placed logs.
        BlockPos branched = new BlockPos(30, 1, 20);
        LumberjackTests.plantTree(helper, branched);
        helper.setBlock(branched.offset(1, 4, 0), Blocks.OAK_LOG);
        helper.setBlock(branched.offset(2, 4, 0), Blocks.OAK_LOG);
        for (int y = 0; y <= 4; y++) {
            helper.setBlock(branched.offset(3, y, 0), Blocks.OAK_LOG);
            placed.add(helper.absolutePos(branched.offset(3, y, 0)));
            PlacedLogs.get(level).add(helper.absolutePos(branched.offset(3, y, 0)));
        }
        if (TreeFinder.trunk(level, helper.absolutePos(branched)).isPresent()) {
            accepted.add("branch touching a post");
        }
        placed.forEach(PlacedLogs.get(level)::remove);
        helper.assertTrue(accepted.isEmpty(), "accepted as trees: " + accepted);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_foundation")
    public static void untrackedLogWallsOnAFoundationAreNotTrees(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        List<String> accepted = new ArrayList<>();
        int z = 6;
        for (Block foundation : List.of(Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.STONE_BRICKS)) {
            BlockPos base = new BlockPos(4, 1, z);
            for (int y = 0; y < 7; y++) {
                helper.setBlock(base.above(y), Blocks.OAK_LOG);
            }
            canopy(helper, base.above(6));
            for (int x = 5; x <= 9; x++) {
                helper.setBlock(new BlockPos(x, 1, z), foundation);
                for (int y = 2; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
                }
            }
            if (TreeFinder.trunk(level, helper.absolutePos(base)).isPresent()) {
                accepted.add(foundation.getDescriptionId());
            }
            z += 12;
        }
        // The same tree alone is a tree.
        BlockPos lonely = new BlockPos(30, 1, 6);
        for (int y = 0; y < 7; y++) {
            helper.setBlock(lonely.above(y), Blocks.OAK_LOG);
        }
        canopy(helper, lonely.above(6));
        helper.assertTrue(accepted.isEmpty(), "trees absorbing a log wall on a foundation accepted: " + accepted);
        helper.assertTrue(TreeFinder.trunk(level, helper.absolutePos(lonely)).isPresent(), "lonely tree rejected");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_leaves")
    public static void aTreeNeedsFourNaturalLeaves(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        List<String> wrong = new ArrayList<>();
        int x = 6;
        for (int leaves = 3; leaves <= 4; leaves++) {
            for (boolean persistent : new boolean[] {false, true}) {
                BlockPos base = new BlockPos(x, 1, 10);
                for (int y = 0; y < 3; y++) {
                    helper.setBlock(base.above(y), Blocks.OAK_LOG);
                }
                List<BlockPos> spots = List.of(base.above(3), base.offset(1, 2, 0), base.offset(-1, 2, 0), base.offset(0, 2, 1));
                for (int i = 0; i < leaves; i++) {
                    helper.setBlock(spots.get(i), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, persistent));
                }
                boolean accepted = TreeFinder.trunk(level, helper.absolutePos(base)).isPresent();
                if (accepted != (leaves == 4 && !persistent)) {
                    wrong.add(leaves + (persistent ? " persistent" : " natural") + " leaves accepted=" + accepted);
                }
                x += 6;
            }
        }
        helper.assertTrue(wrong.isEmpty(), "leaves rule: " + wrong);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_unknown_start")
    public static void aStructureWhoseStartIsNotLoadedStillCovers(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.SWAMP_HUT);
        BlockPos pos = helper.absolutePos(new BlockPos(24, 1, 24));
        ChunkAccess chunk = level.getChunk(pos);
        Map<Structure, LongSet> before = new HashMap<>();
        chunk.getAllReferences().forEach((key, value) -> before.put(key, new LongOpenHashSet(value)));
        boolean flaggedBefore = TreeFinder.insideStructure(level, pos);
        ChunkPos farStart = new ChunkPos((pos.getX() >> 4) + 10_000, (pos.getZ() >> 4) + 10_000);
        chunk.addReferenceForStructure(structure, farStart.toLong());
        boolean flagged = TreeFinder.insideStructure(level, pos);
        boolean loaded = level.getChunkSource().getChunkNow(farStart.x, farStart.z) != null;
        chunk.setAllReferences(before);
        helper.assertTrue(!flaggedBefore, "plain ground flagged as a structure");
        helper.assertTrue(flagged, "position referencing a structure whose start is not loaded not flagged");
        helper.assertTrue(!loaded, "checking the structure loaded its start chunk");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_avoided", timeoutTicks = 400)
    public static void avoidedTreesAreNotWalkedAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(new BlockPos(4, 1, 4));
        for (BlockPos pos : BlockPos.betweenClosed(corner.offset(-4, -1, -4), corner.offset(44, 40, 44))) {
            level.setBlock(pos, pos.getY() < corner.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Set<BlockPos> bases = new HashSet<>();
        int seed = 0;
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) {
                BlockPos origin = corner.offset(i * 5, 0, j * 5);
                if (grow(level, TreeFeatures.DARK_OAK, seed++, origin)) {
                    accepted(level, origin).ifPresent(tree -> bases.add(tree.base()));
                }
            }
        }
        VillageData village = VillageTestSupport.freshVillage(helper, new BlockPos(24, 1, 24), 6, false);
        BlockPos from = helper.absolutePos(new BlockPos(24, 1, 24));
        boolean foundWithout = TreeFinder.findNearest(level, from, village).isPresent();
        long best = Long.MAX_VALUE;
        boolean foundAvoided = false;
        for (int run = 0; run < 5; run++) {
            long started = System.nanoTime();
            foundAvoided |= TreeFinder.findNearest(level, from, village, bases::contains).isPresent();
            best = Math.min(best, System.nanoTime() - started);
        }
        for (BlockPos pos : BlockPos.betweenClosed(corner.offset(-4, -1, -4), corner.offset(44, 40, 44))) {
            level.setBlock(pos, pos.getY() < corner.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        VillageTestSupport.remove(helper, village);
        helper.assertTrue(bases.size() >= 60, "only " + bases.size() + " dark oaks grew");
        helper.assertTrue(foundWithout && !foundAvoided, "found without avoiding " + foundWithout + ", with every tree avoided " + foundAvoided);
        helper.assertTrue(best < 6_000_000L, "searching " + bases.size() + " avoided trees took " + best / 1000 + " us");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_identity_unreachable", timeoutTicks = 1200)
    public static void anUnreachableTreeIsNotRememberedAsFelling(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        BlockPos base = new BlockPos(26, 8, 26);
        for (int y = 1; y < 8; y++) {
            helper.setBlock(new BlockPos(26, y, 26), Blocks.DIRT);
        }
        LumberjackTests.plantTree(helper, base);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 20, 1, 20);
        helper.assertTrue(TreeFinder.findNearest(level, villager.blockPosition(), village).isPresent(), "tree on the pillar not found");
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.STONE_AXE), new LumberjackJob());
        helper.runAfterDelay(1000, () -> {
            boolean standing = helper.getBlockState(base).is(Blocks.OAK_LOG) && helper.getBlockState(base.above(4)).is(Blocks.OAK_LOG);
            List<BlockPos> felling = village.felling();
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(standing, "tree on the pillar was felled; the test needs an unreachable tree");
            helper.assertTrue(felling.isEmpty(), "unreachable tree remembered as felling " + felling);
            helper.succeed();
        });
    }

    /** A 4-log oak with natural leaves around its top, at an absolute position. */
    private static void plantAbsolute(ServerLevel level, BlockPos base) {
        for (int y = 0; y < 4; y++) {
            level.setBlock(base.above(y), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(base.offset(dx, 4, dz), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_CLIENTS);
                if (dx != 0 || dz != 0) {
                    level.setBlock(base.offset(dx, 3, dz), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
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
