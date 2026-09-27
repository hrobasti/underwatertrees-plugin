package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

class SpawnProtectionTest {

    @Test
    void edgeOfRadiusIsStillProtected() {
        assertTrue(UnderwaterSaplingsListener.isWithinSpawnProtection(16, -16, 0, 0, 16));
    }

    @Test
    void oneBlockBeyondRadiusOnEitherAxisIsFree() {
        assertFalse(UnderwaterSaplingsListener.isWithinSpawnProtection(17, 0, 0, 0, 16));
        assertFalse(UnderwaterSaplingsListener.isWithinSpawnProtection(0, -17, 0, 0, 16));
    }

    @Test
    void distanceIsMeasuredFromWorldSpawnNotOrigin() {
        assertTrue(UnderwaterSaplingsListener.isWithinSpawnProtection(110, 205, 100, 200, 16));
        assertFalse(UnderwaterSaplingsListener.isWithinSpawnProtection(0, 0, 100, 200, 16));
    }

    @Test
    void onlyTheWorldHoldingTheSpawnIsProtected() {
        NamespacedKey overworld = NamespacedKey.minecraft("overworld");
        NamespacedKey nether = NamespacedKey.minecraft("the_nether");
        NamespacedKey custom = new NamespacedKey("example", "lobby");
        assertTrue(UnderwaterSaplingsListener.isSpawnProtected(overworld, 5, 5, overworld, 0, 0, 16));
        assertFalse(UnderwaterSaplingsListener.isSpawnProtected(nether, 5, 5, overworld, 0, 0, 16));
        assertTrue(UnderwaterSaplingsListener.isSpawnProtected(custom, 5, 5, custom, 0, 0, 16));
        assertFalse(UnderwaterSaplingsListener.isSpawnProtected(overworld, 5, 5, custom, 0, 0, 16));
    }

    @Test
    void radiusZeroDisablesProtection() {
        NamespacedKey overworld = NamespacedKey.minecraft("overworld");
        assertFalse(UnderwaterSaplingsListener.isSpawnProtected(overworld, 0, 0, overworld, 0, 0, 0));
    }
}
