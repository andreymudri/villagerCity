package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.village.VillageData;
import javax.annotation.Nullable;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

public final class CitizenTestSupport {
    private CitizenTestSupport() {
    }

    /** Makes the villager a citizen of the village; when a job is given it runs instead of the registered one. */
    public static CitizenData enroll(Villager villager, VillageData village, JobType type, ItemStack tool, @Nullable Job forced) {
        CitizenData data = new CitizenData(village.id(), type, tool);
        villager.setData(CitizenAttachments.CITIZEN, data);
        villager.getData(CitizenAttachments.RUNTIME).forceJob(forced);
        return data;
    }
}
