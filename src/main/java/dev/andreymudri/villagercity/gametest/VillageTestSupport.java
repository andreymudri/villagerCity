package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class VillageTestSupport {
    private VillageTestSupport() {
    }

    /** Removes every registered village whose center is within merge distance of the given relative position. */
    public static void removeVillagesNear(GameTestHelper helper, BlockPos relative) {
        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        BlockPos pos = helper.absolutePos(relative);
        List<UUID> stale = registry.all().stream()
                .filter(v -> VillageRegistry.horizontalDistanceSqr(v.center(), pos) <= (long) VillageRegistry.MERGE_DISTANCE * VillageRegistry.MERGE_DISTANCE)
                .map(VillageData::id)
                .toList();
        stale.forEach(registry::remove);
    }

    /** Places a bell and registers a village there with the given radius, clearing any leftover village nearby first. */
    public static VillageData freshVillage(GameTestHelper helper, BlockPos relativeBell, int radius, boolean managed) {
        removeVillagesNear(helper, relativeBell);
        helper.setBlock(relativeBell, Blocks.BELL);
        VillageRegistry registry = VillageRegistry.get(helper.getLevel());
        VillageData village = registry.register(helper.absolutePos(relativeBell));
        village.setRadius(radius);
        village.setManaged(managed);
        registry.setDirty();
        return village;
    }

    public static void remove(GameTestHelper helper, VillageData village) {
        VillageRegistry.get(helper.getLevel()).remove(village.id());
    }
}
