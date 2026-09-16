package dev.andreymudri.villagercity.village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** A finished building. Vanilla homes adopted at registration use blueprint "minecraft:home" and size 1x1x1. */
public record BuildingRecord(String blueprint, BlockPos origin, Vec3i size) {
    public static final String VANILLA_HOME = "minecraft:home";

    public BuildingRecord {
        origin = origin.immutable();
    }

    public Footprint footprint() {
        return Footprint.of(origin, size);
    }
}
