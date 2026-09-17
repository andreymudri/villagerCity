package dev.andreymudri.villagercity.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.andreymudri.villagercity.citizen.JobType;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.util.StringRepresentable;

public final class VillageCodecs {
    public static final Codec<ContributionCategory> CATEGORY = StringRepresentable.fromEnum(ContributionCategory::values);
    public static final Codec<VillageAge> AGE = StringRepresentable.fromEnum(VillageAge::values);

    public static final Codec<ContributionLedger> LEDGER = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.unboundedMap(CATEGORY, Codec.LONG))
            .xmap(ContributionLedger::fromSnapshot, ContributionLedger::snapshot);

    public static final Codec<BuildingRecord> BUILDING = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("blueprint").forGetter(BuildingRecord::blueprint),
            BlockPos.CODEC.fieldOf("origin").forGetter(BuildingRecord::origin),
            Vec3i.CODEC.fieldOf("size").forGetter(BuildingRecord::size)
    ).apply(i, BuildingRecord::new));

    public static final Codec<Plot> PLOT = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Plot::id),
            Codec.STRING.fieldOf("blueprint").forGetter(Plot::blueprint),
            BlockPos.CODEC.fieldOf("origin").forGetter(Plot::origin),
            Vec3i.CODEC.fieldOf("size").forGetter(Plot::size),
            UUIDUtil.CODEC.fieldOf("builder").forGetter(Plot::builder)
    ).apply(i, Plot::new));

    public static final Codec<VillageData> VILLAGE = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(VillageData::id),
            BlockPos.CODEC.fieldOf("center").forGetter(VillageData::center),
            Codec.INT.fieldOf("radius").forGetter(VillageData::radius),
            AGE.optionalFieldOf("age", VillageAge.DARK).forGetter(VillageData::age),
            BlockPos.CODEC.optionalFieldOf("storehouse").forGetter(v -> java.util.Optional.ofNullable(v.storehousePos())),
            BUILDING.listOf().fieldOf("houses").forGetter(VillageData::houses),
            PLOT.listOf().fieldOf("plots").forGetter(VillageData::plots),
            LEDGER.fieldOf("ledger").forGetter(VillageData::ledger),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, JobType.CODEC).optionalFieldOf("citizens", Map.of()).forGetter(VillageData::citizens),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).optionalFieldOf("storehouse_debt", Map.of()).forGetter(VillageData::storehouseDebt),
            Codec.BOOL.optionalFieldOf("managed", true).forGetter(VillageData::managed)
    ).apply(i, (id, center, radius, age, storehouse, houses, plots, ledger, citizens, storehouseDebt, managed) ->
            new VillageData(id, center, radius, age, storehouse.orElse(null), houses, plots, ledger, citizens, storehouseDebt, managed)));

    private VillageCodecs() {
    }
}
