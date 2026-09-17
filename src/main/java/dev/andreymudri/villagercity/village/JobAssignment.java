package dev.andreymudri.villagercity.village;

import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Keeps one citizen in each of {@link #SLICE_JOBS}, hired in that order: lumberjack, builder, artisan, paver and
 * lamplighter. Filled jobs come from the village's saved citizen roster, so a worker away from the bell still counts;
 * candidates come from adult villagers with no vanilla profession inside the village area, sorted by UUID.
 */
public final class JobAssignment {
    public static final List<JobType> SLICE_JOBS = List.of(JobType.LUMBERJACK, JobType.BUILDER, JobType.ARTISAN, JobType.PAVER, JobType.LAMPLIGHTER);
    public static final int VERTICAL_RANGE = 32;

    private JobAssignment() {
    }

    public static void assign(ServerLevel level, VillageData village) {
        AABB area = new AABB(village.center()).inflate(village.radius(), VERTICAL_RANGE, village.radius());
        List<Villager> candidates = new ArrayList<>();
        for (Villager villager : level.getEntitiesOfClass(Villager.class, area, Villager::isAlive)) {
            CitizenData data = villager.getExistingData(CitizenAttachments.CITIZEN.get()).orElse(null);
            if (data != null && data.villageId() != null) {
                if (village.id().equals(data.villageId()) && data.job() != JobType.NONE) {
                    village.setCitizen(villager.getUUID(), data.job());
                }
                if (!village.id().equals(data.villageId()) || data.job() != JobType.NONE) {
                    continue;
                }
            }
            if (!villager.isBaby() && villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                candidates.add(villager);
            }
        }
        candidates.sort(Comparator.comparing(Entity::getUUID));
        Iterator<Villager> next = candidates.iterator();
        for (JobType job : SLICE_JOBS) {
            if (village.jobCount(job) == 0 && next.hasNext()) {
                employ(next.next(), village, job);
            }
        }
    }

    /** Enrolls the villager in the job with its starting tool: a stone axe for the lumberjack, a stone pickaxe for the paver, nothing otherwise. */
    public static void employ(Villager villager, VillageData village, JobType job) {
        ItemStack tool = switch (job) {
            case LUMBERJACK -> new ItemStack(Items.STONE_AXE);
            case PAVER -> new ItemStack(Items.STONE_PICKAXE);
            default -> ItemStack.EMPTY;
        };
        villager.setData(CitizenAttachments.CITIZEN, new CitizenData(village.id(), job, tool));
        village.setCitizen(villager.getUUID(), job);
    }
}
