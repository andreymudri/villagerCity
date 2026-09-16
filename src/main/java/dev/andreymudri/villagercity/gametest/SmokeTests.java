package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class SmokeTests {
    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void testAreaLoads(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        helper.assertBlockPresent(Blocks.GRASS_BLOCK, new BlockPos(24, 0, 24));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(24, 1, 24));
        helper.succeed();
    }
}
