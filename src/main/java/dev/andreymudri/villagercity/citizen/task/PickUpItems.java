package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

/** Walks to and collects matching item entities near a point until none are left or the task has run 200 ticks. */
public final class PickUpItems implements Task {
    public static final int TIMEOUT_TICKS = 200;
    public static final double GRAB_DISTANCE = 1.5;

    private final BlockPos around;
    private final double radius;
    private final Predicate<ItemStack> filter;
    private int ticksRun;
    private int lastPathTick;

    public PickUpItems(BlockPos around, double radius, Predicate<ItemStack> filter) {
        this.around = around.immutable();
        this.radius = radius;
        this.filter = filter;
    }

    @Override
    public void start(TaskContext ctx) {
        ticksRun = 0;
        lastPathTick = -MoveTo.REPATH_TICKS;
    }

    @Override
    public Status tick(TaskContext ctx) {
        ticksRun++;
        if (ticksRun > TIMEOUT_TICKS) {
            return Status.SUCCESS;
        }
        Villager villager = ctx.villager();
        List<ItemEntity> items = ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(radius),
                item -> item.isAlive() && filter.test(item.getItem()) && villager.getInventory().canAddItem(item.getItem()));
        if (items.isEmpty()) {
            return Status.SUCCESS;
        }
        ItemEntity nearest = items.stream().min(Comparator.comparingDouble(villager::distanceToSqr)).orElseThrow();
        if (villager.distanceToSqr(nearest) <= GRAB_DISTANCE * GRAB_DISTANCE) {
            ItemStack stack = nearest.getItem();
            int before = stack.getCount();
            ItemStack rest = villager.getInventory().addItem(stack);
            villager.take(nearest, before - rest.getCount());
            if (rest.isEmpty()) {
                nearest.discard();
            } else {
                nearest.setItem(rest);
            }
            return Status.RUNNING;
        }
        BlockPos itemBlock = nearest.blockPosition();
        PathNavigation navigation = villager.getNavigation();
        boolean ours = itemBlock.equals(navigation.getTargetPos());
        if (navigation.isDone() || !ours || ticksRun - lastPathTick >= MoveTo.REPATH_TICKS) {
            Path path = navigation.createPath(itemBlock, 0);
            if (path != null) {
                navigation.moveTo(path, MoveTo.SPEED);
            }
            lastPathTick = ticksRun;
        }
        return Status.RUNNING;
    }
}
