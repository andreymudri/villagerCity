package dev.andreymudri.villagercity.storehouse;

import dev.andreymudri.villagercity.VillagerCity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = VillagerCity.MODID)
public final class StorehouseContent {
    public static final DeferredHolder<Block, StorehouseBlock> BLOCK =
            DeferredHolder.create(Registries.BLOCK, VillagerCity.id("storehouse"));
    public static final DeferredHolder<Item, BlockItem> ITEM =
            DeferredHolder.create(Registries.ITEM, VillagerCity.id("storehouse"));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StorehouseBlockEntity>> BLOCK_ENTITY =
            DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, VillagerCity.id("storehouse"));
    public static final DeferredHolder<MenuType<?>, MenuType<StorehouseMenu>> MENU =
            DeferredHolder.create(Registries.MENU, VillagerCity.id("storehouse"));

    private StorehouseContent() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.BLOCK, VillagerCity.id("storehouse"),
                () -> new StorehouseBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL).explosionResistance(1200.0f)));
        event.register(Registries.ITEM, VillagerCity.id("storehouse"),
                () -> new BlockItem(BLOCK.get(), new Item.Properties()));
        event.register(Registries.BLOCK_ENTITY_TYPE, VillagerCity.id("storehouse"),
                () -> BlockEntityType.Builder.of(StorehouseBlockEntity::new, BLOCK.get()).build(null));
        event.register(Registries.MENU, VillagerCity.id("storehouse"),
                () -> IMenuTypeExtension.create((containerId, inventory, data) -> new StorehouseMenu(containerId, inventory)));
    }

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BLOCK_ENTITY.get(), (storehouse, side) -> storehouse.itemHandler());
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(StorehouseContentsPayload.TYPE, StorehouseContentsPayload.STREAM_CODEC, StorehouseContentsPayload::handle)
                .playToServer(StorehouseActionPayload.TYPE, StorehouseActionPayload.STREAM_CODEC, StorehouseActionPayload::handle);
    }
}
