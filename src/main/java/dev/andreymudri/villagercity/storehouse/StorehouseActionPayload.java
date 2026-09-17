package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.VillagerCity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: a click on the storehouse entry grid ({@link StorehouseMenu#act}). */
public record StorehouseActionPayload(int containerId, ItemStack prototype, StorehouseMenu.Button button) implements CustomPacketPayload {
    public static final Type<StorehouseActionPayload> TYPE = new Type<>(VillagerCity.id("storehouse_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StorehouseActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StorehouseActionPayload::containerId,
            ItemStack.OPTIONAL_STREAM_CODEC, StorehouseActionPayload::prototype,
            NeoForgeStreamCodecs.enumCodec(StorehouseMenu.Button.class), StorehouseActionPayload::button,
            StorehouseActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void handle(StorehouseActionPayload payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof StorehouseMenu menu && menu.containerId == payload.containerId()
                && menu.stillValid(context.player())) {
            menu.act(context.player(), payload.prototype(), payload.button());
            menu.broadcastChanges();
        }
    }
}
