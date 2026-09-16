package dev.andreymudri.villagercity.village.plot;

import dev.andreymudri.villagercity.village.Footprint;
import java.util.ArrayList;
import java.util.List;

/** Pure plot-validity rules; no world access. */
public final class PlotRules {
    public static final int MAX_HEIGHT_VARIANCE = 1;
    public static final int MARGIN = 1;

    private PlotRules() {
    }

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
        return max - min <= MAX_HEIGHT_VARIANCE;
    }

    public static int buildY(List<Column> columns) {
        return columns.stream().mapToInt(Column::groundY).max().orElseThrow();
    }

    /** True when the candidate, grown by {@link #MARGIN}, touches any occupied footprint. */
    public static boolean overlapsAny(Footprint candidate, List<Footprint> occupied) {
        Footprint grown = candidate.inflate(MARGIN);
        return occupied.stream().anyMatch(grown::intersects);
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
