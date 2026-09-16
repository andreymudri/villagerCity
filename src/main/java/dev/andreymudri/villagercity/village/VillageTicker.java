package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.storehouse.StorehouseService;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Every 100 game ticks: detect villages around players, then keep each managed, loaded village supplied and staffed. */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class VillageTicker {
    public static final int INTERVAL_TICKS = 100;

    private VillageTicker() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        VillageDetector.scanAroundPlayers(level);
        VillageRegistry registry = VillageRegistry.get(level);
        for (VillageData village : List.copyOf(registry.all())) {
            if (village.managed()) {
                tickVillage(level, village);
            }
        }
        registry.setDirty();
    }

    public static void tickVillage(ServerLevel level, VillageData village) {
        if (!level.isLoaded(village.center())) {
            return;
        }
        StorehouseService.ensureStorehouse(level, village);
        JobAssignment.assign(level, village);
    }
}
