package dev.andreymudri.villagercity.command;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenRuntime;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskScheduler;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /villagercity village — prints the nearest village's state. */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class VillageCommand {
    public static final int SEARCH_DISTANCE = 256;

    private VillageCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("villagercity")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("village").executes(context -> show(context.getSource()))));
    }

    private static int show(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        VillageData village = VillageRegistry.get(level).nearest(BlockPos.containing(source.getPosition()), SEARCH_DISTANCE);
        if (village == null) {
            source.sendFailure(Component.literal("No village within " + SEARCH_DISTANCE + " blocks"));
            return 0;
        }
        for (String line : describe(level, village)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    public static List<String> describe(ServerLevel level, VillageData village) {
        List<String> lines = new ArrayList<>();
        lines.add("Village " + village.id());
        lines.add("center: " + village.center().toShortString() + "  radius: " + village.radius() + "  age: " + village.age().getSerializedName());
        lines.add("houses: " + village.houseCount() + "  plots in progress: " + village.plots().size());
        BlockPos storehouse = village.storehousePos();
        if (storehouse == null) {
            lines.add("storehouse: none");
        } else if (level.isLoaded(storehouse) && level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) {
            lines.add("storehouse " + storehouse.toShortString() + ": " + format(entity.counts()));
        } else {
            lines.add("storehouse " + storehouse.toShortString() + ": not loaded");
        }
        // The roster, plus citizens standing near the bell that it does not list yet, so one away at a far plot or at the
        // tree line still shows up.
        Map<UUID, JobType> members = new LinkedHashMap<>(village.citizens());
        AABB area = new AABB(village.center()).inflate(village.radius() + PlotPlanner.SEARCH_MARGIN, 32, village.radius() + PlotPlanner.SEARCH_MARGIN);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, area)) {
            villager.getExistingData(CitizenAttachments.CITIZEN.get())
                    .filter(data -> village.id().equals(data.villageId()))
                    .ifPresent(data -> members.putIfAbsent(villager.getUUID(), data.job()));
        }
        for (Map.Entry<UUID, JobType> member : members.entrySet()) {
            if (level.getEntity(member.getKey()) instanceof Villager villager) {
                CitizenRuntime runtime = villager.getData(CitizenAttachments.RUNTIME);
                lines.add("citizen " + villager.getUUID() + " job=" + member.getValue().getSerializedName() + " task=" + taskText(level, villager, runtime));
            } else {
                lines.add("citizen " + member.getKey() + " job=" + member.getValue().getSerializedName() + " not loaded");
            }
        }
        return lines;
    }

    /**
     * The running task; why city work is paused (trading, sleeping, or the vanilla activity that took over: rest, panic,
     * raid, hide); or idle with what the job waits for.
     */
    private static String taskText(ServerLevel level, Villager villager, CitizenRuntime runtime) {
        if (villager.isTrading()) {
            return "trading";
        }
        if (villager.isSleeping()) {
            return "sleeping";
        }
        if (TaskScheduler.shouldYield(level, villager)) {
            return villager.getBrain().getActiveNonCoreActivity().map(activity -> activity.getName()).orElse("resting");
        }
        Task task = runtime.currentTask();
        if (task != null) {
            return task.getClass().getSimpleName();
        }
        Job job = runtime.activeJob();
        String waiting = job == null ? null : job.waitingFor();
        return waiting == null ? "idle" : "idle (waiting for " + waiting + ")";
    }

    private static String format(Map<Item, Integer> counts) {
        if (counts.isEmpty()) {
            return "empty";
        }
        return counts.entrySet().stream()
                .map(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).getPath() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
