package dev.andreymudri.villagercity;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(VillagerCity.MODID)
public final class VillagerCity {
    public static final String MODID = "villagercity";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VillagerCity(IEventBus modBus) {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
