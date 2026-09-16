package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

public final class StorehouseService {
    private static final Vec3i SIZE = new Vec3i(1, 1, 1);

    private StorehouseService() {
    }

    /** Places a storehouse at the first buildable spot near the bell when the village has none, or when its block is gone. */
    public static void ensureStorehouse(ServerLevel level, VillageData village) {
        BlockPos pos = village.storehousePos();
        if (pos != null) {
            if (!level.isLoaded(pos) || level.getBlockState(pos).is(StorehouseContent.BLOCK.get())) {
                return;
            }
            village.setStorehousePos(null);
        }
        PlotPlanner.find(level, village, SIZE).ifPresent(spot -> {
            level.setBlock(spot, StorehouseContent.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            village.setStorehousePos(spot);
        });
    }
}
