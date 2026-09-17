package dev.andreymudri.villagercity.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Saved village infrastructure: laid path cells, houses waiting for a path, and the artisan's workshop blocks. */
public record VillageWorks(List<BlockPos> pathCells, List<BlockPos> pathQueue, Optional<BlockPos> craftingTable, Optional<BlockPos> furnace) {
    public static final VillageWorks EMPTY = new VillageWorks(List.of(), List.of(), Optional.empty(), Optional.empty());
    public static final Codec<VillageWorks> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.listOf().optionalFieldOf("path_cells", List.of()).forGetter(VillageWorks::pathCells),
            BlockPos.CODEC.listOf().optionalFieldOf("path_queue", List.of()).forGetter(VillageWorks::pathQueue),
            BlockPos.CODEC.optionalFieldOf("crafting_table").forGetter(VillageWorks::craftingTable),
            BlockPos.CODEC.optionalFieldOf("furnace").forGetter(VillageWorks::furnace)
    ).apply(i, VillageWorks::new));
}
