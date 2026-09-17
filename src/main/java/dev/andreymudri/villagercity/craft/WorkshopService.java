package dev.andreymudri.villagercity.craft;

import dev.andreymudri.villagercity.village.VillageData;
import net.minecraft.server.level.ServerLevel;

/** Keeps the artisan's workshop, a crafting table and a furnace, in place for the village. */
public final class WorkshopService {
    private WorkshopService() {
    }

    /** Called on every village tick right after the storehouse check. Empty for now: Task 7 implements it. */
    public static void ensureWorkshop(ServerLevel level, VillageData village) {
    }
}
