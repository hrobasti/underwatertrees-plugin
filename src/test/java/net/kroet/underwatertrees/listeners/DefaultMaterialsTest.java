package net.kroet.underwatertrees.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The built-in fallback lists must match what the shipped config.yml enables,
 * since config.yml is the source of truth for defaults.
 */
class DefaultMaterialsTest {

    @Test
    void fallbackSoilsMatchTheEnabledConfigEntries() throws IOException {
        assertEquals(enabledEntries("soils"), UnderwaterSaplingsListener.DEFAULT_SOILS);
    }

    @Test
    void fallbackSaplingsMatchTheEnabledConfigEntries() throws IOException {
        assertEquals(enabledEntries("saplings"), UnderwaterSaplingsListener.DEFAULT_SAPLINGS);
    }

    private static List<String> enabledEntries(String section) throws IOException {
        try (InputStream in = DefaultMaterialsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml not found on the test classpath");
            ConfigurationSection entries = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getConfigurationSection(section);
            assertNotNull(entries, section + " missing from config.yml");
            return entries.getKeys(false).stream().filter(entries::getBoolean).toList();
        }
    }
}
