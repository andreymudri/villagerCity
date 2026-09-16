package dev.andreymudri.villagercity.village.plot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.andreymudri.villagercity.village.Footprint;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlotRulesTest {
    private static Column ground(int y) {
        return new Column(y, true, false);
    }

    @Test
    void flatNaturalGroundIsBuildable() {
        assertTrue(PlotRules.isBuildable(List.of(ground(64), ground(65), ground(64))));
        assertEquals(65, PlotRules.buildY(List.of(ground(64), ground(65), ground(64))));
    }

    @Test
    void rejectsSteepMissingArtificialOrWetGround() {
        assertFalse(PlotRules.isBuildable(List.of(ground(64), ground(66))));
        assertFalse(PlotRules.isBuildable(List.of(ground(64), Column.MISSING)));
        assertFalse(PlotRules.isBuildable(List.of(ground(64), new Column(64, false, false))));
        assertFalse(PlotRules.isBuildable(List.of(ground(64), new Column(64, false, true))));
        assertFalse(PlotRules.isBuildable(List.of()));
    }

    @Test
    void overlapIncludesTheMargin() {
        Footprint house = new Footprint(0, 0, 4, 4);
        assertTrue(PlotRules.overlapsAny(new Footprint(5, 0, 9, 4), List.of(house)));
        assertFalse(PlotRules.overlapsAny(new Footprint(6, 0, 10, 4), List.of(house)));
    }

    @Test
    void spiralStartsAtCenterAndGrowsByRings() {
        List<int[]> spiral = PlotRules.spiral(2, 1);
        assertEquals(25, spiral.size());
        assertEquals(0, spiral.get(0)[0]);
        assertEquals(0, spiral.get(0)[1]);
        for (int i = 1; i <= 8; i++) {
            assertEquals(1, Math.max(Math.abs(spiral.get(i)[0]), Math.abs(spiral.get(i)[1])));
        }
        Set<Long> unique = new HashSet<>();
        spiral.forEach(o -> unique.add(((long) o[0] << 32) ^ (o[1] & 0xffffffffL)));
        assertEquals(25, unique.size());
    }

    @Test
    void spiralHonoursStep() {
        List<int[]> spiral = PlotRules.spiral(4, 2);
        assertEquals(25, spiral.size());
        spiral.forEach(o -> {
            assertEquals(0, o[0] % 2);
            assertEquals(0, o[1] % 2);
        });
    }

    @Test
    void withinReachChecksEveryCorner() {
        assertTrue(PlotRules.withinReach(new Footprint(-4, -4, 4, 4), 0, 0, 4));
        assertFalse(PlotRules.withinReach(new Footprint(-4, -4, 5, 4), 0, 0, 4));
    }
}
