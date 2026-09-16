package dev.andreymudri.villagercity.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.andreymudri.villagercity.citizen.JobType;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

class VillageDataTest {
    private static final Vec3i HOUSE = new Vec3i(5, 5, 5);

    @Test
    void footprintIntersectsAndInflates() {
        Footprint a = Footprint.of(new BlockPos(0, 64, 0), HOUSE);
        assertEquals(new Footprint(0, 0, 4, 4), a);
        assertTrue(a.intersects(new Footprint(4, 4, 6, 6)));
        assertFalse(a.intersects(new Footprint(5, 0, 9, 4)));
        assertTrue(a.inflate(1).intersects(new Footprint(5, 0, 9, 4)));
        assertThrows(IllegalArgumentException.class, () -> new Footprint(3, 0, 2, 0));
    }

    @Test
    void addHouseExpandsRadiusToCoverIt() {
        VillageData village = new VillageData(UUID.randomUUID(), new BlockPos(0, 64, 0), 10);
        village.addHouse(new BuildingRecord("villagercity:blueprint/starter_house", new BlockPos(20, 64, 0), HOUSE));
        assertEquals(1, village.houseCount());
        // farthest corner (24, 4): ceil(sqrt(592)) = 25, plus padding 8
        assertEquals(33, village.radius());
        assertTrue(village.contains(new BlockPos(33, 70, 0)));
        assertFalse(village.contains(new BlockPos(34, 64, 0)));
    }

    @Test
    void addHouseNeverShrinksRadius() {
        VillageData village = new VillageData(UUID.randomUUID(), new BlockPos(0, 64, 0), 48);
        village.addHouse(new BuildingRecord("minecraft:home", new BlockPos(2, 64, 2), new Vec3i(1, 1, 1)));
        assertEquals(48, village.radius());
    }

    @Test
    void plotsAreFoundByBuilderAndRemovedById() {
        VillageData village = new VillageData(UUID.randomUUID(), BlockPos.ZERO, 16);
        UUID builder = UUID.randomUUID();
        Plot plot = new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", new BlockPos(5, 0, 5), HOUSE, builder);
        village.addPlot(plot);
        assertEquals(plot, village.plotBuiltBy(builder).orElseThrow());
        assertTrue(village.plotBuiltBy(UUID.randomUUID()).isEmpty());
        assertTrue(village.removePlot(plot.id()));
        assertTrue(village.plots().isEmpty());
    }

    @Test
    void occupiedFootprintsCoverCenterStorehouseHousesAndPlots() {
        VillageData village = new VillageData(UUID.randomUUID(), new BlockPos(0, 64, 0), 32);
        village.setStorehousePos(new BlockPos(3, 64, 0));
        village.addHouse(new BuildingRecord("minecraft:home", new BlockPos(10, 64, 10), new Vec3i(1, 1, 1)));
        village.addPlot(new Plot(UUID.randomUUID(), "x:y", new BlockPos(-10, 64, -10), HOUSE, UUID.randomUUID()));
        assertEquals(4, village.occupiedFootprints().size());
        assertTrue(village.occupiedFootprints().contains(new Footprint(0, 0, 0, 0)));
        assertTrue(village.occupiedFootprints().contains(new Footprint(3, 0, 3, 0)));
        assertTrue(village.occupiedFootprints().contains(new Footprint(-10, -10, -6, -6)));
    }

    @Test
    void newVillageStartsInDarkAgeAndManaged() {
        VillageData village = new VillageData(UUID.randomUUID(), BlockPos.ZERO, VillageData.DEFAULT_RADIUS);
        assertEquals(VillageAge.DARK, village.age());
        assertTrue(village.managed());
        assertEquals(null, village.storehousePos());
    }

    @Test
    void citizenRosterTracksJobsByVillager() {
        VillageData village = new VillageData(UUID.randomUUID(), BlockPos.ZERO, 16);
        UUID lumberjack = UUID.randomUUID();
        UUID builder = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        village.setCitizen(lumberjack, JobType.LUMBERJACK);
        village.setCitizen(builder, JobType.BUILDER);
        village.setCitizen(second, JobType.LUMBERJACK);
        assertEquals(2, village.jobCount(JobType.LUMBERJACK));
        assertEquals(1, village.jobCount(JobType.BUILDER));
        assertEquals(3, village.citizens().size());

        village.setCitizen(second, JobType.NONE);
        assertFalse(village.citizens().containsKey(second));
        assertEquals(1, village.jobCount(JobType.LUMBERJACK));
        assertEquals(0, village.jobCount(JobType.NONE));

        assertTrue(village.removeCitizen(builder));
        assertFalse(village.removeCitizen(builder));
        assertEquals(0, village.jobCount(JobType.BUILDER));
        assertEquals(JobType.LUMBERJACK, village.citizens().get(lumberjack));
    }
}
