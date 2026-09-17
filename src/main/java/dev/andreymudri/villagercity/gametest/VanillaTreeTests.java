package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.LumberjackJob;
import dev.andreymudri.villagercity.job.TreeFinder;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Trees grown by the vanilla tree features, not hand-built ones: every overworld sapling tree must be recognised,
 * and a lumberjack must fell it completely instead of leaving the logs above its reach floating.
 */
@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class VanillaTreeTests {
    private static final BlockPos ORIGIN = new BlockPos(24, 1, 24);
    private static final BlockPos BELL = new BlockPos(44, 1, 44);
    private static final BlockPos STORE = new BlockPos(6, 1, 6);
    private static final int CLEAR_RADIUS = 12;
    private static final int CLEAR_HEIGHT = 40;
    /** Mangroves are left out: their trunk stands on mangrove roots, not dirt, so the finder never scans them. */
    private static final List<ResourceKey<ConfiguredFeature<?, ?>>> SPECIES = List.of(
            TreeFeatures.OAK, TreeFeatures.FANCY_OAK, TreeFeatures.BIRCH, TreeFeatures.SUPER_BIRCH_BEES,
            TreeFeatures.SPRUCE, TreeFeatures.PINE, TreeFeatures.MEGA_SPRUCE, TreeFeatures.MEGA_PINE,
            TreeFeatures.JUNGLE_TREE, TreeFeatures.MEGA_JUNGLE_TREE, TreeFeatures.ACACIA, TreeFeatures.DARK_OAK,
            TreeFeatures.SWAMP_OAK, TreeFeatures.AZALEA_TREE, TreeFeatures.CHERRY);
    private static final int SEEDS = 64;

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_vanilla_trees_found", timeoutTicks = 1200)
    public static void recognisesEveryVanillaTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(ORIGIN);
        StringBuilder rejected = new StringBuilder();
        for (ResourceKey<ConfiguredFeature<?, ?>> species : SPECIES) {
            for (int seed = 0; seed < SEEDS; seed++) {
                clear(level, origin);
                if (grow(level, species, seed, origin) && accepted(level, origin).isEmpty()) {
                    rejected.append(' ').append(species.location().getPath()).append('#').append(seed);
                }
            }
        }
        clear(level, origin);
        helper.assertTrue(rejected.isEmpty(), "vanilla trees rejected:" + rejected);
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_vanilla_fell_jungle", timeoutTicks = 3000)
    public static void fellsJungleTreeCompletely(GameTestHelper helper) {
        fellsCompletely(helper, TreeFeatures.JUNGLE_TREE);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_vanilla_fell_acacia", timeoutTicks = 3000)
    public static void fellsAcaciaCompletely(GameTestHelper helper) {
        fellsCompletely(helper, TreeFeatures.ACACIA);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_vanilla_fell_dark_oak", timeoutTicks = 3000)
    public static void fellsDarkOakCompletely(GameTestHelper helper) {
        fellsCompletely(helper, TreeFeatures.DARK_OAK);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_vanilla_fell_cherry", timeoutTicks = 3000)
    public static void fellsCherryCompletely(GameTestHelper helper) {
        fellsCompletely(helper, TreeFeatures.CHERRY);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_vanilla_fell_super_birch", timeoutTicks = 3000)
    public static void fellsTallBirchCompletely(GameTestHelper helper) {
        fellsCompletely(helper, TreeFeatures.SUPER_BIRCH_BEES);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_vanilla_fell_mega_spruce", timeoutTicks = 6000)
    public static void fellsMegaSpruceCompletely(GameTestHelper helper) {
        fellsCompletely(helper, TreeFeatures.MEGA_SPRUCE);
    }

    /** Grows the first seed of the species that the finder accepts, then waits for a lumberjack to leave no log standing. */
    private static void fellsCompletely(GameTestHelper helper, ResourceKey<ConfiguredFeature<?, ?>> species) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(ORIGIN);
        boolean grown = false;
        for (int seed = 0; seed < SEEDS && !grown; seed++) {
            clear(level, origin);
            grown = grow(level, species, seed, origin) && accepted(level, origin).isPresent();
        }
        helper.assertTrue(grown, "no accepted " + species.location().getPath() + " in " + SEEDS + " seeds");
        int logs = countLogs(level, origin);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 6, false);
        helper.setBlock(STORE, StorehouseContent.BLOCK.get());
        village.setStorehousePos(helper.absolutePos(STORE));
        Villager villager = GameTestSupport.spawnVillager(helper, 16, 1, 16);
        CitizenTestSupport.enroll(villager, village, JobType.LUMBERJACK, new ItemStack(Items.DIAMOND_AXE), new LumberjackJob());
        helper.succeedWhen(() -> {
            int left = countLogs(level, origin);
            helper.assertTrue(left == 0, species.location().getPath() + ": " + left + " of " + logs + " logs still standing");
            clear(level, origin);
            villager.discard();
            VillageTestSupport.remove(helper, village);
        });
    }

    private static boolean grow(ServerLevel level, ResourceKey<ConfiguredFeature<?, ?>> species, int seed, BlockPos origin) {
        ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(species);
        return feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed * 7919L + 13), origin);
    }

    /** A 2x2 trunk may stand on any of its four corners; the finder scans every column, so any accepted corner counts. */
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

    private static int countLogs(ServerLevel level, BlockPos origin) {
        int logs = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-CLEAR_RADIUS, 0, -CLEAR_RADIUS), origin.offset(CLEAR_RADIUS, CLEAR_HEIGHT, CLEAR_RADIUS))) {
            if (level.getBlockState(pos).is(BlockTags.LOGS)) {
                logs++;
            }
        }
        return logs;
    }

    private static void clear(ServerLevel level, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-CLEAR_RADIUS, -1, -CLEAR_RADIUS), origin.offset(CLEAR_RADIUS, CLEAR_HEIGHT, CLEAR_RADIUS))) {
            level.setBlock(pos, pos.getY() < origin.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
