package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.task.DigStep;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * How the paver builds one walkable cell, and whether a column can take one at a given height. A cell is the air cell
 * a villager walks in; its kind says how its support is built: left as the natural ground, raised with dirt, cut into
 * the ground, or bridged with oak planks over a fluid. {@link StreetWork} lays every street cell through {@link
 * #cellAt}, and {@link PathWork#planCell} builds it.
 */
public final class PathRoute {
    /** How far a cell may sit above or below its own column's ground before it can no longer be built there. */
    public static final int MAX_ABOVE_GROUND = 2;

    /** One built cell: the air cell a villager walks in, and how its support was built. */
    public record Cell(BlockPos surface, Kind kind) {
    }

    /** How a cell's support was built: level with the natural ground, raised, cut into it, or bridged over water. */
    public enum Kind { GROUND, RAISED, CUT, BRIDGE }

    /** A column's ground: the first free y above its topmost solid block, and whether a fluid lies there instead. */
    public record Ground(int y, boolean fluid) {
        static final Ground MISSING = new Ground(Integer.MIN_VALUE, false);

        public boolean present() {
            return y != Integer.MIN_VALUE;
        }
    }

    private PathRoute() {
    }

    /**
     * The cell at column ({@code x}, {@code z}) with its walking height at {@code h}, or empty when the paver cannot
     * build one there: the column is unloaded, its ground is a fence, wall, gate or cactus, {@code h} is more than
     * {@link #MAX_ABOVE_GROUND} below dry ground, the cell or the one above it is a block the paver may not clear, or
     * the support is something it may not stand on or build over. A fluid column, or one more than {@link
     * #MAX_ABOVE_GROUND} below {@code h}, is a {@link Kind#BRIDGE}.
     */
    public static Optional<Cell> cellAt(ServerLevel level, int x, int z, int h) {
        Ground ground = groundAt(level, x, z);
        if (!ground.present()) {
            return Optional.empty();
        }
        // A column whose own natural ground is a fence, wall, gate or similar refuses the whole column, not merely a
        // GROUND cell standing directly on it: otherwise a RAISED cell one block higher, whose support is the open air
        // right above the obstruction, caps a player's fence line with a placed dirt path. Never applied to a fluid
        // column: that is exactly what BRIDGE is for.
        if (!ground.fluid() && !standableSupport(level, new BlockPos(x, ground.y() - 1, z))) {
            return Optional.empty();
        }
        if (!ground.fluid() && h < ground.y() - MAX_ABOVE_GROUND) {
            return Optional.empty();
        }
        BlockPos surface = new BlockPos(x, h, z);
        boolean bridge = ground.fluid() || h - ground.y() > MAX_ABOVE_GROUND;
        Kind kind = bridge ? Kind.BRIDGE : kindOf(h, ground);
        if (blocked(level, surface) || blocked(level, surface.above())) {
            return Optional.empty();
        }
        // GROUND cells stand right on top of whatever the column's topmost solid block already is, untouched either
        // way (see PathWork#planCell): a foreign one (a player's cobblestone terrace, say) is a fine surface to cross,
        // so only RAISED/CUT/BRIDGE - which do place or dig at the support - need it to be paveable rather than a
        // foreign obstruction (see supportBlocked). But it must still be something a villager can stand on (see
        // standableSupport).
        if (kind == Kind.GROUND ? !standableSupport(level, surface.below()) : supportBlocked(level, surface.below())) {
            return Optional.empty();
        }
        return Optional.of(new Cell(surface, kind));
    }

    private static Kind kindOf(int h, Ground ground) {
        if (h == ground.y()) {
            return Kind.GROUND;
        }
        return h > ground.y() ? Kind.RAISED : Kind.CUT;
    }

    /** A cell blocks the paver unless it is open (air, replaceable vegetation) or natural ground the paver can dig. */
    private static boolean blocked(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !DigStep.isDiggable(level, pos) && !state.canBeReplaced();
    }

    /**
     * A RAISED, CUT or BRIDGE cell's support blocks the cell only if it is a foreign object: not open ground a
     * RAISED or BRIDGE cell would place its own support into, not what building has already put there (dirt still
     * to pave, a finished dirt path, or a bridge's oak planks), and not natural ground a CUT simply leaves exposed.
     * Never called for a GROUND cell (see {@link #cellAt}): a GROUND cell's support is never touched either way, so a
     * foreign one there is simply a fine surface to cross, not something to bury under new dirt.
     */
    private static boolean supportBlocked(ServerLevel level, BlockPos support) {
        BlockState state = level.getBlockState(support);
        return !state.canBeReplaced() && !state.is(BlockTags.DIRT) && !state.is(Blocks.DIRT_PATH)
                && !state.is(Blocks.OAK_PLANKS) && !DigStep.isDiggable(level, support);
    }

    /**
     * Whether a GROUND cell's support is something a villager can actually stand on, not merely something that
     * satisfies {@code groundAt}'s {@code blocksMotion()} check. A fence, wall, fence gate or cactus all block
     * motion, so the heightmap and {@code groundAt} both report a walkable surface right on top of them, but none
     * of the four is a flat, safe floor: a fence/wall/gate's collision shape reaches well above a full block, so a
     * "surface" cell resting directly on top of one still clips into it, and a cactus is dangerous regardless of
     * its own shape. The generic {@code VoxelShape} check below is a second, tag-independent net for the same
     * class of problem, and also rejects an empty or fluid shape.
     */
    private static boolean standableSupport(ServerLevel level, BlockPos support) {
        BlockState state = level.getBlockState(support);
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)
                || state.is(Blocks.CACTUS)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, support);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
    }

    /**
     * The column's ground: the first free y above the topmost block that blocks motion, leaves and the GameTest
     * barrier ceiling aside (as {@code PlotPlanner.sample}). A fluid column's ground sits right above the fluid.
     */
    public static Ground groundAt(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        if (!level.isLoaded(pos)) {
            return Ground.MISSING;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int y = surface; y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(pos.setY(y));
            if (!state.getFluidState().isEmpty()) {
                return new Ground(y + 1, true);
            }
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BARRIER)) {
                return new Ground(y + 1, false);
            }
        }
        return Ground.MISSING;
    }
}
