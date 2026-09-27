package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kroet.underwatertrees.listeners.TrackedSaplings.Position;
import org.junit.jupiter.api.Test;

class TrackedSaplingsTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    @Test
    void cyclesOfLimitedSizeVisitEverySaplingInTurn() {
        TrackedSaplings tracked = new TrackedSaplings();
        for (int i = 0; i < 100; i++) {
            tracked.add(new Position(WORLD, i, 60, 0));
        }

        Set<Position> visited = new HashSet<>();
        for (int cycle = 0; cycle < 2; cycle++) {
            for (int i = 0; i < 64; i++) {
                visited.add(tracked.next());
            }
        }

        // Two 64-slot cycles over 100 saplings reach all of them.
        assertEquals(100, visited.size());
        assertEquals(100, tracked.size());
    }

    @Test
    void removingDuringACycleKeepsTheRestInOrder() {
        TrackedSaplings tracked = new TrackedSaplings();
        Position a = new Position(WORLD, 0, 60, 0);
        Position b = new Position(WORLD, 1, 60, 0);
        Position c = new Position(WORLD, 2, 60, 0);
        tracked.add(a);
        tracked.add(b);
        tracked.add(c);

        assertEquals(a, tracked.next());
        tracked.remove(a);

        assertEquals(b, tracked.next());
        assertEquals(c, tracked.next());
        assertEquals(b, tracked.next());
    }

    @Test
    void removeChunkDropsOnlyThatChunkInThatWorld() {
        TrackedSaplings tracked = new TrackedSaplings();
        Position inChunk = new Position(WORLD, 17, 60, -1);
        Position sameChunk = new Position(WORLD, 31, 40, -16);
        Position nextChunk = new Position(WORLD, 32, 60, -1);
        Position otherWorld = new Position(OTHER_WORLD, 17, 60, -1);
        tracked.add(inChunk);
        tracked.add(sameChunk);
        tracked.add(nextChunk);
        tracked.add(otherWorld);

        tracked.removeChunk(WORLD, 1, -1);

        assertEquals(2, tracked.size());
        assertFalse(tracked.remove(inChunk));
        assertFalse(tracked.remove(sameChunk));
        assertTrue(tracked.remove(nextChunk));
        assertTrue(tracked.remove(otherWorld));
    }

    @Test
    void addingTwiceTracksOnce() {
        TrackedSaplings tracked = new TrackedSaplings();
        Position p = new Position(WORLD, 0, 60, 0);

        assertTrue(tracked.add(p));
        assertFalse(tracked.add(p));
        assertEquals(1, tracked.size());

        tracked.remove(p);
        assertTrue(tracked.isEmpty());
        assertNull(tracked.next());
    }
}
