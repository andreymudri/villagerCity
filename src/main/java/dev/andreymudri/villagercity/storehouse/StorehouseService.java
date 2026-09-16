package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.citizen.WorldPermissions;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.BlockSnapshot;

public final class StorehouseService {
    private static final Vec3i SIZE = new Vec3i(1, 1, 1);

    private StorehouseService() {
    }

    /**
     * Places a storehouse at the first buildable air spot near the bell when the village has none, or when its
     * block is gone. Does nothing with doMobGriefing off; restores the previous block when EntityPlaceEvent is
     * cancelled (that path is not covered by a test).
     */
    public static void ensureStorehouse(ServerLevel level, VillageData village) {
        BlockPos pos = village.storehousePos();
        if (pos != null) {
            if (!level.isLoaded(pos) || level.getBlockState(pos).is(StorehouseContent.BLOCK.get())) {
                return;
            }
            village.setStorehousePos(null);
        }
        if (!WorldPermissions.mayGrief(level, null)) {
            return;
        }
        PlotPlanner.find(level, village, SIZE, spot -> level.getBlockState(spot).isAir()).ifPresent(spot -> {
            BlockSnapshot snapshot = WorldPermissions.snapshot(level, spot);
            level.setBlock(spot, StorehouseContent.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            if (WorldPermissions.placementCancelled(null, snapshot)) {
                snapshot.restore(Block.UPDATE_ALL);
                return;
            }
            village.setStorehousePos(spot);
        });
    }
}
