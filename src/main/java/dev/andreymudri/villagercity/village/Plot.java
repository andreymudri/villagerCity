package dev.andreymudri.villagercity.village;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** A building in progress, claimed by one builder. */
public record Plot(UUID id, String blueprint, BlockPos origin, Vec3i size, UUID builder) {
    public Plot {
        origin = origin.immutable();
    }

    public Footprint footprint() {
        return Footprint.of(origin, size);
    }
}
