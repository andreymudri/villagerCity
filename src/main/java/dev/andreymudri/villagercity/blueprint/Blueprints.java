package dev.andreymudri.villagercity.blueprint;

import dev.andreymudri.villagercity.VillagerCity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Loads blueprints from data/<ns>/structure/<path>.nbt. A broken or missing file is logged once and yields empty. */
public final class Blueprints {
    public static final ResourceLocation STARTER_HOUSE = VillagerCity.id("blueprint/starter_house");

    private static final Set<ResourceLocation> REPORTED = ConcurrentHashMap.newKeySet();

    private Blueprints() {
    }

    public static Optional<Blueprint> load(ServerLevel level, ResourceLocation id) {
        Optional<StructureTemplate> template;
        try {
            template = level.getStructureManager().get(id);
        } catch (RuntimeException e) {
            report(id, "failed to read: " + e);
            return Optional.empty();
        }
        if (template.isEmpty() || template.get().palettes.isEmpty()) {
            report(id, "missing or empty");
            return Optional.empty();
        }
        List<BlueprintPlacement> placements = template.get().palettes.get(0).blocks().stream()
                .filter(info -> !info.state().is(Blocks.STRUCTURE_VOID))
                .map(info -> new BlueprintPlacement(info.pos(), info.state()))
                .toList();
        return Optional.of(new Blueprint(id, template.get().getSize(), placements));
    }

    private static void report(ResourceLocation id, String problem) {
        if (REPORTED.add(id)) {
            VillagerCity.LOGGER.error("Blueprint {} {}; builders will not use it", id, problem);
        }
    }
}
