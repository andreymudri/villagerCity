package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.Footprint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/** Pure plot-validity rules; no world access. */
public final class PlotRules {
    public static final int MARGIN = 1;
    /** Fewest open columns between a house's footprint and another house's or plot's, along x or along z. */
    public static final int HOUSE_GAP = 5;
    /** One lot in this many is left empty, so a village has gaps like a vanilla one. */
    public static final int SKIP_ONE_IN = 8;
    /**
     * Columns along each side of a lot, the square cells of a grid laid on the world from x 0, z 0: one 5 by 5 house and
     * the {@link #HOUSE_GAP} beside it, so a lot left empty is a gap about the size of a missing house.
     */
    public static final int LOT_SIZE = 10;

    /** Which lots are left empty, by lot coordinates ({@link #lotOf}); {@link #skippedLot} in a real village. */
    @FunctionalInterface
    public interface LotSkip {
        boolean skipped(int lotX, int lotZ);
    }

    private PlotRules() {
    }

    /**
     * True when the columns are natural ground with no fluid that a floor at {@link #buildY} covers with no earthwork
     * ({@link Earthwork#needsNone}): what a village without a paver may build on.
     */
    public static boolean isBuildable(List<Column> columns) {
        if (columns.isEmpty()) {
            return false;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Column column : columns) {
            if (!column.present() || !column.natural() || column.fluid()) {
                return false;
            }
            min = Math.min(min, column.groundY());
            max = Math.max(max, column.groundY());
        }
        return max - min <= Earthwork.FLOOR_SPAN;
    }

    public static int buildY(List<Column> columns) {
        return columns.stream().mapToInt(Column::groundY).max().orElseThrow();
    }

    /** True when the candidate, grown by {@link #MARGIN}, touches any occupied footprint. */
    public static boolean overlapsAny(Footprint candidate, List<Footprint> occupied) {
        Footprint grown = candidate.inflate(MARGIN);
        return occupied.stream().anyMatch(grown::intersects);
    }

    /**
     * True when the candidate's footprint comes within {@link #HOUSE_GAP} columns of any of {@code houses} along both
     * x and z, so fewer than {@link #HOUSE_GAP} open columns lie between them either way.
     */
    public static boolean tooCloseToAHouse(Footprint candidate, List<Footprint> houses) {
        Footprint grown = candidate.inflate(HOUSE_GAP);
        return houses.stream().anyMatch(grown::intersects);
    }

    /**
     * Whether levelling a pad to its floor is work the village can do: none at all without a paver, and with one at
     * most {@link Earthwork#MAX_VOLUME} blocks moved with no column more than {@link Earthwork#MAX_COLUMN_STEP} off
     * the floor.
     */
    public static boolean earthworkAllowed(int volume, int columnStep, boolean paver) {
        return paver ? volume <= Earthwork.MAX_VOLUME && columnStep <= Earthwork.MAX_COLUMN_STEP : volume == 0;
    }

    /** The lot coordinate of a column's x or z. */
    public static int lotOf(int column) {
        return Math.floorDiv(column, LOT_SIZE);
    }

    /** Whether this lot is one of those left empty: a hash of its coordinates, so it never changes. */
    public static boolean skippedLot(int lotX, int lotZ) {
        return Math.floorMod(Mth.murmurHash3Mixer(Long.hashCode(Mth.getSeed(lotX, 0, lotZ))), SKIP_ONE_IN) == 0;
    }

    /** Whether the lot holding this column ({@link #lotOf}) is left empty. */
    public static boolean skipped(BlockPos column) {
        return skippedLot(lotOf(column.getX()), lotOf(column.getZ()));
    }

    /** Whether any column of the footprint lies in a lot {@code skip} leaves empty. */
    public static boolean coversSkippedLot(Footprint footprint, LotSkip skip) {
        for (int lotX = lotOf(footprint.minX()); lotX <= lotOf(footprint.maxX()); lotX++) {
            for (int lotZ = lotOf(footprint.minZ()); lotZ <= lotOf(footprint.maxZ()); lotZ++) {
                if (skip.skipped(lotX, lotZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Horizontal offsets ring by ring (Chebyshev distance), center first; within a ring ordered by dz then dx. */
    public static List<int[]> spiral(int radius, int step) {
        if (radius < 0 || step < 1) {
            throw new IllegalArgumentException("radius must be >= 0 and step >= 1");
        }
        List<int[]> offsets = new ArrayList<>();
        offsets.add(new int[] {0, 0});
        for (int ring = step; ring <= radius; ring += step) {
            for (int dz = -ring; dz <= ring; dz += step) {
                for (int dx = -ring; dx <= ring; dx += step) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        offsets.add(new int[] {dx, dz});
                    }
                }
            }
        }
        return offsets;
    }

    public static boolean withinReach(Footprint footprint, int centerX, int centerZ, int reach) {
        return Math.abs(footprint.minX() - centerX) <= reach && Math.abs(footprint.maxX() - centerX) <= reach
                && Math.abs(footprint.minZ() - centerZ) <= reach && Math.abs(footprint.maxZ() - centerZ) <= reach;
    }
}
