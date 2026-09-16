package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.VillagerCity;
import dev.andreymudri.villagercity.blueprint.Blueprint;
import dev.andreymudri.villagercity.blueprint.BlueprintPlacement;
import dev.andreymudri.villagercity.blueprint.Blueprints;
import dev.andreymudri.villagercity.citizen.CitizenAttachments;
import dev.andreymudri.villagercity.citizen.CitizenData;
import dev.andreymudri.villagercity.citizen.JobType;
import dev.andreymudri.villagercity.job.BuilderJob;
import dev.andreymudri.villagercity.storehouse.StorehouseBlockEntity;
import dev.andreymudri.villagercity.storehouse.StorehouseContent;
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class EndToEndTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);

    private static void stockAllButLogs(StorehouseBlockEntity storehouse, Blueprint blueprint) {
        for (Map.Entry<Item, Integer> entry : blueprint.requiredMaterials().entrySet()) {
            if (entry.getKey() != Items.OAK_LOG) {
                storehouse.insertFromCitizen(new ItemStack(entry.getKey(), entry.getValue()));
            }
        }
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_e2e_village", timeoutTicks = 12000)
    public static void villageBuildsHouseFromGatheredWood(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, true);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        LumberjackTests.plantTree(helper, new BlockPos(8, 1, 8));
        LumberjackTests.plantTree(helper, new BlockPos(8, 1, 38));
        LumberjackTests.plantTree(helper, new BlockPos(38, 1, 8));
        GameTestSupport.spawnVillager(helper, 23, 1, 23);
        GameTestSupport.spawnVillager(helper, 25, 1, 23);
        AtomicBoolean stocked = new AtomicBoolean();
        helper.onEachTick(() -> {
            BlockPos storehouse = village.storehousePos();
            if (!stocked.get() && storehouse != null
                    && helper.getLevel().getBlockEntity(storehouse) instanceof StorehouseBlockEntity entity) {
                stockAllButLogs(entity, blueprint);
                stocked.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stocked.get(), "storehouse never placed");
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount() + ", plots " + village.plots().size());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_e2e_reload", timeoutTicks = 6000)
    public static void builderResumesAfterSaveAndReload(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        ServerLevel level = helper.getLevel();
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(level, Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        Villager builder = GameTestSupport.spawnVillager(helper, 22, 1, 22);
        builder.setData(CitizenAttachments.CITIZEN, new CitizenData(village.id(), JobType.BUILDER, ItemStack.EMPTY));
        UUID villageId = village.id();
        UUID builderId = builder.getUUID();
        AtomicBoolean reloaded = new AtomicBoolean();
        AtomicReference<Plot> plotAtReload = new AtomicReference<>();

        helper.onEachTick(() -> {
            if (reloaded.get()) {
                return;
            }
            VillageData live = VillageRegistry.get(level).get(villageId);
            Plot plot = live == null ? null : live.plotBuiltBy(builderId).orElse(null);
            if (plot == null) {
                return;
            }
            long placed = blueprint.placements().stream()
                    .filter(p -> !p.state().isAir())
                    .filter(p -> BuilderJob.isDone(level, plot, p))
                    .count();
            if (placed < 20) {
                return;
            }
            Villager current = (Villager) level.getEntity(builderId);
            CompoundTag entityTag = new CompoundTag();
            current.save(entityTag);
            current.discard();
            Entity copy = EntityType.create(entityTag, level).orElseThrow();
            level.addFreshEntity(copy);

            CompoundTag registryTag = VillageRegistry.get(level).save(new CompoundTag(), level.registryAccess());
            level.getDataStorage().set(VillageRegistry.NAME, VillageRegistry.load(registryTag, level.registryAccess()));
            plotAtReload.set(plot);
            reloaded.set(true);
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(reloaded.get(), "reload never happened");
            VillageData live = VillageRegistry.get(level).get(villageId);
            helper.assertTrue(live != null && live != village, "registry was not replaced");
            helper.assertTrue(live.houseCount() == 1, "houses after reload " + live.houseCount());
            Plot plot = plotAtReload.get();
            for (BlueprintPlacement placement : blueprint.placements()) {
                helper.assertTrue(BuilderJob.isDone(level, plot, placement), "unfinished at " + helper.relativePos(plot.origin().offset(placement.offset())));
            }
            VillageRegistry.get(level).remove(villageId);
        });
    }
}
