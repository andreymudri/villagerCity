package dev.andreymudri.villagercity.command;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.village.BuildingRecord;
import dev.andreymudri.villagercity.village.Footprint;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;

/**
 * Draws the village's areas as coloured dust for whoever asked, until their view expires. White is the village itself
 * (its radius around the bell), orange the ground the builder searches for a plot, blue a plot in progress and green a
 * finished house. Every point sits on the surface, so the outline follows the terrain.
 */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class VillageOutline {
    /** How often the dust is redrawn; each particle lives about this long. */
    public static final int REDRAW_TICKS = 20;
    /** One point per this many blocks along an edge, so a large village stays readable and cheap. */
    public static final int SPACING = 2;
    public static final int DEFAULT_SECONDS = 30;
    public static final int MAX_SECONDS = 600;
    /** Past this, dust is not drawn: it would not render for the player anyway. */
    public static final int VIEW_DISTANCE = 96;

    private static final DustParticleOptions VILLAGE = dust(0xFFFFFF);
    private static final DustParticleOptions SEARCH = dust(0xFF9A28);
    private static final DustParticleOptions PLOT = dust(0x3FA9F5);
    private static final DustParticleOptions HOUSE = dust(0x4BD16A);

    /** Who is watching which village, and the game time their view ends; not saved, cleared on stop. */
    private static final Map<UUID, Watch> WATCHES = new LinkedHashMap<>();

    private VillageOutline() {
    }

    private record Watch(UUID villageId, long endsAt) {
    }

    private static DustParticleOptions dust(int rgb) {
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f), 1.0f);
    }

    /** Shows {@code village} to {@code player} for {@code seconds}, replacing any view they had. */
    public static void show(ServerPlayer player, VillageData village, int seconds) {
        WATCHES.put(player.getUUID(), new Watch(village.id(), player.level().getGameTime() + (long) seconds * 20));
    }

    /** Stops {@code player}'s view. Returns whether they had one. */
    public static boolean hide(ServerPlayer player) {
        return WATCHES.remove(player.getUUID()) != null;
    }

    public static boolean isWatching(ServerPlayer player) {
        return WATCHES.containsKey(player.getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % REDRAW_TICKS != 0 || WATCHES.isEmpty()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            Watch watch = WATCHES.get(player.getUUID());
            if (watch == null) {
                continue;
            }
            if (level.getGameTime() >= watch.endsAt()) {
                WATCHES.remove(player.getUUID());
                continue;
            }
            VillageData village = VillageRegistry.get(level).get(watch.villageId());
            if (village == null) {
                WATCHES.remove(player.getUUID());
                continue;
            }
            draw(level, player, village);
        }
    }

    private static void draw(ServerLevel level, ServerPlayer player, VillageData village) {
        BlockPos center = village.center();
        square(level, player, center, village.radius(), VILLAGE);
        square(level, player, center, Math.min(village.radius() + PlotPlanner.SEARCH_MARGIN, PlotPlanner.MAX_REACH), SEARCH);
        for (Plot plot : village.plots()) {
            outline(level, player, plot.footprint(), PLOT);
        }
        for (BuildingRecord house : village.houses()) {
            outline(level, player, house.footprint(), HOUSE);
        }
    }

    /** The square of {@code reach} blocks around the bell, the same Chebyshev shape the plot search uses. */
    private static void square(ServerLevel level, ServerPlayer player, BlockPos center, int reach, DustParticleOptions color) {
        outline(level, player, new Footprint(center.getX() - reach, center.getZ() - reach,
                center.getX() + reach, center.getZ() + reach), color);
    }

    private static void outline(ServerLevel level, ServerPlayer player, Footprint area, DustParticleOptions color) {
        for (int x = area.minX(); x <= area.maxX(); x += SPACING) {
            point(level, player, x, area.minZ(), color);
            point(level, player, x, area.maxZ(), color);
        }
        for (int z = area.minZ(); z <= area.maxZ(); z += SPACING) {
            point(level, player, area.minX(), z, color);
            point(level, player, area.maxX(), z, color);
        }
    }

    /** One dust a block above the surface, skipping columns that are not loaded or are too far to see. */
    private static void point(ServerLevel level, ServerPlayer player, int x, int z, DustParticleOptions color) {
        BlockPos at = new BlockPos(x, player.getBlockY(), z);
        if (!level.isLoaded(at) || player.distanceToSqr(x + 0.5, player.getY(), z + 0.5) > VIEW_DISTANCE * VIEW_DISTANCE) {
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        level.sendParticles(player, color, true, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
    }
}
