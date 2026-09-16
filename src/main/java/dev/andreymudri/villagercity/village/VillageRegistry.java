package dev.andreymudri.villagercity.village;

import com.mojang.serialization.Codec;
import dev.andreymudri.villagercity.VillagerCity;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** All villages of one level, saved as data/villagercity_villages.dat. */
public final class VillageRegistry extends SavedData {
    public static final String NAME = "villagercity_villages";
    public static final int MERGE_DISTANCE = 64;
    private static final Codec<List<VillageData>> LIST_CODEC = VillageCodecs.VILLAGE.listOf();
    private static final SavedData.Factory<VillageRegistry> FACTORY = new SavedData.Factory<>(VillageRegistry::new, VillageRegistry::load, null);

    private final Map<UUID, VillageData> villages = new LinkedHashMap<>();

    public static VillageRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static VillageRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageRegistry registry = new VillageRegistry();
        if (tag.contains("villages")) {
            LIST_CODEC.parse(NbtOps.INSTANCE, tag.get("villages"))
                    .resultOrPartial(error -> VillagerCity.LOGGER.error("Failed to load villages: {}", error))
                    .ifPresent(list -> list.forEach(village -> registry.villages.put(village.id(), village)));
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        LIST_CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(villages.values()))
                .resultOrPartial(error -> VillagerCity.LOGGER.error("Failed to save villages: {}", error))
                .ifPresent(encoded -> tag.put("villages", encoded));
        return tag;
    }

    public @Nullable VillageData get(UUID id) {
        return villages.get(id);
    }

    public Collection<VillageData> all() {
        return Collections.unmodifiableCollection(villages.values());
    }

    /** Returns the village whose center is within merge distance of the bell, or registers a new one there. */
    public VillageData register(BlockPos bell) {
        VillageData existing = nearest(bell, MERGE_DISTANCE);
        if (existing != null) {
            return existing;
        }
        VillageData village = new VillageData(UUID.randomUUID(), bell, VillageData.DEFAULT_RADIUS);
        villages.put(village.id(), village);
        setDirty();
        return village;
    }

    public void remove(UUID id) {
        if (villages.remove(id) != null) {
            setDirty();
        }
    }

    public @Nullable VillageData nearest(BlockPos pos, int maxDistance) {
        long max = (long) maxDistance * maxDistance;
        return villages.values().stream()
                .filter(village -> horizontalDistanceSqr(village.center(), pos) <= max)
                .min(Comparator.comparingLong(village -> horizontalDistanceSqr(village.center(), pos)))
                .orElse(null);
    }

    public @Nullable VillageData villageAt(BlockPos pos) {
        return villages.values().stream().filter(village -> village.contains(pos)).findFirst().orElse(null);
    }

    public static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
