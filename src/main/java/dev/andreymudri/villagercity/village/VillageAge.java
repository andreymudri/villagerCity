package dev.andreymudri.villagercity.village;

import net.minecraft.util.StringRepresentable;

public enum VillageAge implements StringRepresentable {
    DARK("dark"),
    FEUDAL("feudal"),
    CASTLE("castle"),
    IMPERIAL("imperial"),
    NETHERITE("netherite");

    private final String serializedName;

    VillageAge(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
