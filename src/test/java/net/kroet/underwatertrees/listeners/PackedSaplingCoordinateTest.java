package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PackedSaplingCoordinateTest {

    @Test
    void roundTripsCornerAndMidRangeCoordinates() {
        assertRoundTrip(0, 0, 0);
        assertRoundTrip(15, 0, 15);
        assertRoundTrip(0, -64, 15);
        assertRoundTrip(15, 319, 0);
        assertRoundTrip(7, 100, 3);
    }

    @Test
    void roundTripsFullSignedYRange() {
        assertRoundTrip(3, Integer.MIN_VALUE, 12);
        assertRoundTrip(3, Integer.MAX_VALUE, 12);
        assertRoundTrip(3, -1, 12);
    }

    @Test
    void distinctPositionsNeverProduceTheSamePackedValue() {
        long a = UnderwaterSaplingsListener.encodeLocalCoordinate(1, 64, 2);
        long b = UnderwaterSaplingsListener.encodeLocalCoordinate(2, 64, 1);
        long c = UnderwaterSaplingsListener.encodeLocalCoordinate(1, 65, 2);
        assertEquals(false, a == b);
        assertEquals(false, a == c);
    }

    private void assertRoundTrip(int localX, int y, int localZ) {
        long packed = UnderwaterSaplingsListener.encodeLocalCoordinate(localX, y, localZ);
        assertEquals(localX, UnderwaterSaplingsListener.decodeLocalX(packed));
        assertEquals(y, UnderwaterSaplingsListener.decodeY(packed));
        assertEquals(localZ, UnderwaterSaplingsListener.decodeLocalZ(packed));
    }
}
