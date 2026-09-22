package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.village.VillageWorks.StreetCell;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
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
    private final Map<UUID, Long> storehouseDebt;
    private final Set<BlockPos> felling;
    private final Set<BlockPos> failedPlots;
    private final Set<BlockPos> pathCells;
    /** The x and z of each of {@link #pathCells}, packed with {@code BlockPos.asLong(x, 0, z)}, for {@link #isPathColumn}. */
    private final Set<Long> pathColumns;
    private final List<BlockPos> pathQueue;
    /** The street graph, in the order its cells were laid; {@link #streetEnds} depends on that order. */
    private final List<StreetCell> streets;
    private final Set<BlockPos> openedDoors;
    private @Nullable BlockPos craftingTablePos;
    private boolean managed;
    /** Game time before which no builder or storehouse placement searches this village for a spot again; not saved. */
    private long nextPlotSearch;
    private long nextStorehouseSearch;
    private final Map<BlockPos, Long> unreachablePlots = new HashMap<>();
    /** The last recorded number of spots in the village dark enough for monsters to spawn; not saved. */
    private int darkSpotCount;
    /** The last recorded orders for the artisan, shown by the village command; not saved. */
    private List<String> artisanOrders = List.of();

    public VillageData(UUID id, BlockPos center, int radius) {
        this(id, center, radius, VillageAge.DARK, null, List.of(), List.of(), new ContributionLedger(), Map.of(), Map.of(), List.of(), List.of(), VillageWorks.EMPTY, List.of(), true);
    }

    public VillageData(UUID id, BlockPos center, int radius, VillageAge age, @Nullable BlockPos storehousePos,
                       List<BuildingRecord> houses, List<Plot> plots, ContributionLedger ledger, Map<UUID, JobType> citizens,
                       Map<UUID, Long> storehouseDebt, List<BlockPos> felling, List<BlockPos> failedPlots, VillageWorks works,
                       List<BlockPos> openedDoors, boolean managed) {
        this.id = id;
        this.center = center.immutable();
        this.radius = radius;
        this.age = age;
        this.storehousePos = storehousePos == null ? null : storehousePos.immutable();
        this.houses = new ArrayList<>(houses);
        this.plots = new ArrayList<>(plots);
        this.ledger = ledger;
        this.citizens = new LinkedHashMap<>(citizens);
        this.storehouseDebt = new LinkedHashMap<>(storehouseDebt);
        this.felling = new LinkedHashSet<>();
        felling.forEach(pos -> this.felling.add(pos.immutable()));
        this.failedPlots = new LinkedHashSet<>();
        failedPlots.forEach(pos -> this.failedPlots.add(pos.immutable()));
        this.pathCells = new LinkedHashSet<>();
        this.pathColumns = new HashSet<>();
        works.pathCells().forEach(this::addPathCell);
        this.pathQueue = new ArrayList<>();
        works.pathQueue().forEach(this::queuePath);
        this.streets = new ArrayList<>();
        works.streets().forEach(cell -> addStreetCell(cell.pos(), cell.hops()));
        this.openedDoors = new LinkedHashSet<>();
        openedDoors.forEach(this::recordOpenedDoor);
        setCraftingTablePos(works.craftingTable().orElse(null));
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

    /** Employed citizens by villager UUID. Survives unloads; entries leave only when the villager is destroyed, changes dimension or is dismissed. */
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

    /** Items each player has taken out of the storehouse and not yet put back; deposits repay this before earning credit. */
    public Map<UUID, Long> storehouseDebt() {
        return Collections.unmodifiableMap(storehouseDebt);
    }

    public long debt(UUID player) {
        return storehouseDebt.getOrDefault(player, 0L);
    }

    public void setDebt(UUID player, long debt) {
        if (debt <= 0) {
            storehouseDebt.remove(player);
        } else {
            storehouseDebt.put(player, debt);
        }
    }

    /** Bases of trees a lumberjack started felling; the finder accepts them without their canopy, which goes first. */
    public List<BlockPos> felling() {
        return List.copyOf(felling);
    }

    public boolean isFelling(BlockPos base) {
        return felling.contains(base);
    }

    public boolean startFelling(BlockPos base) {
        return felling.add(base.immutable());
    }

    public boolean stopFelling(BlockPos base) {
        return felling.remove(base);
    }

    /** Origins of plots dropped after {@code BuilderJob.MAX_ABANDONS}; the builder never picks them again. */
    public List<BlockPos> failedPlots() {
        return List.copyOf(failedPlots);
    }

    public boolean isFailedPlot(BlockPos origin) {
        return failedPlots.contains(origin);
    }

    public void markPlotFailed(BlockPos origin) {
        failedPlots.add(origin.immutable());
    }

    /** Remembers, until the given game time and without saving, a plot origin a builder could not path to. */
    public void markPlotUnreachable(BlockPos origin, long until) {
        unreachablePlots.put(origin.immutable(), until);
    }

    public boolean isPlotUnreachable(BlockPos origin, long gameTime) {
        unreachablePlots.values().removeIf(until -> until <= gameTime);
        return unreachablePlots.containsKey(origin);
    }

    public long nextPlotSearch() {
        return nextPlotSearch;
    }

    public void setNextPlotSearch(long gameTime) {
        this.nextPlotSearch = gameTime;
    }

    public long nextStorehouseSearch() {
        return nextStorehouseSearch;
    }

    public void setNextStorehouseSearch(long gameTime) {
        this.nextStorehouseSearch = gameTime;
    }

    /** Cells a villager walks on along the laid paths: the air cell above each path block. */
    public List<BlockPos> pathCells() {
        return List.copyOf(pathCells);
    }

    /** True when any laid path cell has this x and z, whatever its height. */
    public boolean isPathColumn(int x, int z) {
        return pathColumns.contains(BlockPos.asLong(x, 0, z));
    }

    /** Records a laid path cell: the air cell above the path block, where a villager walks. */
    public void addPathCell(BlockPos surface) {
        BlockPos cell = surface.immutable();
        pathCells.add(cell);
        pathColumns.add(BlockPos.asLong(cell.getX(), 0, cell.getZ()));
    }

    /** The street graph: every laid street cell with its hop count, in the order they were laid. */
    public List<StreetCell> streets() {
        return List.copyOf(streets);
    }

    /**
     * Records a laid street cell with its hop count, and records it as a path cell too. A cell already in the graph
     * keeps the hop count it was first recorded with.
     */
    public void addStreetCell(BlockPos pos, int hops) {
        BlockPos cell = pos.immutable();
        addPathCell(cell);
        if (streets.stream().noneMatch(street -> street.pos().equals(cell))) {
            streets.add(new StreetCell(cell, hops));
        }
    }

    /**
     * The frontier of the street graph: the cells no other cell grew from. A cell grew from another when it was laid
     * after it, at a hop count no lower, one block or less away along x, y and z (diagonals included). The last cell of
     * a run is therefore an end until a later run starts beside it, and every cell before it in its run is not.
     */
    public List<StreetCell> streetEnds() {
        List<StreetCell> ends = new ArrayList<>();
        for (int i = 0; i < streets.size(); i++) {
            StreetCell cell = streets.get(i);
            boolean grownFrom = false;
            for (int j = i + 1; j < streets.size() && !grownFrom; j++) {
                StreetCell later = streets.get(j);
                grownFrom = later.hops() >= cell.hops() && isNeighbour(cell.pos(), later.pos());
            }
            if (!grownFrom) {
                ends.add(cell);
            }
        }
        return ends;
    }

    private static boolean isNeighbour(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getY() - b.getY()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    /** The highest hop count in the street graph, or 0 when it has no cells. */
    public int deepestHops() {
        return streets.stream().mapToInt(StreetCell::hops).max().orElse(0);
    }

    /** Doors the paver opened and has not closed yet. Saved, so a door opened before a restart is still known after it. */
    public List<BlockPos> openedDoors() {
        return List.copyOf(openedDoors);
    }

    public void recordOpenedDoor(BlockPos door) {
        openedDoors.add(door.immutable());
    }

    /** Forgets an opened door, once it is closed. Returns whether it was recorded. */
    public boolean clearOpenedDoor(BlockPos door) {
        return openedDoors.remove(door);
    }

    /** Origins of houses still waiting for a path to the bell, oldest first. */
    public List<BlockPos> pathQueue() {
        return List.copyOf(pathQueue);
    }

    public void queuePath(BlockPos houseOrigin) {
        BlockPos origin = houseOrigin.immutable();
        if (!pathQueue.contains(origin)) {
            pathQueue.add(origin);
        }
    }

    public boolean removeQueuedPath(BlockPos houseOrigin) {
        return pathQueue.remove(houseOrigin);
    }

    public @Nullable BlockPos craftingTablePos() {
        return craftingTablePos;
    }

    public void setCraftingTablePos(@Nullable BlockPos pos) {
        this.craftingTablePos = pos == null ? null : pos.immutable();
    }

    /** The saved form of the paths, the path queue, the street graph and the workshop blocks. */
    public VillageWorks works() {
        return new VillageWorks(pathCells(), pathQueue(), streets(), Optional.ofNullable(craftingTablePos));
    }

    public int darkSpotCount() {
        return darkSpotCount;
    }

    public void setDarkSpotCount(int darkSpotCount) {
        this.darkSpotCount = darkSpotCount;
    }

    public List<String> artisanOrders() {
        return artisanOrders;
    }

    public void setArtisanOrders(List<String> orders) {
        this.artisanOrders = List.copyOf(orders);
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

    /** Records a finished building. Houses stand on streets, so none queues a path to the bell. */
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

    /** The plot this builder holds; released plots belong to nobody. */
    public Optional<Plot> plotBuiltBy(UUID builder) {
        return plots.stream().filter(plot -> plot.builder() != null && plot.builder().equals(builder)).findFirst();
    }

    /** Takes the plot away from its builder; another builder may take it over from game time {@code retryAt}. */
    public void releasePlot(UUID plotId, long retryAt, boolean abandoned) {
        replacePlot(plotId, plot -> new Plot(plot.id(), plot.blueprint(), plot.origin(), plot.size(), null, retryAt,
                abandoned ? plot.abandons() + 1 : plot.abandons(), plot.prepared()));
    }

    /** Releases every plot held by a builder that left, ready for takeover at once; leaving does not count as an abandon. */
    public boolean releasePlotsBuiltBy(UUID builder) {
        boolean changed = false;
        for (int i = 0; i < plots.size(); i++) {
            Plot plot = plots.get(i);
            if (builder.equals(plot.builder())) {
                plots.set(i, new Plot(plot.id(), plot.blueprint(), plot.origin(), plot.size(), null, 0L, plot.abandons(), plot.prepared()));
                changed = true;
            }
        }
        return changed;
    }

    public void assignPlot(UUID plotId, UUID builder) {
        replacePlot(plotId, plot -> new Plot(plot.id(), plot.blueprint(), plot.origin(), plot.size(), builder, plot.retryAt(), plot.abandons(), plot.prepared()));
    }

    /** Marks the plot's ground as levelled, so its builder may start. */
    public void markPlotPrepared(UUID plotId) {
        replacePlot(plotId, plot -> new Plot(plot.id(), plot.blueprint(), plot.origin(), plot.size(), plot.builder(), plot.retryAt(), plot.abandons(), true));
    }

    private void replacePlot(UUID plotId, UnaryOperator<Plot> change) {
        plots.replaceAll(plot -> plot.id().equals(plotId) ? change.apply(plot) : plot);
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
        if (craftingTablePos != null) {
            occupied.add(Footprint.of(craftingTablePos, SINGLE_BLOCK));
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
