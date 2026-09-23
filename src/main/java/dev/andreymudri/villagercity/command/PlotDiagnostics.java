package dev.andreymudri.villagercity.command;

import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import dev.andreymudri.villagercity.village.plot.Column;
import dev.andreymudri.villagercity.village.plot.Earthwork;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.plot.PlotRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;

/**
 * Explains, for one spot, why the plot search would take it or turn it down. Every rule it reports, but one, is the
 * same one {@link PlotPlanner#findSite} applies, through the same methods, with a paver's earth budget exactly when
 * the village has a paver, as the builder searches; {@code VillageCommandTests} pins the two to the same verdict, so
 * a change to the planner that this misses fails that test rather than quietly lying to whoever asked. The one rule
 * missing is the slice straight ahead of a street end ({@link PlotPlanner}'s own {@code slicesAhead}, private to it):
 * a pad there can still be called buildable here while the real search would never offer it.
 */
public final class PlotDiagnostics {
    private PlotDiagnostics() {
    }

    /** Human-readable lines about {@code footprint} for {@code village}, the first line being the verdict. */
    public static List<String> explain(ServerLevel level, VillageData village, BlockPos center, Vec3i size) {
        return explain(level, village, center, size, PlotRules::skippedLot);
    }

    /**
     * As {@link #explain(ServerLevel, VillageData, BlockPos, Vec3i)}, with {@code skip} deciding which lots are left
     * empty instead of {@link PlotRules#skippedLot}, whose answer depends on where in the world the pad lies.
     */
    public static List<String> explain(ServerLevel level, VillageData village, BlockPos center, Vec3i size, PlotRules.LotSkip skip) {
        List<String> lines = new ArrayList<>();
        BlockPos bell = village.center();
        boolean paver = village.jobCount(JobType.PAVER) > 0;
        int reach = PlotPlanner.searchReach(village);
        int window = PlotPlanner.verticalReach(village.radius());
        int minX = center.getX() - size.getX() / 2;
        int minZ = center.getZ() - size.getZ() / 2;
        Footprint footprint = new Footprint(minX, minZ, minX + size.getX() - 1, minZ + size.getZ() - 1);
        Footprint area = footprint.inflate(PlotRules.MARGIN);
        lines.add("spot " + footprint.minX() + "," + footprint.minZ() + " .. " + footprint.maxX() + "," + footprint.maxZ()
                + " (" + size.getX() + "x" + size.getZ() + " plus " + PlotRules.MARGIN + " margin)");

        boolean ok = true;
        int distance = Math.max(Math.abs(center.getX() - bell.getX()), Math.abs(center.getZ() - bell.getZ()));
        // Beside a street, the pads are bounded by how far the streets run, not by the search reach.
        if (village.streets().isEmpty() && !PlotRules.withinReach(footprint, bell.getX(), bell.getZ(), reach)) {
            lines.add("NO  too far from the bell: " + distance + " blocks away, the search reaches " + reach);
            ok = false;
        }
        if (PlotRules.overlapsAny(footprint, village.occupiedFootprints())) {
            lines.add("NO  overlaps a house, plot or the storehouse");
            ok = false;
        }
        if (PlotRules.tooCloseToAHouse(footprint, PlotPlanner.houseFootprints(village))) {
            lines.add("NO  spacing: fewer than " + PlotRules.HOUSE_GAP + " open columns from a house or plot (houses stand at least "
                    + PlotRules.HOUSE_GAP + " apart)");
            ok = false;
        }

        List<StreetCell> touching = List.of();
        if (village.streets().isEmpty()) {
            lines.add("    the village has no streets yet, so the spot is levelled on its own ground");
        } else {
            touching = PlotPlanner.touchingStreets(village, area);
            if (touching.isEmpty()) {
                StreetCell nearest = nearest(village.streets(), center);
                int away = Math.max(Math.abs(nearest.pos().getX() - center.getX()), Math.abs(nearest.pos().getZ() - center.getZ()));
                lines.add("NO  street: the spot touches no street; the nearest street cell is " + away + " blocks away, "
                        + nearest.hops() + " hops out");
                ok = false;
            }
        }

        int paths = 0;
        int missing = 0;
        int fluid = 0;
        int unnatural = 0;
        BlockPos worst = center;
        int[] groundYs = new int[(area.maxX() - area.minX() + 1) * (area.maxZ() - area.minZ() + 1)];
        int columns = 0;
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                if (village.isPathColumn(x, z)) {
                    paths++;
                }
                Column column = PlotPlanner.sample(level, x, z, bell.getY(), window);
                if (!column.present()) {
                    missing++;
                    continue;
                }
                groundYs[columns++] = column.groundY();
                if (column.fluid()) {
                    fluid++;
                }
                if (!column.natural()) {
                    unnatural++;
                    worst = new BlockPos(x, column.groundY(), z);
                }
            }
        }
        if (paths > 0) {
            lines.add("NO  " + paths + " column(s) are laid path");
            ok = false;
        }
        if (missing > 0) {
            lines.add("NO  " + missing + " column(s) out of the village's height window or not loaded"
                    + " (the bell is at y" + bell.getY() + ", the window is " + window + " blocks)");
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
        if (missing == 0) {
            PlotPlanner.Floor floor = PlotPlanner.floor(groundYs, touching, paver);
            String at = floor.street() == null ? "" : " (the street's height, " + floor.street().hops() + " hops out)";
            int budget = paver ? Earthwork.MAX_VOLUME : 0;
            boolean volumeOk = floor.earthwork() <= budget;
            lines.add((volumeOk ? "yes " : "NO  ") + "earthwork: levelling to y" + floor.y() + at + " moves " + floor.earthwork()
                    + " blocks, the budget is " + budget + (paver ? " for the paver" : " with no paver"));
            if (paver) {
                boolean stepOk = floor.columnStep() <= Earthwork.MAX_COLUMN_STEP;
                lines.add((stepOk ? "yes " : "NO  ") + "column step: the worst column is " + floor.columnStep()
                        + " blocks off the floor, the limit is " + Earthwork.MAX_COLUMN_STEP);
            }
            ok &= floor.accepted();
            if (floor.accepted() && PlotPlanner.fillObstructed(level, area, floor.y(), Earthwork.MAX_COLUMN_STEP)) {
                lines.add("NO  fill: something the paver may not clear, such as a torch, stands where the fill would go");
                ok = false;
            }
            if (!touching.isEmpty() && PlotRules.coversSkippedLot(footprint, skip)) {
                lines.add("NO  skipped: one lot in " + PlotRules.SKIP_ONE_IN + " beside a street is left empty, and this footprint covers one");
                ok = false;
            }
        }
        lines.add(0, ok ? "This spot is buildable." : "This spot is not buildable:");
        return lines;
    }

    private static StreetCell nearest(List<StreetCell> streets, BlockPos center) {
        StreetCell nearest = streets.get(0);
        int best = Integer.MAX_VALUE;
        for (StreetCell cell : streets) {
            int away = Math.max(Math.abs(cell.pos().getX() - center.getX()), Math.abs(cell.pos().getZ() - center.getZ()));
            if (away < best) {
                best = away;
                nearest = cell;
            }
        }
        return nearest;
    }
}
