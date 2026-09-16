package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** Drops a citizen from its village roster when the villager is destroyed (killed, discarded, converted), not when it unloads. */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class CitizenRoster {
    private CitizenRoster() {
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity.RemovalReason reason = villager.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) {
            return;
        }
        villager.getExistingData(CitizenAttachments.CITIZEN.get()).ifPresent(data -> {
            if (data.villageId() == null) {
                return;
            }
            VillageRegistry registry = VillageRegistry.get(level);
            VillageData village = registry.get(data.villageId());
            if (village != null && village.removeCitizen(villager.getUUID())) {
                registry.setDirty();
            }
        });
    }
}
