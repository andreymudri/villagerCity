package dev.andreymudri.villagercity.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** One block of a blueprint, relative to the plot origin. */
public record BlueprintPlacement(BlockPos offset, BlockState state) {
    public BlueprintPlacement {
        offset = offset.immutable();
    }
}
