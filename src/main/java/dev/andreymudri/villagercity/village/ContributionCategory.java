package dev.andreymudri.villagercity.village;

import net.minecraft.util.StringRepresentable;

/** What a player did for a village. Slice 1 records deposits only. */
public enum ContributionCategory implements StringRepresentable {
    DEPOSIT("deposit");

    private final String serializedName;

    ContributionCategory(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
