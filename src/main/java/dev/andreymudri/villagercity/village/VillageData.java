package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.citizen.JobType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * Everything a village knows about itself. Holds no entity or level references, so it can be
 * saved and driven by either future offscreen-simulation mechanism.
 */
public final class VillageData {
    public static final int DEFAULT_RADIUS = 48;
    public static final int RADIUS_PADDING = 8;
    private static final Vec3i SINGLE_BLOCK = new Vec3i(1, 1, 1);

    private final UUID id;
    private final BlockPos center;
    private int radius;
    private VillageAge age;
    private @Nullable BlockPos storehousePos;
    private final List<BuildingRecord> houses;
    private final List<Plot> plots;
    private final ContributionLedger ledger;
    private final Map<UUID, JobType> citizens;
    private boolean managed;

    public VillageData(UUID id, BlockPos center, int radius) {
        this(id, center, radius, VillageAge.DARK, null, List.of(), List.of(), new ContributionLedger(), Map.of(), true);
    }

    public VillageData(UUID id, BlockPos center, int radius, VillageAge age, @Nullable BlockPos storehousePos,
                       List<BuildingRecord> houses, List<Plot> plots, ContributionLedger ledger, Map<UUID, JobType> citizens,
                       boolean managed) {
        this.id = id;
        this.center = center.immutable();
        this.radius = radius;
        this.age = age;
        this.storehousePos = storehousePos == null ? null : storehousePos.immutable();
        this.houses = new ArrayList<>(houses);
        this.plots = new ArrayList<>(plots);
        this.ledger = ledger;
        this.citizens = new LinkedHashMap<>(citizens);
        this.managed = managed;
    }

    public UUID id() {
        return id;
    }

    public BlockPos center() {
        return center;
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public VillageAge age() {
        return age;
    }

    public @Nullable BlockPos storehousePos() {
        return storehousePos;
    }

    public void setStorehousePos(@Nullable BlockPos pos) {
        this.storehousePos = pos == null ? null : pos.immutable();
    }

    public ContributionLedger ledger() {
        return ledger;
    }

    /** Employed citizens by villager UUID. Survives unloads; entries leave only when the villager is destroyed or dismissed. */
    public Map<UUID, JobType> citizens() {
        return Collections.unmodifiableMap(citizens);
    }

    public void setCitizen(UUID villager, JobType job) {
        if (job == JobType.NONE) {
            citizens.remove(villager);
        } else {
            citizens.put(villager, job);
        }
    }

    public boolean removeCitizen(UUID villager) {
        return citizens.remove(villager) != null;
    }

    public int jobCount(JobType job) {
        return (int) citizens.values().stream().filter(job::equals).count();
    }

    /** When false, the village ticker leaves this village alone (used by focused GameTests). */
    public boolean managed() {
        return managed;
    }

    public void setManaged(boolean managed) {
        this.managed = managed;
    }

    public List<BuildingRecord> houses() {
        return Collections.unmodifiableList(houses);
    }

    public int houseCount() {
        return houses.size();
    }

    public void addHouse(BuildingRecord house) {
        houses.add(house);
        radius = Math.max(radius, farthestCorner(house.footprint()) + RADIUS_PADDING);
    }

    public List<Plot> plots() {
        return Collections.unmodifiableList(plots);
    }

    public void addPlot(Plot plot) {
        plots.add(plot);
    }

    public boolean removePlot(UUID plotId) {
        return plots.removeIf(plot -> plot.id().equals(plotId));
    }

    public Optional<Plot> plotBuiltBy(UUID builder) {
        return plots.stream().filter(plot -> plot.builder().equals(builder)).findFirst();
    }

    /** Horizontal (cylindrical) membership test. */
    public boolean contains(BlockPos pos) {
        long dx = pos.getX() - center.getX();
        long dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    public List<Footprint> occupiedFootprints() {
        List<Footprint> occupied = new ArrayList<>();
        occupied.add(Footprint.of(center, SINGLE_BLOCK));
        if (storehousePos != null) {
            occupied.add(Footprint.of(storehousePos, SINGLE_BLOCK));
        }
        houses.forEach(house -> occupied.add(house.footprint()));
        plots.forEach(plot -> occupied.add(plot.footprint()));
        return occupied;
    }

    private int farthestCorner(Footprint footprint) {
        int farthest = 0;
        for (int x : new int[] {footprint.minX(), footprint.maxX()}) {
            for (int z : new int[] {footprint.minZ(), footprint.maxZ()}) {
                long dx = x - center.getX();
                long dz = z - center.getZ();
                farthest = Math.max(farthest, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz)));
            }
        }
        return farthest;
    }
}
