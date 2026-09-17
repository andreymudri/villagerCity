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
import dev.andreymudri.villagercity.village.Plot;
import dev.andreymudri.villagercity.village.VillageCodecs;
import dev.andreymudri.villagercity.village.VillageData;
import dev.andreymudri.villagercity.village.VillageTicker;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(VillagerCity.MODID)
@PrefixGameTestTemplate(false)
public final class PlotHandoverTests {
    private static final BlockPos BELL = new BlockPos(24, 1, 24);
    private static final BlockPos PLOT_ORIGIN = new BlockPos(8, 1, 8);

    private static JobType jobOf(Villager villager) {
        return villager.getExistingData(CitizenAttachments.CITIZEN.get()).map(CitizenData::job).orElse(JobType.NONE);
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_handover_dim", timeoutTicks = 400)
    public static void dimensionChangeFreesTheJob(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 8, false);
        Villager a = GameTestSupport.spawnVillager(helper, 20, 1, 20);
        Villager b = GameTestSupport.spawnVillager(helper, 28, 1, 20);
        VillageTicker.tickVillage(helper.getLevel(), village);
        Villager lumberjack = jobOf(a) == JobType.LUMBERJACK ? a : b;
        if (jobOf(lumberjack) != JobType.LUMBERJACK) {
            VillageTestSupport.remove(helper, village);
            helper.fail("no lumberjack after the first tick");
            return;
        }
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        nether.setChunkForced(0, 0, true);
        UUID lumberjackId = lumberjack.getUUID();
        lumberjack.teleportTo(nether, 8.5, 70, 8.5, Set.of(), 0, 0);
        helper.runAfterDelay(100, () -> {
            // spawned just before the tick: a villager left alone for 100 ticks can wander outside the radius
            Villager replacement = GameTestSupport.spawnVillager(helper, 20, 1, 28);
            VillageTicker.tickVillage(helper.getLevel(), village);
            JobType replacementJob = jobOf(replacement);
            int lumberjacks = village.jobCount(JobType.LUMBERJACK);
            Entity away = nether.getEntity(lumberjackId);
            if (away != null) {
                away.discard();
            }
            nether.setChunkForced(0, 0, false);
            VillageTestSupport.remove(helper, village);
            helper.assertTrue(replacementJob == JobType.LUMBERJACK, "replacement job " + replacementJob + ", roster " + village.citizens());
            helper.assertTrue(lumberjacks == 1, "roster lumberjacks " + lumberjacks);
            helper.succeed();
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_handover_builder", timeoutTicks = 4000)
    public static void leavingBuilderPlotIsTakenOver(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        List<BlueprintPlacement> floor = blueprint.placements().stream().filter(p -> p.offset().getY() == 0).toList();
        helper.assertTrue(floor.size() == 25 && floor.stream().allMatch(p -> p.state().is(Blocks.COBBLESTONE)), "unexpected starter house floor");
        floor.forEach(p -> helper.setBlock(PLOT_ORIGIN.offset(p.offset()), p.state()));
        List<BlueprintPlacement> remaining = blueprint.placements().stream().filter(p -> p.offset().getY() != 0).toList();
        StorehouseBlockEntity storehouse = BuilderTests.stockedStorehouse(helper, village, Blueprint.materialsFor(remaining));
        helper.assertTrue(!storehouse.hasAll(blueprint.requiredMaterials()), "storehouse holds a full blueprint's materials");
        Villager first = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        UUID plotId = UUID.randomUUID();
        village.addPlot(new Plot(plotId, blueprint.id().toString(), helper.absolutePos(PLOT_ORIGIN), blueprint.size(), first.getUUID(), 0L, 1));
        CitizenTestSupport.enroll(first, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        first.discard();
        Plot plot = village.plots().stream().filter(p -> p.id().equals(plotId)).findFirst().orElse(null);
        if (plot == null || plot.builder() != null || plot.abandons() != 1 || plot.retryAt() != 0L) {
            VillageTestSupport.remove(helper, village);
            helper.fail("plot of the discarded builder: " + plot);
            return;
        }
        Villager second = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        CitizenTestSupport.enroll(second, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.succeedWhen(() -> {
            helper.assertTrue(village.houseCount() == 1, "houses " + village.houseCount() + ", plots " + village.plots());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA, batch = "vc_handover_retry", timeoutTicks = 600)
    public static void releasedPlotWaitsForRetry(GameTestHelper helper) {
        GameTestSupport.prepareArea(helper);
        VillageData village = VillageTestSupport.freshVillage(helper, BELL, 4, false);
        Blueprint blueprint = Blueprints.load(helper.getLevel(), Blueprints.STARTER_HOUSE).orElseThrow();
        BuilderTests.stockedStorehouse(helper, village, blueprint, 1);
        long retryAt = helper.getLevel().getGameTime() + 200;
        village.addPlot(new Plot(UUID.randomUUID(), blueprint.id().toString(), helper.absolutePos(PLOT_ORIGIN), blueprint.size(), null, retryAt, 1));
        Villager builder = GameTestSupport.spawnVillager(helper, 12, 1, 14);
        CitizenTestSupport.enroll(builder, village, JobType.BUILDER, ItemStack.EMPTY, new BuilderJob());
        helper.runAfterDelay(100, () -> {
            if (village.plotBuiltBy(builder.getUUID()).isPresent()) {
                VillageTestSupport.remove(helper, village);
                helper.fail("released plot taken before retryAt");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getGameTime() >= retryAt, "retryAt not reached");
            helper.assertTrue(village.plotBuiltBy(builder.getUUID()).isPresent(), "released plot not taken after retryAt: " + village.plots());
            VillageTestSupport.remove(helper, village);
        });
    }

    @GameTest(template = GameTestSupport.TEST_AREA)
    public static void plotFieldsSurviveCodecRoundTrip(GameTestHelper helper) {
        VillageData village = new VillageData(UUID.randomUUID(), helper.absolutePos(BELL), 8);
        Vec3i size = new Vec3i(5, 5, 5);
        Plot released = new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(PLOT_ORIGIN), size, null, 1234L, 2);
        Plot assigned = new Plot(UUID.randomUUID(), "villagercity:blueprint/starter_house", helper.absolutePos(new BlockPos(30, 1, 30)), size, UUID.randomUUID(), 55L, 1);
        village.addPlot(released);
        village.addPlot(assigned);
        Tag encoded = VillageCodecs.VILLAGE.encodeStart(NbtOps.INSTANCE, village).getOrThrow();
        VillageData decoded = VillageCodecs.VILLAGE.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        helper.assertTrue(decoded.plots().equals(List.of(released, assigned)), "plots " + decoded.plots());
        CompoundTag oldSave = ((CompoundTag) encoded).copy();
        ListTag plots = oldSave.getList("plots", Tag.TAG_COMPOUND);
        plots.getCompound(1).remove("retry_at");
        plots.getCompound(1).remove("abandons");
        Plot legacy = VillageCodecs.VILLAGE.parse(NbtOps.INSTANCE, oldSave).getOrThrow().plots().get(1);
        helper.assertTrue(legacy.retryAt() == 0L && legacy.abandons() == 0, "legacy plot " + legacy);
        helper.assertTrue(assigned.builder().equals(legacy.builder()), "legacy builder " + legacy.builder());
        helper.succeed();
    }
}
