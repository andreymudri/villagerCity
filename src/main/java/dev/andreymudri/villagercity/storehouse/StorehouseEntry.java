package dev.andreymudri.villagercity.storehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** One kind of stored item: a one-item prototype (item and data components) and how many are stored. */
public record StorehouseEntry(ItemStack prototype, long count) {
    public static final StreamCodec<RegistryFriendlyByteBuf, StorehouseEntry> STREAM_CODEC = StreamCodec.composite(
            ItemStack.STREAM_CODEC, StorehouseEntry::prototype,
            ByteBufCodecs.VAR_LONG, StorehouseEntry::count,
            StorehouseEntry::new);

    /** A stack of up to {@code amount} of this entry's item, capped at its max stack size. */
    public ItemStack stack(long amount) {
        return prototype.copyWithCount((int) Math.min(Math.min(amount, count), prototype.getMaxStackSize()));
    }
}
