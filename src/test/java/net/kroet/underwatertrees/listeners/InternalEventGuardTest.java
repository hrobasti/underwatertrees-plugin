package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InternalEventGuardTest {
    private final List<LogRecord> records = new ArrayList<>();
    private InternalEventGuard guard;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        guard = new InternalEventGuard(logger, "BlockPlaceEvent", "Underwater planting");
    }

    @Test
    void passesTheEventResultThrough() {
        assertTrue(guard.allows(() -> true));
        assertFalse(guard.allows(() -> false));
        assertEquals(List.of(), records);
    }

    @Test
    void linkageErrorDeniesAndWarnsOncePerRun() {
        assertFalse(guard.allows(() -> {
            throw new NoSuchMethodError("BlockPlaceEvent.<init>");
        }));
        assertFalse(guard.allows(() -> {
            throw new NoClassDefFoundError("org/bukkit/event/block/BlockPlaceEvent");
        }));

        assertEquals(1, records.size());
        LogRecord warning = records.get(0);
        assertEquals(Level.WARNING, warning.getLevel());
        assertTrue(warning.getMessage().contains("NoSuchMethodError"), warning.getMessage());
        assertTrue(warning.getMessage().contains("Paper update"), warning.getMessage());
        assertEquals(null, warning.getThrown());
    }

    // Anything else is a bug, not a changed internal, so it stays visible.
    @Test
    void otherExceptionsAreNotSwallowed() {
        assertThrows(IllegalStateException.class, () -> guard.allows(() -> {
            throw new IllegalStateException("bug");
        }));
        assertEquals(List.of(), records);
    }
}
