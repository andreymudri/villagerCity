package dev.andreymudri.villagercity.citizen;

import com.google.common.collect.ImmutableList;
import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = VillagerCity.MODID)
public final class TaskScheduler {
    public static final Set<Activity> YIELD_ACTIVITIES = Set.of(Activity.PANIC, Activity.REST, Activity.RAID, Activity.PRE_RAID, Activity.HIDE);
    public static final int IDLE_RETRY_TICKS = 40;

    private TaskScheduler() {
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        Optional<CitizenData> citizen = villager.getExistingData(CitizenAttachments.CITIZEN.get());
        if (citizen.isPresent()) {
            tickCitizen(level, villager, citizen.get());
        }
    }

    public static void tickCitizen(ServerLevel level, Villager villager, CitizenData citizen) {
        CitizenRuntime runtime = villager.getData(CitizenAttachments.RUNTIME);
        Brain<Villager> brain = villager.getBrain();
        Activity cityTask = CitizenAttachments.CITY_TASK.get();
        if (citizen.job() == JobType.NONE || citizen.villageId() == null) {
            release(level, villager, runtime);
            return;
        }
        VillageData village = VillageRegistry.get(level).get(citizen.villageId());
        if (village == null) {
            citizen.clear();
            release(level, villager, runtime);
            return;
        }
        brain.addActivity(cityTask, ImmutableList.of());
        if (shouldYield(level, villager)) {
            if (brain.isActive(cityTask)) {
                brain.setActiveActivityIfPossible(scheduledActivity(level, villager));
            }
            return;
        }
        if (!brain.isActive(cityTask)) {
            brain.setActiveActivityIfPossible(cityTask);
        }
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        equipTool(villager, citizen);

        TaskContext ctx = new TaskContext(level, villager, village, citizen);
        if (runtime.forcedJob == null && runtime.jobType != citizen.job()) {
            if (runtime.current != null) {
                runtime.current.stop(ctx);
                runtime.current = null;
            }
            runtime.job = Jobs.create(citizen.job());
            runtime.jobType = citizen.job();
        }
        Job job = runtime.activeJob();
        if (job == null) {
            return;
        }
        if (runtime.current == null) {
            if (level.getGameTime() < runtime.idleUntil) {
                return;
            }
            Task next = job.plan(ctx);
            if (next == null) {
                runtime.idleUntil = level.getGameTime() + IDLE_RETRY_TICKS;
                return;
            }
            runtime.current = next;
            next.start(ctx);
        }
        Task.Status status = runtime.current.tick(ctx);
        if (status != Task.Status.RUNNING) {
            Task finished = runtime.current;
            runtime.current = null;
            finished.stop(ctx);
            job.onTaskFinished(ctx, finished, status);
        }
    }

    public static boolean shouldYield(ServerLevel level, Villager villager) {
        Brain<Villager> brain = villager.getBrain();
        return villager.isTrading()
                || villager.isSleeping()
                || YIELD_ACTIVITIES.stream().anyMatch(brain::isActive)
                || scheduledActivity(level, villager) == Activity.REST;
    }

    public static Activity scheduledActivity(ServerLevel level, Villager villager) {
        return villager.getBrain().getSchedule().getActivityAt((int) (level.getDayTime() % 24000L));
    }

    private static void equipTool(Villager villager, CitizenData citizen) {
        if (!citizen.tool().isEmpty() && villager.getMainHandItem() != citizen.tool()) {
            villager.setItemSlot(EquipmentSlot.MAINHAND, citizen.tool());
        }
    }

    private static void release(ServerLevel level, Villager villager, CitizenRuntime runtime) {
        runtime.current = null;
        runtime.job = null;
        runtime.jobType = null;
        Activity cityTask = CitizenAttachments.CITY_TASK.get();
        if (villager.getBrain().isActive(cityTask)) {
            villager.getBrain().setActiveActivityIfPossible(scheduledActivity(level, villager));
        }
    }
}
