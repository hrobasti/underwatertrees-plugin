package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

class WaterSourceTest {

    @Test
    void stillAndFlowingWaterCount() {
        assertTrue(UnderwaterSaplingsListener.isWater(NamespacedKey.minecraft("water")));
        assertTrue(UnderwaterSaplingsListener.isWater(NamespacedKey.minecraft("flowing_water")));
    }

    @Test
    void lavaAndEmptyDoNotCount() {
        assertFalse(UnderwaterSaplingsListener.isWater(NamespacedKey.minecraft("lava")));
        assertFalse(UnderwaterSaplingsListener.isWater(NamespacedKey.minecraft("flowing_lava")));
        assertFalse(UnderwaterSaplingsListener.isWater(NamespacedKey.minecraft("empty")));
    }

    @Test
    void onlyTheMinecraftNamespaceCounts() {
        assertFalse(UnderwaterSaplingsListener.isWater(new NamespacedKey("example", "water")));
    }
}
