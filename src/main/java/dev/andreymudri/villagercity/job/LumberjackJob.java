package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.Deposit;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Fells the nearest natural tree, collects the drops, replants, and hauls logs to the storehouse. With no tree in
 * reach the lumberjack walks to another sector of the village and searches again, so it works the whole village and
 * not only the square it happens to stand in. A tree whose felling failed or left its trunk standing is skipped for
 * {@link #AVOID_TICKS} and a sector searched in vain for {@link #SECTOR_TICKS}; both lists are in-memory only, as
 * {@link Job} keeps no state that must survive a save.
 */
public final class LumberjackJob implements Job {
    public static final int DEPOSIT_THRESHOLD = 16;
    public static final double DROP_RADIUS = 6.0;
    public static final int AVOID_TICKS = 2400;
    /** How long a sector searched and found empty is skipped, so the village is swept again as trees regrow. */
    public static final int SECTOR_TICKS = 2400;
    /** How close to a sector's centre the lumberjack walks before searching from there. */
    public static final double SECTOR_REACH = 2.5;

    private final Map<BlockPos, Long> avoidUntil = new HashMap<>();
    /** Sector centres (y = 0) searched and found empty, by the game time the memory expires. */
    private final Map<BlockPos, Long> sweptUntil = new HashMap<>();
    private @Nullable BlockPos target;
    private @Nullable BlockPos sector;
    private @Nullable String waitingFor;

    static final Map<Block, Item> SAPLINGS = Map.of(
            Blocks.OAK_LOG, Items.OAK_SAPLING,
            Blocks.SPRUCE_LOG, Items.SPRUCE_SAPLING,
            Blocks.BIRCH_LOG, Items.BIRCH_SAPLING,
            Blocks.JUNGLE_LOG, Items.JUNGLE_SAPLING,
            Blocks.ACACIA_LOG, Items.ACACIA_SAPLING,
            Blocks.DARK_OAK_LOG, Items.DARK_OAK_SAPLING,
            Blocks.CHERRY_LOG, Items.CHERRY_SAPLING,
            Blocks.MANGROVE_LOG, Items.MANGROVE_PROPAGULE);

    static boolean isLog(ItemStack stack) {
        return stack.is(ItemTags.LOGS);
    }

    static boolean isHaul(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    static boolean isDeposit(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        SimpleContainer inventory = ctx.villager().getInventory();
        BlockPos storehouse = ctx.village().storehousePos();
        int logs = Inventories.count(inventory, LumberjackJob::isLog);
        long now = ctx.gameTime();
        avoidUntil.values().removeIf(until -> until <= now);
        sweptUntil.values().removeIf(until -> until <= now);
        waitingFor = null;
        target = null;
        sector = null;
        for (BlockPos base : ctx.village().felling()) {
            if (ctx.level().isLoaded(base) && !ctx.level().getBlockState(base).is(BlockTags.LOGS)) {
                forgetFelling(ctx, base);
            }
        }
        Optional<TreeFinder.Tree> tree = logs >= DEPOSIT_THRESHOLD
                ? Optional.empty()
                : TreeFinder.findNearest(ctx.level(), ctx.villager().blockPosition(), ctx.village(), base -> avoidUntil.containsKey(base));
        if (logs >= DEPOSIT_THRESHOLD || (tree.isEmpty() && Inventories.count(inventory, LumberjackJob::isDeposit) > 0)) {
            if (storehouse == null) {
                waitingFor = "a storehouse";
                return null;
            }
            return TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Deposit(storehouse, LumberjackJob::isDeposit));
        }
        if (tree.isEmpty()) {
            return relocate(ctx);
        }
        TreeFinder.Tree found = tree.get();
        target = found.base();
        return TaskSequence.of(
                new MoveTo(found.base(), 2.5),
                new ChopTree(found.base(), found.logs(), found.logBlock()),
                new PickUpItems(found.base(), DROP_RADIUS, LumberjackJob::isHaul),
                new MoveTo(found.base(), 2.5),
                new Replant(found.base(), SAPLINGS.getOrDefault(found.logBlock(), Items.AIR)));
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        if (target != null && (status == Task.Status.FAILED || TreeFinder.shape(ctx.level(), target).isPresent())) {
            avoidUntil.put(target, ctx.gameTime() + AVOID_TICKS);
        }
        // A sector the lumberjack cannot walk to counts as searched, or it would plan the same doomed walk forever.
        if (sector != null && status == Task.Status.FAILED) {
            sweptUntil.put(sector, ctx.gameTime() + SECTOR_TICKS);
        }
        target = null;
        sector = null;
    }

    /**
     * Walks to the centre of the nearest sector of the village not searched recently, so the next plan searches from
     * there. A search only reaches {@link TreeFinder#SEARCH_RADIUS} around the citizen while the village allows
     * felling {@link TreeFinder#VILLAGE_MARGIN} beyond its radius, so a lumberjack parked at the storehouse would
     * otherwise never see a tree outside that one square. Null once every sector has been searched.
     */
    private @Nullable Task relocate(TaskContext ctx) {
        VillageData village = ctx.village();
        BlockPos from = ctx.villager().blockPosition();
        long expiry = ctx.gameTime() + SECTOR_TICKS;
        sweptUntil.put(sectorOf(village, from), expiry);
        for (BlockPos centre = nearestSector(village, from); centre != null; centre = nearestSector(village, from)) {
            BlockPos goal = centre.atY(from.getY());
            if (ctx.level().isLoaded(goal)) {
                sector = centre;
                return new MoveTo(goal, SECTOR_REACH);
            }
            sweptUntil.put(centre, expiry);
        }
        waitingFor = "a tree within the village";
        return null;
    }

    /** The centre of the sector nearest the position that was not searched recently, or null when none is left. */
    private @Nullable BlockPos nearestSector(VillageData village, BlockPos from) {
        BlockPos nearest = null;
        long best = Long.MAX_VALUE;
        for (BlockPos centre : sectors(village)) {
            long dx = centre.getX() - from.getX();
            long dz = centre.getZ() - from.getZ();
            long distance = dx * dx + dz * dz;
            if (!sweptUntil.containsKey(centre) && distance < best) {
                nearest = centre;
                best = distance;
            }
        }
        return nearest;
    }

    /** The centres (y = 0) of the sectors the village is swept in, each about one search across. */
    private static List<BlockPos> sectors(VillageData village) {
        int reach = village.radius() + TreeFinder.VILLAGE_MARGIN;
        int count = sectorCount(reach);
        List<BlockPos> centres = new ArrayList<>(count * count);
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                centres.add(new BlockPos(axisCentre(village.center().getX(), reach, i), 0, axisCentre(village.center().getZ(), reach, j)));
            }
        }
        return centres;
    }

    /** The centre of the sector the position lies in, which is the sector a search from there covers. */
    private static BlockPos sectorOf(VillageData village, BlockPos pos) {
        int reach = village.radius() + TreeFinder.VILLAGE_MARGIN;
        int count = sectorCount(reach);
        int x = axisCentre(village.center().getX(), reach, axisIndex(village.center().getX(), reach, pos.getX(), count));
        int z = axisCentre(village.center().getZ(), reach, axisIndex(village.center().getZ(), reach, pos.getZ(), count));
        return new BlockPos(x, 0, z);
    }

    private static int sectorCount(int reach) {
        return Math.max(1, Math.ceilDiv(2 * reach + 1, TreeFinder.SEARCH_RADIUS));
    }

    /** Sector centres start half a sector inside the village's reach; the last one is pulled back to its far edge. */
    private static int axisCentre(int villageCenter, int reach, int index) {
        return Math.min(villageCenter - reach + index * TreeFinder.SEARCH_RADIUS + TreeFinder.SEARCH_RADIUS / 2, villageCenter + reach);
    }

    /** Which sector the coordinate lies in; a citizen that wandered outside the reach counts as in the nearest one. */
    private static int axisIndex(int villageCenter, int reach, int coordinate, int count) {
        return Math.clamp(Math.floorDiv(coordinate - (villageCenter - reach), TreeFinder.SEARCH_RADIUS), 0, count - 1);
    }

    /** Drops a remembered felling whose base log is gone; the felling breaks the base last, so the tree is down. */
    private static void forgetFelling(TaskContext ctx, BlockPos base) {
        if (ctx.village().stopFelling(base)) {
            VillageRegistry.get(ctx.level()).setDirty();
        }
    }
}
