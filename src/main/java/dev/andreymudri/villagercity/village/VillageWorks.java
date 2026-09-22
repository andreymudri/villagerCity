package dev.andreymudri.villagercity.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Saved village infrastructure: laid path cells, houses waiting for a path, the street graph, and the artisan's
 * workshop blocks.
 * <p>
 * {@code pathCells} is every laid path column, whatever laid it. {@code streets} is the street graph on top of it: one
 * {@link StreetCell} per laid street cell, with the hop count it was recorded with. Saves from before the
 * street graph carry no {@code streets} key and load with an empty graph.
 * <p>
 * The workshop is a crafting table alone. Saves from the version that placed a furnace carry a {@code furnace} key,
 * which the codec ignores: the village keeps no furnace position, so it cannot come back to maintain one.
 * <p>
 * Nothing about what was inside that furnace is saved either, on purpose. A record of what the village left cooking is a
 * claim about items that are gone the moment nobody is watching them, and five review rounds of such a record ended
 * in five different ways of taking a player's stock. The village now claims only what it watched itself make, which
 * is a thing it can only know while it is standing there, so there is nothing to persist.
 */
public record VillageWorks(List<BlockPos> pathCells, List<BlockPos> pathQueue, List<StreetCell> streets,
                           Optional<BlockPos> craftingTable) {
    public static final VillageWorks EMPTY = new VillageWorks(List.of(), List.of(), List.of(), Optional.empty());
    public static final Codec<VillageWorks> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.listOf().optionalFieldOf("path_cells", List.of()).forGetter(VillageWorks::pathCells),
            BlockPos.CODEC.listOf().optionalFieldOf("path_queue", List.of()).forGetter(VillageWorks::pathQueue),
            StreetCell.CODEC.listOf().optionalFieldOf("streets", List.of()).forGetter(VillageWorks::streets),
            BlockPos.CODEC.optionalFieldOf("crafting_table").forGetter(VillageWorks::craftingTable)
    ).apply(i, VillageWorks::new));

    /**
     * One cell of the street graph: the air cell above the street block, where a villager walks, and its hop count,
     * how deep in the graph it lies as counted by whatever laid it.
     */
    public record StreetCell(BlockPos pos, int hops) {
        public static final Codec<StreetCell> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(StreetCell::pos),
                Codec.INT.fieldOf("hops").forGetter(StreetCell::hops)
        ).apply(i, StreetCell::new));

        public StreetCell {
            pos = pos.immutable();
        }
    }
}
