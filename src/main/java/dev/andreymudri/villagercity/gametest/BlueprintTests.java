package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class BlueprintTests {
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void starterHouseLoads(GameTestHelper helper) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow(() -> new AssertionError("blueprint missing"));
        helper.assertTrue(blueprint.size().equals(new Vec3i(5, 5, 5)), "size " + blueprint.size());
        helper.assertTrue(blueprint.placements().size() == 125, "placements " + blueprint.placements().size());
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void materialsCountEachDoubleBlockOnce(GameTestHelper helper) {
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        Map<net.minecraft.world.item.Item, Integer> expected = Map.of(
                Items.COBBLESTONE, 25, Items.OAK_PLANKS, 57, Items.OAK_LOG, 12,
                Items.GLASS, 2, Items.RED_BED, 1, Items.OAK_DOOR, 1);
        helper.assertTrue(blueprint.requiredMaterials().equals(expected), "materials " + blueprint.requiredMaterials());
        helper.assertTrue(Blueprint.costOf(Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD)) == Items.AIR, "bed head costs");
        helper.assertTrue(Blueprint.costOf(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)) == Items.AIR, "upper door costs");
        helper.assertTrue(Blueprint.costOf(Blocks.AIR.defaultBlockState()) == Items.AIR, "air costs");
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void placementsAreBottomUpSolidsFirst(GameTestHelper helper) {
        List<BlueprintPlacement> placements = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow().placements();
        for (int i = 1; i < placements.size(); i++) {
            BlueprintPlacement previous = placements.get(i - 1);
            BlueprintPlacement current = placements.get(i);
            helper.assertTrue(previous.offset().getY() <= current.offset().getY(), "layer order broken at " + i);
            if (previous.offset().getY() == current.offset().getY()) {
                helper.assertFalse(!previous.state().blocksMotion() && current.state().blocksMotion(), "non-solid before solid at " + i);
            }
        }
        helper.assertTrue(placements.get(0).state().is(Blocks.COBBLESTONE), "first placement " + placements.get(0));
        helper.succeed();
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void missingBlueprintIsEmpty(GameTestHelper helper) {
        helper.assertTrue(Blueprints.load(helper.getLevel(), VillagerCity.id("blueprint/does_not_exist")).isEmpty(), "missing blueprint loaded");
        helper.succeed();
    }
}
