package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.TaskScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Stops a citizen's current task whenever the villager leaves the level, for any reason, so the task can undo what it
 * left in the world (a door it opened). Drops the citizen from its village roster, and releases the plots it was
 * building, only when the villager is destroyed (killed, discarded, converted) or changes dimension, not when it
 * unloads.
 */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class CitizenRoster {
    private CitizenRoster() {
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        TaskScheduler.stopCurrentTask(level, villager);
        Entity.RemovalReason reason = villager.getRemovalReason();
        if (reason == null || !(reason.shouldDestroy() || reason == Entity.RemovalReason.CHANGED_DIMENSION)) {
            return;
        }
        villager.getExistingData(CitizenAttachments.CITIZEN.get()).ifPresent(data -> {
            if (data.villageId() == null) {
                return;
            }
            VillageRegistry registry = VillageRegistry.get(level);
            VillageData village = registry.get(data.villageId());
            if (village == null) {
                return;
            }
            boolean removed = village.removeCitizen(villager.getUUID());
            boolean released = village.releasePlotsBuiltBy(villager.getUUID());
            if (removed || released) {
                registry.setDirty();
            }
        });
    }
}
