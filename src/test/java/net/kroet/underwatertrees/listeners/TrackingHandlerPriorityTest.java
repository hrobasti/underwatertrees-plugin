package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

/**
 * Tracking follows only placements and breaks that stand: a protection plugin
 * that cancels one must not leave a sapling untracked or a phantom entry.
 */
class TrackingHandlerPriorityTest {

    private static void assertRunsLastOnUncancelledEvents(Method handler) {
        EventHandler annotation = handler.getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, annotation.priority(), handler.getName());
        assertTrue(annotation.ignoreCancelled(), handler.getName());
    }

    @Test
    void breakIsTrackedAfterEveryOtherPlugin() throws NoSuchMethodException {
        assertRunsLastOnUncancelledEvents(
                UnderwaterSaplingsListener.class.getMethod("onSaplingBreak", BlockBreakEvent.class));
    }

    @Test
    void placementIsTrackedAfterEveryOtherPlugin() throws NoSuchMethodException {
        assertRunsLastOnUncancelledEvents(
                UnderwaterSaplingsListener.class.getMethod("onSaplingPlaced", BlockPlaceEvent.class));
    }
}
