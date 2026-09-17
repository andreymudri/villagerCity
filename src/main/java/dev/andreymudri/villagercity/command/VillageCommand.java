package dev.andreymudri.villagercity.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.CitizenRuntime;
import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.TaskScheduler;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import dev.andreymudri.villagercity.village.plot.PlotPlanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /villagercity village — prints the nearest village's state.
 * /villagercity show [seconds|off] — outlines its areas in dust.
 * /villagercity stock [houses] — fills its storehouse with starter house materials, for testing.
 */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class VillageCommand {
    public static final int SEARCH_DISTANCE = 256;
    /** Houses' worth of materials {@code /villagercity stock} adds when no count is given. */
    public static final int DEFAULT_STOCK_HOUSES = 10;
    public static final int MAX_STOCK_HOUSES = 100;

    private VillageCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("villagercity")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("village").executes(context -> show(context.getSource())))
                .then(Commands.literal("show")
                        .executes(context -> show(context.getSource(), VillageOutline.DEFAULT_SECONDS))
                        .then(Commands.literal("off").executes(context -> hide(context.getSource())))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, VillageOutline.MAX_SECONDS))
                                .executes(context -> show(context.getSource(), IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("stock")
                        .executes(context -> stock(context.getSource(), DEFAULT_STOCK_HOUSES))
                        .then(Commands.argument("houses", IntegerArgumentType.integer(1, MAX_STOCK_HOUSES))
                                .executes(context -> stock(context.getSource(), IntegerArgumentType.getInteger(context, "houses"))))));
    }

    /**
     * Draws the nearest village's areas as coloured dust for the player who asked: white the village itself, orange the
     * ground the builder searches for a plot, blue a plot in progress, green a finished house.
     */
    private static int show(CommandSourceStack source, int seconds) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        VillageData village = VillageRegistry.get(level).nearest(BlockPos.containing(source.getPosition()), SEARCH_DISTANCE);
        if (village == null) {
            source.sendFailure(Component.literal("No village within " + SEARCH_DISTANCE + " blocks"));
            return 0;
        }
        VillageOutline.show(player, village, seconds);
        int reach = Math.min(village.radius() + PlotPlanner.SEARCH_MARGIN, PlotPlanner.MAX_REACH);
        source.sendSuccess(() -> Component.literal("Showing " + village.center().toShortString() + " for " + seconds
                + "s: white village (radius " + village.radius() + "), orange plot search (" + reach
                + "), blue plots, green houses"), false);
        return 1;
    }

    /** Stops the caller's outline. */
    private static int hide(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!VillageOutline.hide(player)) {
            source.sendFailure(Component.literal("You are not showing a village"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Outline hidden"), false);
        return 1;
    }

    /**
     * Fills the nearest village's storehouse with the materials for {@code houses} starter houses. Nobody is credited for
     * them, exactly as if they had always been stored.
     */
    public static int stock(CommandSourceStack source, int houses) {
        ServerLevel level = source.getLevel();
        VillageData village = VillageRegistry.get(level).nearest(BlockPos.containing(source.getPosition()), SEARCH_DISTANCE);
        if (village == null) {
            source.sendFailure(Component.literal("No village within " + SEARCH_DISTANCE + " blocks"));
            return 0;
        }
        BlockPos storehouse = village.storehousePos();
        if (storehouse == null || !level.isLoaded(storehouse)
                || !(level.getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity)) {
            source.sendFailure(Component.literal("That village has no loaded storehouse"));
            return 0;
        }
        Optional<Blueprint> blueprint = Blueprints.load(level, Blueprints.STARTER_HOUSE);
        if (blueprint.isEmpty()) {
            source.sendFailure(Component.literal("The starter house blueprint could not be read"));
            return 0;
        }
        Map<Item, Integer> materials = blueprint.get().requiredMaterials();
        for (Map.Entry<Item, Integer> material : materials.entrySet()) {
            entity.insert(new ItemStack(material.getKey()), (long) material.getValue() * houses);
        }
        String added = materials.entrySet().stream()
                .map(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).getPath() + " x" + (long) entry.getValue() * houses)
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Stocked " + storehouse.toShortString() + " for " + houses
                + " house(s): " + added), false);
        return 1;
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
        for (Plot plot : village.plots()) {
            lines.add(plotText(level, plot));
        }
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
                lines.add("citizen " + villager.getUUID() + " job=" + member.getValue().getSerializedName() + " task=" + taskText(level, village, villager, runtime)
                        + "  [standing at " + villager.blockPosition().toShortString() + "]");
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
    private static String taskText(ServerLevel level, VillageData village, Villager villager, CitizenRuntime runtime) {
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
        CitizenData citizen = villager.getExistingData(CitizenAttachments.CITIZEN.get()).orElse(null);
        if (task != null && citizen != null) {
            return task.describe(new TaskContext(level, villager, village, citizen));
        }
        Job job = runtime.activeJob();
        String waiting = job == null ? null : job.waitingFor();
        return waiting == null ? "idle" : "idle (waiting for " + waiting + ")";
    }

    /** Where the plot is, who builds it, how many blueprint blocks are left, and a released plot's abandons and retry time. */
    private static String plotText(ServerLevel level, Plot plot) {
        StringBuilder line = new StringBuilder("plot " + plot.origin().toShortString() + " " + plot.blueprint());
        Blueprints.load(level, ResourceLocation.parse(plot.blueprint())).ifPresent(blueprint -> {
            long left = blueprint.placements().stream().filter(placement -> !BuilderJob.isDone(level, plot, placement)).count();
            line.append(": ").append(left).append('/').append(blueprint.placements().size()).append(" blocks left");
        });
        if (plot.released()) {
            long wait = Math.max(0, plot.retryAt() - level.getGameTime()) / 20;
            line.append(", released").append(wait > 0 ? " (retry in " + wait + "s)" : "");
        } else {
            line.append(", builder ").append(plot.builder());
        }
        if (plot.abandons() > 0) {
            line.append(", abandoned ").append(plot.abandons()).append('/').append(BuilderJob.MAX_ABANDONS);
        }
        return line.toString();
    }

    private static String format(Map<Item, Long> counts) {
        if (counts.isEmpty()) {
            return "empty";
        }
        return counts.entrySet().stream()
                .map(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).getPath() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }
}
