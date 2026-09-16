package dev.andreymudri.villagercity.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

/** Finds vanilla villages: a bell (meeting POI) with at least one villager within 64 blocks. */
public final class VillageDetector {
    public static final int PLAYER_SCAN_RADIUS = 128;
    public static final int VILLAGER_RADIUS = 64;

    private VillageDetector() {
    }

    public static void scanAroundPlayers(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            scan(level, player.blockPosition(), PLAYER_SCAN_RADIUS);
        }
    }

    /** Registers every unregistered qualifying bell within the radius; returns the villages created by this call. */
    public static List<VillageData> scan(ServerLevel level, BlockPos around, int radius) {
        VillageRegistry registry = VillageRegistry.get(level);
        List<BlockPos> bells = level.getPoiManager()
                .findAll(type -> type.is(PoiTypes.MEETING), pos -> true, around, radius, PoiManager.Occupancy.ANY)
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(around)))
                .toList();
        List<VillageData> created = new ArrayList<>();
        for (BlockPos bell : bells) {
            if (!level.isLoaded(bell) || registry.nearest(bell, VillageRegistry.MERGE_DISTANCE) != null) {
                continue;
            }
            if (level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(VILLAGER_RADIUS)).isEmpty()) {
                continue;
            }
            VillageData village = registry.register(bell);
            adoptHomes(level, village);
            created.add(village);
        }
        return created;
    }

    /** Every bed (home POI) inside the village radius becomes a vanilla house record. */
    public static void adoptHomes(ServerLevel level, VillageData village) {
        level.getPoiManager()
                .findAll(type -> type.is(PoiTypes.HOME), village::contains, village.center(), village.radius(), PoiManager.Occupancy.ANY)
                .forEach(home -> village.addHouse(new BuildingRecord(BuildingRecord.VANILLA_HOME, home, new Vec3i(1, 1, 1))));
        VillageRegistry.get(level).setDirty();
    }
}
