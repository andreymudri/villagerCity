package dev.andreymudri.villagercity.village;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * A building in progress. A released plot has no builder and may be taken over from game time {@code retryAt}. An
 * unprepared plot still needs its ground levelled by the paver.
 */
public record Plot(UUID id, String blueprint, BlockPos origin, Vec3i size, @Nullable UUID builder, long retryAt, int abandons, boolean prepared) {
    public Plot {
        origin = origin.immutable();
    }

    public Plot(UUID id, String blueprint, BlockPos origin, Vec3i size, @Nullable UUID builder, long retryAt, int abandons) {
        this(id, blueprint, origin, size, builder, retryAt, abandons, true);
    }

    public Plot(UUID id, String blueprint, BlockPos origin, Vec3i size, UUID builder) {
        this(id, blueprint, origin, size, builder, 0L, 0);
    }

    public boolean released() {
        return builder == null;
    }

    public Footprint footprint() {
        return Footprint.of(origin, size);
    }
}
