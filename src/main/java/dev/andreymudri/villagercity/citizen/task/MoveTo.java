package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Walks until within reach of the target block's center. Fails after 200 ticks without getting closer. */
public final class MoveTo implements Task {
    public static final double SPEED = 0.6;
    public static final int STUCK_TICKS = 200;
    public static final int REPATH_TICKS = 20;

    private final BlockPos target;
    private final double reach;
    private double bestDistanceSqr;
    private long lastProgressTick;
    private long lastPathTick;

    public MoveTo(BlockPos target, double reach) {
        this.target = target.immutable();
        this.reach = reach;
    }

    @Override
    public void start(TaskContext ctx) {
        bestDistanceSqr = Double.MAX_VALUE;
        lastProgressTick = ctx.gameTime();
        lastPathTick = ctx.gameTime() - REPATH_TICKS;
    }

    @Override
    public Status tick(TaskContext ctx) {
        Villager villager = ctx.villager();
        PathNavigation navigation = villager.getNavigation();
        Vec3 center = Vec3.atCenterOf(target);
        double distanceSqr = villager.distanceToSqr(center);
        if (distanceSqr <= reach * reach) {
            navigation.stop();
            return Status.SUCCESS;
        }
        long now = ctx.gameTime();
        if (distanceSqr < bestDistanceSqr - 0.25) {
            bestDistanceSqr = distanceSqr;
            lastProgressTick = now;
        } else if (now - lastProgressTick > STUCK_TICKS) {
            navigation.stop();
            return Status.FAILED;
        }
        boolean ours = target.equals(navigation.getTargetPos());
        if ((navigation.isDone() || !ours) && now - lastPathTick >= (ours ? REPATH_TICKS : 0)) {
            Path path = navigation.createPath(target, Math.max(0, (int) Math.floor(reach)));
            if (path != null) {
                navigation.moveTo(path, SPEED);
            }
            lastPathTick = now;
        }
        villager.getLookControl().setLookAt(center);
        return Status.RUNNING;
    }

    public BlockPos target() {
        return target;
    }
}
