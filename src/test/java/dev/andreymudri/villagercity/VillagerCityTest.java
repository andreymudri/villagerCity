package dev.andreymudri.villagercity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VillagerCityTest {
    @Test
    void idUsesModNamespace() {
        assertEquals("villagercity:blueprint/starter_house", VillagerCity.id("blueprint/starter_house").toString());
    }
}
