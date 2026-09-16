package dev.andreymudri.villagercity.citizen;

import dev.andreymudri.villagercity.village.VillageData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

public record TaskContext(ServerLevel level, Villager villager, VillageData village, CitizenData citizen) {
    public long gameTime() {
        return level.getGameTime();
    }
}
