package dev.andreymudri.villagercity;

import com.mojang.logging.LogUtils;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.citizen.Jobs;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.job.LumberjackJob;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(VillagerCity.MODID)
public final class VillagerCity {
    public static final String MODID = "villagercity";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VillagerCity(IEventBus modBus) {
        Jobs.register(JobType.LUMBERJACK, LumberjackJob::new);
        Jobs.register(JobType.BUILDER, BuilderJob::new);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
