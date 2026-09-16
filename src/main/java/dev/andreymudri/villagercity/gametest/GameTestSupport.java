package dev.andreymudri.villagercity.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;

public final class GameTestSupport {
    public static final String TEST_AREA = "test_area";
    public static final int AREA_SIZE = 48;
    public static final int DAY_TIME = 1000;

    private GameTestSupport() {
    }

    /**
     * Grass floor at relative y=0 over the whole area, and a frozen daytime so villagers are never
     * scheduled to sleep mid-test (the GameTest server leaves the daylight cycle on).
     */
    public static void prepareArea(GameTestHelper helper) {
        for (int x = 0; x < AREA_SIZE; x++) {
            for (int z = 0; z < AREA_SIZE; z++) {
                helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
            }
        }
        helper.getLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, helper.getLevel().getServer());
        helper.getLevel().setDayTime(DAY_TIME);
    }

    public static Villager spawnVillager(GameTestHelper helper, int x, int y, int z) {
        Villager villager = helper.spawn(EntityType.VILLAGER, x, y, z);
        villager.setPersistenceRequired();
        return villager;
    }
}
