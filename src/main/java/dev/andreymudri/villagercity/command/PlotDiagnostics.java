package dev.andreymudri.villagercity.command;

import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Explains, for one spot, why the plot search would take it or turn it down. Every rule it reports is the same one
 * {@link PlotPlanner} applies, in the same order; {@code VillageCommandTests} pins the two to the same verdict, so a
 * change to the planner that this misses fails that test rather than quietly lying to whoever asked.
 */
public final class PlotDiagnostics {
    private PlotDiagnostics() {
    }

    /** Human-readable lines about {@code footprint} for {@code village}, the first line being the verdict. */
    public static List<String> explain(ServerLevel level, VillageData village, BlockPos center, Vec3i size) {
        List<String> lines = new ArrayList<>();
        BlockPos bell = village.center();
        int reach = Math.min(village.radius() + PlotPlanner.SEARCH_MARGIN, PlotPlanner.MAX_REACH);
        int minX = center.getX() - size.getX() / 2;
        int minZ = center.getZ() - size.getZ() / 2;
        Footprint footprint = new Footprint(minX, minZ, minX + size.getX() - 1, minZ + size.getZ() - 1);
        Footprint area = footprint.inflate(PlotRules.MARGIN);
        lines.add("spot " + footprint.minX() + "," + footprint.minZ() + " .. " + footprint.maxX() + "," + footprint.maxZ()
                + " (" + size.getX() + "x" + size.getZ() + " plus " + PlotRules.MARGIN + " margin)");

        boolean ok = true;
        int distance = Math.max(Math.abs(center.getX() - bell.getX()), Math.abs(center.getZ() - bell.getZ()));
        if (!PlotRules.withinReach(footprint, bell.getX(), bell.getZ(), reach)) {
            lines.add("NO  too far from the bell: " + distance + " blocks away, the search reaches " + reach);
            ok = false;
        }
        if (PlotRules.overlapsAny(footprint, village.occupiedFootprints())) {
            lines.add("NO  overlaps a house, plot or the storehouse");
            ok = false;
        }

        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int missing = 0;
        int fluid = 0;
        int unnatural = 0;
        BlockPos worst = center;
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                Ground ground = groundAt(level, x, z, bell.getY());
                if (ground == null) {
                    missing++;
                    continue;
                }
                if (ground.fluid()) {
                    fluid++;
                }
                if (!ground.natural()) {
                    unnatural++;
                    worst = new BlockPos(x, ground.y(), z);
                }
                if (ground.y() < low) {
                    low = ground.y();
                }
                if (ground.y() > high) {
                    high = ground.y();
                }
            }
        }
        if (missing > 0) {
            lines.add("NO  " + missing + " column(s) out of the village's height window or not loaded"
                    + " (the bell is at y" + bell.getY() + ", the window is " + PlotPlanner.MAX_VERTICAL + " blocks)");
            ok = false;
        }
        if (fluid > 0) {
            lines.add("NO  " + fluid + " column(s) end in water or lava");
            ok = false;
        }
        if (unnatural > 0) {
            lines.add("NO  " + unnatural + " column(s) are not natural ground, e.g. " + worst.toShortString());
            ok = false;
        }
        if (high >= low) {
            int variance = high - low;
            String verdict = variance <= PlotRules.MAX_HEIGHT_VARIANCE ? "ok" : "too uneven";
            lines.add((variance <= PlotRules.MAX_HEIGHT_VARIANCE ? "yes " : "NO  ") + "ground y" + low + " to y" + high
                    + ", varies by " + variance + " (" + verdict + ", the limit is " + PlotRules.MAX_HEIGHT_VARIANCE + ")");
            ok &= variance <= PlotRules.MAX_HEIGHT_VARIANCE;
        }
        lines.add(0, ok ? "This spot is buildable." : "This spot is not buildable:");
        return lines;
    }

    /** What {@link PlotPlanner} would call this column's ground, or null when it is missing for the planner too. */
    private static Ground groundAt(ServerLevel level, int x, int z, int bellY) {
        if (!level.isLoaded(new BlockPos(x, bellY, z))) {
            return null;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = surface; y >= bellY - PlotPlanner.MAX_VERTICAL; y--) {
            BlockState state = level.getBlockState(pos.setY(y));
            if (y > bellY + PlotPlanner.MAX_VERTICAL) {
                if (blocks(state)) {
                    return null;
                }
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return new Ground(y + 1, false, true);
            }
            if (blocks(state)) {
                boolean natural = state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                        || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL);
                return new Ground(y + 1, natural, false);
            }
        }
        return null;
    }

    private static boolean blocks(BlockState state) {
        return state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BARRIER);
    }

    private record Ground(int y, boolean natural, boolean fluid) {
    }
}
