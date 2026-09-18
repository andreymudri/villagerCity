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
     * over, how many of which output that batch is owed, and how many of them it has already carried off.
     * <p>
     * The furnace is shared with players and nothing in a slot says who filled it, so this receipt is the only thing
     * that tells a batch an interrupted trip left behind from a player's own smelting. It is written when the villager
     * actually hands the items over and cleared when the batch is settled, so it outlives a night, a reload and the
     * villager itself.
     * <p>
     * {@link #takenCount} is what makes the receipt a budget rather than a licence. Everything the village may still
     * take is counted over the batch's whole life, not from whatever the furnace happens to hold at the moment it is
     * asked, so draining the result slot — by hopper, by player, by anything — cannot hand the allowance back.
     */
    public record FurnaceClaim(Item input, int inputCount, Item fuel, int fuelCount, Item output, int outputCount,
                               int takenCount) {
        public static final Codec<FurnaceClaim> CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("input").forGetter(FurnaceClaim::input),
                Codec.INT.fieldOf("input_count").forGetter(FurnaceClaim::inputCount),
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("fuel").forGetter(FurnaceClaim::fuel),
                Codec.INT.fieldOf("fuel_count").forGetter(FurnaceClaim::fuelCount),
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("output").forGetter(FurnaceClaim::output),
                Codec.INT.fieldOf("output_count").forGetter(FurnaceClaim::outputCount),
                Codec.INT.optionalFieldOf("taken_count", 0).forGetter(FurnaceClaim::takenCount)
        ).apply(i, FurnaceClaim::new));

        /** A receipt for a batch nothing has been taken back from yet. */
        public FurnaceClaim(Item input, int inputCount, Item fuel, int fuelCount, Item output, int outputCount) {
            this(input, inputCount, fuel, fuelCount, output, outputCount, 0);
        }

        /** The same receipt after the village carried {@code amount} more of the output off. */
        public FurnaceClaim plusTaken(int amount) {
            return new FurnaceClaim(input, inputCount, fuel, fuelCount, output, outputCount, takenCount + amount);
        }
    }
}
