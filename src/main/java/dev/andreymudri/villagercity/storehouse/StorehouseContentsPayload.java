package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.VillagerCity;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server to client: every entry of the storehouse the player's open menu shows. */
public record StorehouseContentsPayload(int containerId, List<StorehouseEntry> entries) implements CustomPacketPayload {
    public static final Type<StorehouseContentsPayload> TYPE = new Type<>(VillagerCity.id("storehouse_contents"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StorehouseContentsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StorehouseContentsPayload::containerId,
            StorehouseEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), StorehouseContentsPayload::entries,
            StorehouseContentsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void handle(StorehouseContentsPayload payload, IPayloadContext context) {
        if (context.player().containerMenu instanceof StorehouseMenu menu && menu.containerId == payload.containerId()) {
            menu.setClientEntries(payload.entries());
        }
    }
}
