package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.citizen.WorldPermissions;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;

/**
 * Keeps the artisan's workshop, a crafting table and a furnace, in place for the village: both stand within
 * {@link #SEARCH_RADIUS} blocks of the storehouse so one plan can walk store, workshop and store again. A block that
 * goes missing is forgotten and placed again at the first free spot, which may differ from the old one.
 */
public final class WorkshopService {
    /** How far from the storehouse, horizontally, a workshop block may stand. */
    public static final int SEARCH_RADIUS = 3;
    /** How far above or below the storehouse a workshop block may stand. */
    public static final int VERTICAL_REACH = 1;

    private WorkshopService() {
    }

    /**
     * Called on every village tick right after the storehouse check. Does nothing without a loaded storehouse or with
     * doMobGriefing off, and records no position when EntityPlaceEvent is cancelled, as {@code StorehouseService} does.
     */
    public static void ensureWorkshop(ServerLevel level, VillageData village) {
        BlockPos storehouse = village.storehousePos();
        if (storehouse == null || !level.isLoaded(storehouse) || !WorldPermissions.mayGrief(level, null)) {
            return;
        }
        ensure(level, village, storehouse, Blocks.CRAFTING_TABLE, village.craftingTablePos(), village::setCraftingTablePos);
        ensure(level, village, storehouse, Blocks.FURNACE, village.furnacePos(), village::setFurnacePos);
    }

    /**
     * Leaves a recorded block that is still there alone; otherwise clears the record and places a new one at the first
     * spot {@link #spots} offers. Clearing first matters: the old cell is dropped from {@code occupiedFootprints} and
     * can be reused right away.
     */
    private static void ensure(ServerLevel level, VillageData village, BlockPos storehouse, Block block,
                               @Nullable BlockPos current, Consumer<BlockPos> save) {
        if (current != null) {
            if (!level.isLoaded(current) || level.getBlockState(current).is(block)) {
                return;
            }
            save.accept(null);
        }
        List<Footprint> occupied = village.occupiedFootprints();
        for (BlockPos spot : spots(storehouse)) {
            if (!isFree(level, village, occupied, spot)) {
                continue;
            }
            BlockSnapshot snapshot = WorldPermissions.snapshot(level, spot);
            level.setBlock(spot, block.defaultBlockState(), Block.UPDATE_ALL);
            if (WorldPermissions.placementCancelled(null, snapshot)) {
                snapshot.restore(Block.UPDATE_ALL);
                return;
            }
            save.accept(spot);
            return;
        }
    }

    /** An air or replaceable cell with a sturdy floor and a free cell above, on no laid path and in no footprint. */
    private static boolean isFree(ServerLevel level, VillageData village, List<Footprint> occupied, BlockPos spot) {
        if (!level.isLoaded(spot) || village.isPathColumn(spot.getX(), spot.getZ())
                || occupied.stream().anyMatch(footprint -> footprint.contains(spot.getX(), spot.getZ()))) {
            return false;
        }
        BlockState existing = level.getBlockState(spot);
        if ((!existing.isAir() && !existing.canBeReplaced()) || !existing.getFluidState().isEmpty()) {
            return false;
        }
        BlockPos below = spot.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        BlockState above = level.getBlockState(spot.above());
        return above.isAir() || above.canBeReplaced();
    }

    /**
     * Every cell within {@link #SEARCH_RADIUS} horizontal blocks of the storehouse and {@link #VERTICAL_REACH} above or
     * below it, nearest ring first, then the storehouse's own level before the one above and the one below, then by x
     * and z. Fixed and independent of the world, so two villages with the same shape lay their workshop out the same.
     */
    static List<BlockPos> spots(BlockPos storehouse) {
        List<BlockPos> spots = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = -VERTICAL_REACH; dy <= VERTICAL_REACH; dy++) {
                    spots.add(storehouse.offset(dx, dy, dz));
                }
            }
        }
        spots.sort(Comparator
                .comparingInt((BlockPos spot) -> Math.max(Math.abs(spot.getX() - storehouse.getX()), Math.abs(spot.getZ() - storehouse.getZ())))
                .thenComparingInt(spot -> Math.abs(spot.getY() - storehouse.getY()))
                .thenComparingInt(spot -> -(spot.getY() - storehouse.getY()))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return spots;
    }
}
