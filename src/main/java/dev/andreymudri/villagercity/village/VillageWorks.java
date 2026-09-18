package dev.andreymudri.villagercity.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Saved village infrastructure: laid path cells, houses waiting for a path, the artisan's workshop blocks, and the
 * village's receipt for whatever it has left cooking in the furnace.
 */
public record VillageWorks(List<BlockPos> pathCells, List<BlockPos> pathQueue, Optional<BlockPos> craftingTable,
                           Optional<BlockPos> furnace, Optional<FurnaceClaim> furnaceClaim) {
    public static final VillageWorks EMPTY = new VillageWorks(List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    public static final Codec<VillageWorks> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.listOf().optionalFieldOf("path_cells", List.of()).forGetter(VillageWorks::pathCells),
            BlockPos.CODEC.listOf().optionalFieldOf("path_queue", List.of()).forGetter(VillageWorks::pathQueue),
            BlockPos.CODEC.optionalFieldOf("crafting_table").forGetter(VillageWorks::craftingTable),
            BlockPos.CODEC.optionalFieldOf("furnace").forGetter(VillageWorks::furnace),
            FurnaceClaim.CODEC.optionalFieldOf("furnace_claim").forGetter(VillageWorks::furnaceClaim)
    ).apply(i, VillageWorks::new));

    /**
     * What the village put into its furnace and has not taken back yet: how much of which input and fuel it handed
     * over, and how many of which output that batch is owed.
     * <p>
     * The furnace is shared with players and nothing in a slot says who filled it, so this receipt is the only thing
     * that tells a batch an interrupted trip left behind from a player's own smelting. It is written when the villager
     * actually hands the items over and cleared when it takes the output back, so it outlives a night, a reload and
     * the villager itself. A claim is only ever opened on a furnace whose three slots are empty, which is what makes
     * "everything in the result slot up to {@link #outputCount}" exactly the village's own output and nobody else's.
     */
    public record FurnaceClaim(Item input, int inputCount, Item fuel, int fuelCount, Item output, int outputCount) {
        public static final Codec<FurnaceClaim> CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("input").forGetter(FurnaceClaim::input),
                Codec.INT.fieldOf("input_count").forGetter(FurnaceClaim::inputCount),
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("fuel").forGetter(FurnaceClaim::fuel),
                Codec.INT.fieldOf("fuel_count").forGetter(FurnaceClaim::fuelCount),
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("output").forGetter(FurnaceClaim::output),
                Codec.INT.fieldOf("output_count").forGetter(FurnaceClaim::outputCount)
        ).apply(i, FurnaceClaim::new));
    }
}
