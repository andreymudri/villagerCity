package dev.andreymudri.villagercity.blueprint;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** A structure template in build order: bottom layer first, solid blocks before non-solid ones. */
public final class Blueprint {
    public static final Comparator<BlueprintPlacement> BUILD_ORDER = Comparator
            .comparingInt((BlueprintPlacement p) -> p.offset().getY())
            .thenComparingInt(p -> p.state().blocksMotion() ? 0 : 1)
            .thenComparingInt(p -> p.offset().getZ())
            .thenComparingInt(p -> p.offset().getX());

    private final ResourceLocation id;
    private final Vec3i size;
    private final List<BlueprintPlacement> placements;

    public Blueprint(ResourceLocation id, Vec3i size, Collection<BlueprintPlacement> placements) {
        this.id = id;
        this.size = size;
        this.placements = placements.stream().sorted(BUILD_ORDER).toList();
    }

    public ResourceLocation id() {
        return id;
    }

    public Vec3i size() {
        return size;
    }

    public List<BlueprintPlacement> placements() {
        return placements;
    }

    public Map<Item, Integer> requiredMaterials() {
        return materialsFor(placements);
    }

    public static Map<Item, Integer> materialsFor(Collection<BlueprintPlacement> placements) {
        Map<Item, Integer> materials = new LinkedHashMap<>();
        for (BlueprintPlacement placement : placements) {
            Item cost = costOf(placement.state());
            if (cost != Items.AIR) {
                materials.merge(cost, 1, Integer::sum);
            }
        }
        return materials;
    }

    /** The item consumed to place this state; AIR when free (air, the second half of a bed or door, or no item form). */
    public static Item costOf(BlockState state) {
        if (state.isAir()) {
            return Items.AIR;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return Items.AIR;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return Items.AIR;
        }
        return state.getBlock().asItem();
    }
}
