package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.VillagerCity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Positions of logs an entity (a player or a citizen) placed in one level, saved as data/villagercity_placed_logs.dat.
 * The lumberjack never fells a tree touching one. Placements are recorded after every other listener had its chance
 * to cancel them; breaking the block by a player forgets it. Other removals (fire, explosions) leave a stale entry,
 * which can only keep a future tree at that exact spot from being felled.
 */
@EventBusSubscriber(modid = VillagerCity.MODID)
public final class PlacedLogs extends SavedData {
    public static final String NAME = "villagercity_placed_logs";
    private static final SavedData.Factory<PlacedLogs> FACTORY = new SavedData.Factory<>(PlacedLogs::new, PlacedLogs::load, null);

    private final LongSet positions = new LongOpenHashSet();

    public static PlacedLogs get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static PlacedLogs load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacedLogs logs = new PlacedLogs();
        for (long pos : tag.getLongArray("positions")) {
            logs.positions.add(pos);
        }
        return logs;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("positions", positions.toLongArray());
        return tag;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() == null || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // For non-player entities NeoForge reports the snapshot's old state as the placed block, so read the level.
        if (level.getBlockState(event.getPos()).is(BlockTags.LOGS)) {
            get(level).add(event.getPos());
        } else {
            get(level).remove(event.getPos());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            get(level).remove(event.getPos());
        }
    }

    public boolean contains(BlockPos pos) {
        return positions.contains(pos.asLong());
    }

    public void add(BlockPos pos) {
        if (positions.add(pos.asLong())) {
            setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (positions.remove(pos.asLong())) {
            setDirty();
        }
    }
}
