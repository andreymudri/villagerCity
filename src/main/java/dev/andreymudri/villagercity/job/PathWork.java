package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Inventories;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskSequence;
import dev.andreymudri.villagercity.citizen.task.BreakBlock;
import dev.andreymudri.villagercity.citizen.task.DigStep;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.citizen.task.PickUpItems;
import dev.andreymudri.villagercity.citizen.task.PlaceBlock;
import dev.andreymudri.villagercity.citizen.task.Withdraw;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How the paver builds one path cell ({@link #planCell}): clearing headroom, placing dirt or oak plank support where
 * the ground is lower or wet, and surfacing dirt-like support with {@link MakePath}. Only natural ground the paver can
 * dig, or vegetation a placement can replace, is ever broken, and only ground still dirt-like when the paver gets
 * there is ever paved; a block that fails either check (a player's chest, or a block dropped on the support cell)
 * reports the cell obstructed instead, so its caller plans around it rather than through it. {@link StreetWork} lays
 * every street cell through here.
 *
 * <p>As a job of its own, all that is left of the old path from each house to the bell is retiring it: {@link #plan}
 * drains the village's path queue and closes any door an older save recorded as opened, and lays nothing.
 */
public final class PathWork implements Job {
    /** Consecutive failed build tasks on the same cell before its caller gives up on it. */
    public static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** Most units of one support item withdrawn from the storehouse at a time. */
    public static final int WITHDRAW_BATCH = 32;
    /** How far down a cut looks for the block it must break through to reach solid ground. */
    public static final int MAX_CUT_DEPTH = 6;
    /** How far (horizontally) a villager steps aside when it is standing where a support block must go. */
    public static final int STAND_ASIDE_DISTANCE = 2;
    public static final double STAND_ASIDE_REACH = 0.9;

    /**
     * What building one cell needs next: a task, a reason to wait, or nothing when the cell is already built. {@code
     * building} is true for a task that works on the cell itself, false for one that only fetches materials for it.
     */
    public record CellStep(@Nullable Task task, boolean building, @Nullable String waitingFor) {
        static final CellStep BUILT = new CellStep(null, false, null);

        static CellStep build(Task task) {
            return new CellStep(task, true, null);
        }

        static CellStep waitFor(String reason) {
            return new CellStep(null, false, reason);
        }

        /** True when the cell is finished: nothing to do and nothing to wait for. */
        public boolean built() {
            return task == null && waitingFor == null;
        }
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        retireHouseToBellPaths(ctx.level(), ctx.village());
        return null;
    }

    /**
     * Drains the path queue houses built before streets left behind, and closes every door recorded on the village as
     * opened by the paver, then forgets it. A door is only closed when it is still a wooden door and still open, and
     * one in an unloaded chunk stays recorded until its chunk is loaded.
     */
    public static void retireHouseToBellPaths(ServerLevel level, VillageData village) {
        boolean changed = false;
        for (BlockPos origin : village.pathQueue()) {
            changed |= village.removeQueuedPath(origin);
        }
        for (BlockPos door : village.openedDoors()) {
            if (!level.isLoaded(door)) {
                continue;
            }
            BlockState state = level.getBlockState(door);
            if (state.getBlock() instanceof DoorBlock doorBlock && state.is(BlockTags.WOODEN_DOORS) && doorBlock.isOpen(state)) {
                doorBlock.setOpen(null, level, state, door, false);
            }
            changed |= village.clearOpenedDoor(door);
        }
        if (changed) {
            VillageRegistry.get(level).setDirty();
        }
    }

    /**
     * The next step to finish {@code cell}. {@code onObstructed} is told the position of a block that neither the
     * paver may clear nor may be paved over, either now (and the step then carries a {@code waitingFor}) or when a
     * task returned here re-checks it right before breaking or paving it (and the task then fails).
     */
    public static CellStep planCell(TaskContext ctx, VillageData village, PathRoute.Cell cell, Consumer<BlockPos> onObstructed) {
        ServerLevel level = ctx.level();
        BlockPos surface = cell.surface();
        BlockPos support = surface.below();

        BlockPos headroom = firstOccupied(level, surface);
        if (headroom != null) {
            if (!clearable(level, headroom)) {
                // A block that is neither natural ground nor replaceable vegetation (a player's chest, for example)
                // must never be broken.
                onObstructed.accept(headroom);
                return CellStep.waitFor("a block at " + headroom.toShortString() + " blocks the street");
            }
            return CellStep.build(dig(headroom, onObstructed));
        }

        if (cell.kind() == PathRoute.Kind.RAISED || cell.kind() == PathRoute.Kind.BRIDGE) {
            BlockState supportState = level.getBlockState(support);
            if (supportState.isAir() || supportState.canBeReplaced()) {
                if (ctx.villager().getBoundingBox().intersects(new AABB(support))) {
                    Optional<BlockPos> aside = standAside(level, support, ctx.villager());
                    if (aside.isPresent()) {
                        return CellStep.build(new MoveTo(aside.get(), STAND_ASIDE_REACH));
                    }
                }
                Item item = cell.kind() == PathRoute.Kind.BRIDGE ? Items.OAK_PLANKS : Items.DIRT;
                BlockState placed = cell.kind() == PathRoute.Kind.BRIDGE ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                SimpleContainer inventory = ctx.villager().getInventory();
                if (Inventories.count(inventory, stack -> stack.getItem() == item) <= 0) {
                    return withdraw(ctx, village, item);
                }
                return CellStep.build(TaskSequence.of(MoveTo.digOut(surface, BuilderJob.WORK_REACH), new PlaceBlock(support, placed, item)));
            }
        } else if (cell.kind() == PathRoute.Kind.CUT) {
            BlockPos cutTarget = cutTarget(level, support);
            if (cutTarget != null) {
                return CellStep.build(dig(cutTarget, onObstructed));
            }
        }

        if (level.getBlockState(support).is(BlockTags.DIRT)) {
            return CellStep.build(TaskSequence.of(MoveTo.digOut(surface, BuilderJob.WORK_REACH), new RequireDirt(support, onObstructed),
                    new MakePath(support)));
        }
        return CellStep.BUILT;
    }

    /**
     * Walks to {@code pos}, then re-checks {@link #clearable} right before breaking it: a block placed there since
     * the cell was planned (a player's chest, say) is left alone and reported obstructed instead, just as
     * {@link ChopTree} re-checks a log right before it breaks it rather than trusting a check made when it started.
     */
    private static TaskSequence dig(BlockPos pos, Consumer<BlockPos> onObstructed) {
        return TaskSequence.of(MoveTo.digOut(pos, BuilderJob.WORK_REACH), new RequireClearable(pos, onObstructed), new BreakBlock(pos),
                new PickUpItems(pos, 2.0, PathWork::isBlockItem));
    }

    /** An instant check, run as a {@link TaskSequence} step right before a {@link BreakBlock} it guards. */
    private static final class RequireClearable implements Task {
        private final BlockPos pos;
        private final Consumer<BlockPos> onObstructed;

        RequireClearable(BlockPos pos, Consumer<BlockPos> onObstructed) {
            this.pos = pos;
            this.onObstructed = onObstructed;
        }

        @Override
        public Status tick(TaskContext ctx) {
            if (clearable(ctx.level(), pos)) {
                return Status.SUCCESS;
            }
            onObstructed.accept(pos);
            return Status.FAILED;
        }

        @Override
        public String describe(TaskContext ctx) {
            return "checking " + pos.toShortString() + " is still clear to dig";
        }
    }

    /**
     * An instant check, run as a {@link TaskSequence} step right before a {@link MakePath} it guards: a block placed
     * over dirt-like ground since the cell was planned (a player's block, say) is left alone and reported obstructed
     * instead of being paved over, the same way {@link RequireClearable} guards a {@link BreakBlock}.
     */
    private static final class RequireDirt implements Task {
        private final BlockPos pos;
        private final Consumer<BlockPos> onObstructed;

        RequireDirt(BlockPos pos, Consumer<BlockPos> onObstructed) {
            this.pos = pos;
            this.onObstructed = onObstructed;
        }

        @Override
        public Status tick(TaskContext ctx) {
            if (ctx.level().getBlockState(pos).is(BlockTags.DIRT)) {
                return Status.SUCCESS;
            }
            onObstructed.accept(pos);
            return Status.FAILED;
        }

        @Override
        public String describe(TaskContext ctx) {
            return "checking " + pos.toShortString() + " is still dirt-like ground";
        }
    }

    /** Withdraws up to {@link #WITHDRAW_BATCH} of the wanted item, or waits when the storehouse has none either. */
    private static CellStep withdraw(TaskContext ctx, VillageData village, Item item) {
        ServerLevel level = ctx.level();
        BlockPos storehouse = village.storehousePos();
        long have = storehouse != null && level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity ? entity.count(item) : 0;
        if (have <= 0) {
            return CellStep.waitFor("dirt or oak planks for the path");
        }
        int amount = (int) Math.min(WITHDRAW_BATCH, have);
        return new CellStep(TaskSequence.of(MoveTo.digOut(storehouse, 2.5), new Withdraw(storehouse, Map.of(item, amount))), false, null);
    }

    /**
     * A standable cell within {@link #STAND_ASIDE_DISTANCE} blocks (horizontally) of {@code target}, nearest to the
     * villager first. Without this, a villager whose approach already left it standing on the spot a support block
     * must fill would be embedded by its own placement.
     */
    private static Optional<BlockPos> standAside(ServerLevel level, BlockPos target, Villager villager) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -STAND_ASIDE_DISTANCE; dx <= STAND_ASIDE_DISTANCE; dx++) {
                for (int dz = -STAND_ASIDE_DISTANCE; dz <= STAND_ASIDE_DISTANCE; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != STAND_ASIDE_DISTANCE) {
                        continue;
                    }
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!isStandable(level, feet)) {
                        continue;
                    }
                    double distance = villager.distanceToSqr(Vec3.atBottomCenterOf(feet));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = feet;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean isStandable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    /** The first of {@code surface} and {@code surface.above()} that is not air. */
    private static @Nullable BlockPos firstOccupied(ServerLevel level, BlockPos surface) {
        for (BlockPos pos : new BlockPos[] {surface, surface.above()}) {
            if (!level.getBlockState(pos).isAir()) {
                return pos;
            }
        }
        return null;
    }

    /** Whether the paver may clear {@code pos}: natural ground it can dig, or vegetation a placement can replace. */
    private static boolean clearable(ServerLevel level, BlockPos pos) {
        return DigStep.isDiggable(level, pos) || level.getBlockState(pos).canBeReplaced();
    }

    /** The nearest block below {@code support} the paver must break through to reach solid ground, within {@link #MAX_CUT_DEPTH}. */
    private static @Nullable BlockPos cutTarget(ServerLevel level, BlockPos support) {
        BlockPos pos = support;
        for (int i = 0; i < MAX_CUT_DEPTH; i++) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.canBeReplaced()) {
                return null;
            }
            if (!state.isAir()) {
                return pos;
            }
            pos = pos.below();
        }
        return null;
    }

    static boolean isBlockItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }
}
