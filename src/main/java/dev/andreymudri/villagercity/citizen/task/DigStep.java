package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.WorldPermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * One step of a staircase dug through natural ground toward a target a trapped citizen cannot path to: clears the
 * cells for a step up, across or down, then walks into it. Only natural ground is dug (stone, dirt, sand, gravel,
 * ores and the like), never a block next to a fluid or under a falling block, and each break asks
 * {@link WorldPermissions#mayBreak}. Blocks drop their loot as when a player mines them.
 */
public final class DigStep implements Task {
    /** Ticks to walk into the dug step before giving up. */
    public static final int ENTER_TICKS = 60;
    /** Tool speed the digging is at least as fast as, so a bare-handed citizen digs stone in about a second. */
    public static final float MIN_DIG_SPEED = 2.0f;

    private final List<BlockPos> toClear;
    private final BlockPos feet;
    private int index;
    private int progress;
    private int required = -1;
    private int entering;

    private DigStep(List<BlockPos> toClear, BlockPos feet) {
        this.toClear = toClear;
        this.feet = feet;
    }

    /** The step whose destination lies nearest the target, preferring to climb or descend toward it; empty when none can be dug. */
    public static Optional<DigStep> toward(ServerLevel level, Villager villager, BlockPos target) {
        BlockPos from = villager.blockPosition();
        int dy = Integer.signum(target.getY() - from.getY());
        List<Direction> directions = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        directions.sort(Comparator.comparingDouble(direction -> horizontalDistanceSqr(from.relative(direction), target)));
        int[] rises = dy > 0 ? new int[] {1, 0} : dy < 0 ? new int[] {-1, 0} : new int[] {0, 1, -1};
        for (Direction direction : directions) {
            for (int rise : rises) {
                Optional<DigStep> step = plan(level, villager, from, target, direction, rise);
                if (step.isPresent()) {
                    return step;
                }
            }
        }
        return Optional.empty();
    }

    private static double horizontalDistanceSqr(BlockPos pos, BlockPos target) {
        double dx = pos.getX() - target.getX();
        double dz = pos.getZ() - target.getZ();
        return dx * dx + dz * dz;
    }

    private static Optional<DigStep> plan(ServerLevel level, Villager villager, BlockPos from, BlockPos target, Direction direction, int rise) {
        BlockPos feet = from.relative(direction).above(rise);
        List<BlockPos> cells = new ArrayList<>();
        if (rise > 0) {
            // Room above the current cell to jump up, then the step itself.
            cells.add(from.above(2));
        }
        if (rise < 0) {
            // The cell the head passes through on the way down.
            cells.add(feet.above(2));
        }
        cells.add(feet.above());
        cells.add(feet);
        if (!hasFloor(level, feet, rise)) {
            return Optional.empty();
        }
        List<BlockPos> toClear = new ArrayList<>();
        for (BlockPos cell : cells) {
            if (isOpen(level, cell)) {
                continue;
            }
            if (!isDiggable(level, cell) || !WorldPermissions.mayBreak(level, villager, cell)) {
                return Optional.empty();
            }
            toClear.add(cell);
        }
        if (toClear.isEmpty() && feet.distSqr(target) >= from.distSqr(target)) {
            // Open ground that brings the citizen no closer is the pathfinder's business, not a step to dig.
            return Optional.empty();
        }
        BlockPos top = cells.get(0).above();
        if (!toClear.isEmpty() && level.getBlockState(top).getBlock() instanceof FallingBlock) {
            return Optional.empty();
        }
        return Optional.of(new DigStep(toClear, feet.immutable()));
    }

    /** A step up or across needs a sturdy floor; across may also drop at most three blocks onto one. */
    private static boolean hasFloor(ServerLevel level, BlockPos feet, int rise) {
        int maxDrop = rise == 0 ? 3 : 0;
        for (int drop = 0; drop <= maxDrop; drop++) {
            BlockPos floor = feet.below(drop + 1);
            if (level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                return true;
            }
            if (!isOpen(level, floor)) {
                return false;
            }
        }
        return false;
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    /** Natural ground with no fluid beside it (digging it must not flood the tunnel). */
    public static boolean isDiggable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        boolean natural = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Tags.Blocks.ORES) || state.is(Blocks.CLAY) || state.is(BlockTags.SNOW)
                || state.is(Blocks.CALCITE) || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.MOSS_BLOCK);
        if (!natural || state.getDestroySpeed(level, pos) < 0) {
            return false;
        }
        for (Direction side : Direction.values()) {
            if (!level.getFluidState(pos.relative(side)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Status tick(TaskContext ctx) {
        ServerLevel level = ctx.level();
        Villager villager = ctx.villager();
        while (index < toClear.size() && isOpen(level, toClear.get(index))) {
            index++;
            required = -1;
        }
        if (index < toClear.size()) {
            return dig(level, villager, toClear.get(index));
        }
        if (villager.blockPosition().equals(feet) && villager.onGround()) {
            villager.getNavigation().stop();
            return Status.SUCCESS;
        }
        if (++entering > ENTER_TICKS) {
            villager.getNavigation().stop();
            return Status.FAILED;
        }
        // Steered directly: a path to a neighbouring cell counts as arrived before the villager moves. The move control
        // jumps on its own when the wanted position is a step up.
        villager.getNavigation().stop();
        villager.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, MoveTo.SPEED);
        return Status.RUNNING;
    }

    private Status dig(ServerLevel level, Villager villager, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (!isDiggable(level, cell) || !WorldPermissions.mayBreak(level, villager, cell)) {
            return Status.FAILED;
        }
        if (required < 0) {
            progress = 0;
            float speed = Math.max(MIN_DIG_SPEED, villager.getMainHandItem().getDestroySpeed(state));
            required = Math.max(1, (int) Math.ceil(state.getDestroySpeed(level, cell) * 30f / speed));
        }
        villager.getLookControl().setLookAt(Vec3.atCenterOf(cell));
        if (++progress % 5 == 1) {
            villager.swing(InteractionHand.MAIN_HAND);
        }
        if (progress < required) {
            level.destroyBlockProgress(villager.getId(), cell, Math.min(9, progress * 10 / required));
            return Status.RUNNING;
        }
        level.destroyBlockProgress(villager.getId(), cell, -1);
        Block.dropResources(state, level, cell, level.getBlockEntity(cell), villager, ItemStack.EMPTY);
        level.destroyBlock(cell, false, villager);
        required = -1;
        index++;
        return Status.RUNNING;
    }

    @Override
    public void stop(TaskContext ctx) {
        if (index < toClear.size()) {
            ctx.level().destroyBlockProgress(ctx.villager().getId(), toClear.get(index), -1);
        }
    }

    public BlockPos feet() {
        return feet;
    }
}
