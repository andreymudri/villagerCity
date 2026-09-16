package dev.andreymudri.villagercity.citizen;

import dev.andreymudri.villagercity.VillagerCity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = VillagerCity.MODID)
public final class CitizenAttachments {
    /** An activity with no behaviours: while active, vanilla WORK/IDLE/MEET/PLAY behaviours cannot start. */
    public static final DeferredHolder<Activity, Activity> CITY_TASK =
            DeferredHolder.create(Registries.ACTIVITY, VillagerCity.id("city_task"));
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CitizenData>> CITIZEN =
            DeferredHolder.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen"));
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CitizenRuntime>> RUNTIME =
            DeferredHolder.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen_runtime"));

    private CitizenAttachments() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.ACTIVITY, VillagerCity.id("city_task"), () -> new Activity("villagercity:city_task"));
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen"),
                () -> AttachmentType.builder(() -> new CitizenData()).serialize(CitizenData.CODEC).build());
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, VillagerCity.id("citizen_runtime"),
                () -> AttachmentType.builder(() -> new CitizenRuntime()).build());
    }
}
