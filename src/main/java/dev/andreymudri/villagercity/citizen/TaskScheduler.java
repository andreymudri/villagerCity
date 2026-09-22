package dev.andreymudri.villagercity.citizen;

import com.google.common.collect.ImmutableList;
import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.task.MoveTo;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
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
            VillageData currentVillage = citizen.villageId() != null ? VillageRegistry.get(level).get(citizen.villageId()) : null;
            release(level, villager, citizen, currentVillage, runtime);
            return;
        }
        VillageData village = VillageRegistry.get(level).get(citizen.villageId());
        if (village == null) {
            release(level, villager, citizen, null, runtime);
            citizen.clear();
            return;
        }
        brain.addActivity(cityTask, ImmutableList.of());
        if (shouldYield(level, villager)) {
            if (brain.isActive(cityTask)) {
                brain.setActiveActivityIfPossible(scheduledActivity(level, villager));
            }
            // Nothing drives the current task while vanilla has the villager, so no door it opened would be closed.
            MoveTo.closeDoorsOpenedBy(level, villager);
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

    /**
     * Stops the villager's current task, if it has one, as the villager leaves the level: the task will never be ticked
     * again, so it must undo now whatever it left in the world. Does nothing for a villager that is not a citizen.
     */
    public static void stopCurrentTask(ServerLevel level, Villager villager) {
        Optional<CitizenRuntime> runtime = villager.getExistingData(CitizenAttachments.RUNTIME.get());
        Optional<CitizenData> citizen = villager.getExistingData(CitizenAttachments.CITIZEN.get());
        if (runtime.isEmpty() || citizen.isEmpty() || runtime.get().current == null) {
            return;
        }
        Task current = runtime.get().current;
        runtime.get().current = null;
        VillageData village = citizen.get().villageId() != null ? VillageRegistry.get(level).get(citizen.get().villageId()) : null;
        current.stop(new TaskContext(level, villager, village, citizen.get()));
    }

    private static void release(ServerLevel level, Villager villager, CitizenData citizen, @Nullable VillageData village, CitizenRuntime runtime) {
        if (runtime.current != null) {
            Task current = runtime.current;
            runtime.current = null;
            current.stop(new TaskContext(level, villager, village, citizen));
        }
        runtime.job = null;
        runtime.jobType = null;
        Activity cityTask = CitizenAttachments.CITY_TASK.get();
        if (villager.getBrain().isActive(cityTask)) {
            villager.getBrain().setActiveActivityIfPossible(scheduledActivity(level, villager));
        }
    }
}
