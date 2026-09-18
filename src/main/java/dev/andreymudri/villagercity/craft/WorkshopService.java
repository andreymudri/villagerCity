package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.citizen.WorldPermissions;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
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
     * Gives up on the furnace the village has and builds another one somewhere else, leaving the old one and
     * everything in it exactly where they are. Returns whether the village ended up somewhere new.
     * <p>
     * This is what makes abandoning a batch affordable. The village never takes back what it left in a furnace it
     * walked away from, and it refuses to start a batch in a furnace that holds anything at all, so without a way
     * out a single interruption — a chunk unloading while a player walks off — would stop the village smelting for
     * good, and the only cure would be a player coming to empty a furnace by hand. The old furnace keeps its
     * contents for whoever wants them.
     */
    public static boolean moveFurnace(ServerLevel level, VillageData village) {
        BlockPos abandoned = village.furnacePos();
        if (abandoned == null || !level.isLoaded(abandoned) || !WorldPermissions.mayGrief(level, null)) {
            return false;
        }
        BlockPos storehouse = village.storehousePos();
        if (storehouse == null || !level.isLoaded(storehouse)) {
            return false;
        }
        // The old furnace block stays standing, so its cell is no longer free and the search cannot hand it back.
        village.setFurnacePos(null);
        ensure(level, village, storehouse, Blocks.FURNACE, null, village::setFurnacePos);
        if (village.furnacePos() == null) {
            // Nowhere else to put one. Keeping the blocked furnace is better than having none at all: a player may
            // yet empty it, and the artisan goes on reporting it by name until somebody does.
            village.setFurnacePos(abandoned);
            return false;
        }
        return true;
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

    /**
     * An air or replaceable cell with a sturdy floor and a free cell above, on no laid path, in no footprint, and with
     * nothing alive standing in it. The last of those is why a player leaning on the storehouse is never shoved out of
     * the way by a crafting table: a workshop block fills the cell exactly as {@code PlaceBlock} does, so it refuses an
     * occupied one on the same terms and walks idle mobs aside for the next village tick.
     */
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
        if (!above.isAir() && !above.canBeReplaced()) {
            return false;
        }
        List<LivingEntity> occupants = level.getEntitiesOfClass(LivingEntity.class, new AABB(spot));
        if (occupants.isEmpty()) {
            return true;
        }
        standAside(level, spot, occupants);
        return false;
    }

    /** Paths idle mobs out of the cell, the way {@code PlaceBlock} does, so a later village tick finds it free. */
    private static void standAside(ServerLevel level, BlockPos spot, List<LivingEntity> occupants) {
        for (LivingEntity occupant : occupants) {
            if (occupant instanceof Mob mob && mob.getNavigation().isDone()) {
                double side = mob.getX() < spot.getX() + 0.5 ? -2.0 : 2.0;
                Path path = mob.getNavigation().createPath(
                        BlockPos.containing(spot.getX() + 0.5 + side, spot.getY(), spot.getZ() + 0.5), 0);
                if (path != null) {
                    mob.getNavigation().moveTo(path, MoveTo.SPEED);
                }
            }
        }
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
