package dev.andreymudri.villagercity.citizen;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/** The permission checks vanilla/NeoForge mob griefing honours, applied to citizen and village block changes. */
public final class WorldPermissions {
    private WorldPermissions() {
    }

    /** doMobGriefing (via EntityMobGriefingEvent), Block#canEntityDestroy, and LivingDestroyBlockEvent. */
    public static boolean mayBreak(ServerLevel level, LivingEntity actor, BlockPos pos) {
        return CommonHooks.canEntityDestroy(level, pos, actor);
    }

    /** doMobGriefing (or EntityMobGriefingEvent when an actor is known) before placing anything. */
    public static boolean mayGrief(ServerLevel level, @Nullable Entity actor) {
        return EventHooks.canEntityGrief(level, actor);
    }

    /** Take before setBlock; after placing, call {@link #placementCancelled} and restore the snapshot when it returns true. */
    public static BlockSnapshot snapshot(ServerLevel level, BlockPos pos) {
        return BlockSnapshot.create(level.dimension(), level, pos);
    }

    /** Fires EntityPlaceEvent the way vanilla item placement does: after the block is set. */
    public static boolean placementCancelled(@Nullable Entity actor, BlockSnapshot snapshot) {
        return EventHooks.onBlockPlace(actor, snapshot, Direction.UP);
    }
}
