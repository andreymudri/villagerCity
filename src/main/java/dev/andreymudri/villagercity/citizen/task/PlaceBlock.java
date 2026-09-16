package dev.andreymudri.villagercity.citizen.task;

import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import dev.andreymudri.villagercity.citizen.WorldPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;

/**
 * Places one block state, consuming its cost item from the villager's inventory. Fails when
 * {@link WorldPermissions#mayGrief} refuses, and undoes the placement, refunding the cost, when
 * EntityPlaceEvent is cancelled (both covered by ProtectionTests).
 */
public final class PlaceBlock implements Task {
    public static final double REACH = 5.0;
    public static final int PLACE_DELAY_TICKS = 4;
    public static final int BLOCKED_TIMEOUT_TICKS = 100;

    private final BlockPos pos;
    private final BlockState state;
    private final Item cost;
    private int waited;
    private int blocked;

    public PlaceBlock(BlockPos pos, BlockState state, Item cost) {
        this.pos = pos.immutable();
        this.state = state;
        this.cost = cost;
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (++waited < PLACE_DELAY_TICKS) {
            return Status.RUNNING;
        }
        ServerLevel level = ctx.level();
        Villager villager = ctx.villager();
        BlockState current = level.getBlockState(pos);
        if (current == state) {
            return Status.SUCCESS;
        }
        if (villager.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return Status.FAILED;
        }
        if (!current.isAir() && !current.canBeReplaced()) {
            return Status.FAILED;
        }
        if (state.blocksMotion() && !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty()) {
            if (villager.getBoundingBox().intersects(new AABB(pos))) {
                double side = villager.getX() < pos.getX() + 0.5 ? -2.0 : 2.0;
                villager.getNavigation().moveTo(pos.getX() + 0.5 + side, pos.getY(), pos.getZ() + 0.5, MoveTo.SPEED);
            }
            return ++blocked > BLOCKED_TIMEOUT_TICKS ? Status.FAILED : Status.RUNNING;
        }
        if (!WorldPermissions.mayGrief(level, villager)) {
            return Status.FAILED;
        }
        if (cost != Items.AIR) {
            ItemStack taken = villager.getInventory().removeItemType(cost, 1);
            if (taken.isEmpty()) {
                return Status.FAILED;
            }
        }
        BlockSnapshot snapshot = WorldPermissions.snapshot(level, pos);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        if (WorldPermissions.placementCancelled(villager, snapshot)) {
            snapshot.restore(Block.UPDATE_CLIENTS);
            if (cost != Items.AIR) {
                villager.getInventory().addItem(new ItemStack(cost));
            }
            return Status.FAILED;
        }
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0f) / 2.0f, sound.getPitch() * 0.8f);
        villager.swing(InteractionHand.MAIN_HAND);
        return Status.SUCCESS;
    }
}
