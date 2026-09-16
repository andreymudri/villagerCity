package dev.andreymudri.villagercity.citizen;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum JobType implements StringRepresentable {
    NONE("none"),
    LUMBERJACK("lumberjack"),
    BUILDER("builder");

    public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);

    private final String serializedName;

    JobType(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
