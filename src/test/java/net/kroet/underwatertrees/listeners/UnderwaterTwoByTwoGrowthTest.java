package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.kroet.underwatertrees.listeners.UnderwaterTwoByTwoGrowth.Offset;
import net.kroet.underwatertrees.listeners.UnderwaterTwoByTwoGrowth.Refill;
import org.junit.jupiter.api.Test;

class UnderwaterTwoByTwoGrowthTest {

    private static final List<Offset> GATES = UnderwaterTwoByTwoGrowth.trunkGateOffsets();

    @Test
    void trunkGatesCoverEveryLayerAboveTheSaplingsUpToTheTallestTrunk() {
        for (int dy = 1; dy <= 8; dy++) {
            assertTrue(GATES.contains(new Offset(0, dy, 0)), "layer " + dy);
        }
        assertFalse(GATES.contains(new Offset(0, 0, 0)), "the sapling layer is cleared separately");
        assertFalse(GATES.contains(new Offset(0, 9, 0)), "a trunk has at most 9 layers");
    }

    @Test
    void leaningCellsStartAtTheEarliestLayerTheLeanCanReach() {
        for (Offset side : List.of(new Offset(1, 0, 0), new Offset(-1, 0, 0), new Offset(0, 0, 1),
                new Offset(0, 0, -1))) {
            assertFalse(GATES.contains(new Offset(side.dx(), 2, side.dz())));
            assertTrue(GATES.contains(new Offset(side.dx(), 3, side.dz())));
            assertFalse(GATES.contains(new Offset(2 * side.dx(), 3, 2 * side.dz())));
            assertTrue(GATES.contains(new Offset(2 * side.dx(), 4, 2 * side.dz())));
            assertTrue(GATES.contains(new Offset(2 * side.dx(), 8, 2 * side.dz())));
        }
    }

    @Test
    void trunkGatesOnlyLeanAlongOneAxisAndNoFurtherThanTwo() {
        for (Offset gate : GATES) {
            assertTrue(gate.dx() == 0 || gate.dz() == 0, gate.toString());
            assertTrue(Math.abs(gate.dx()) + Math.abs(gate.dz()) <= 2, gate.toString());
        }
        assertEquals(8 + 4 * 6 + 4 * 5, GATES.size());
        assertEquals(GATES.size(), Set.copyOf(GATES).size());
    }

    @Test
    void originIsTheNorthWestCornerOfTheSquareHoldingTheGrownSapling() {
        // Square at x/z 0..1: grown from each of its four corners.
        Set<List<Integer>> square = Set.of(List.of(0, 0), List.of(1, 0), List.of(0, 1), List.of(1, 1));
        assertEquals(new Offset(0, 0, 0), UnderwaterTwoByTwoGrowth.findOrigin((x, z) -> square.contains(List.of(x, z))));
        assertEquals(new Offset(-1, 0, -1),
                UnderwaterTwoByTwoGrowth.findOrigin((x, z) -> square.contains(List.of(x + 1, z + 1))));
        assertEquals(new Offset(-1, 0, 0),
                UnderwaterTwoByTwoGrowth.findOrigin((x, z) -> square.contains(List.of(x + 1, z))));
    }

    @Test
    void originFollowsVanillaOrderAndNeedsAFullSquare() {
        assertEquals(new Offset(0, 0, 0), UnderwaterTwoByTwoGrowth.findOrigin((x, z) -> true));
        assertNull(UnderwaterTwoByTwoGrowth.findOrigin((x, z) -> x == 0 && z == 0));
        assertNull(UnderwaterTwoByTwoGrowth.findOrigin((x, z) -> !(x == 1 && z == 1) && !(x == -1 || z == -1)));
    }

    @Test
    void drainedCellsGetTheirWaterBackUnlessTheTreeFilledThem() {
        assertEquals(Refill.WATER, UnderwaterTwoByTwoGrowth.refillFor(true, false));
        assertEquals(Refill.WATERLOG, UnderwaterTwoByTwoGrowth.refillFor(false, true));
        assertEquals(Refill.KEEP, UnderwaterTwoByTwoGrowth.refillFor(false, false));
    }

    @Test
    void theTreeGrowsOnlyIfProtectionLeavesItAndSomeBlocks() {
        assertTrue(UnderwaterTwoByTwoGrowth.growsAfter(false, 40));
        assertTrue(UnderwaterTwoByTwoGrowth.growsAfter(false, 1));
        assertFalse(UnderwaterTwoByTwoGrowth.growsAfter(true, 40));
        assertFalse(UnderwaterTwoByTwoGrowth.growsAfter(false, 0));
    }
}
