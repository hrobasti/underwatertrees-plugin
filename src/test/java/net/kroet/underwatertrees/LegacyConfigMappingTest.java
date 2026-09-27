package net.kroet.underwatertrees;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Every setting of the 1.x default config.yml must map, through the rename
 * table, onto a key of the current config.yml, so an upgrade from 1.x can carry
 * each customized value over.
 */
class LegacyConfigMappingTest {

    // Free-form maps: any material key inside them is valid.
    private static final Set<String> OPEN_SECTIONS = Set.of("soils", "saplings");

    private static YamlConfiguration load(String resource) throws IOException {
        try (InputStream in = LegacyConfigMappingTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "Missing resource " + resource);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    // Same segment-wise derivation as ConfigKeyMigrator.
    private static Map<String, String> segmentRenames(Map<String, String> oldToNew) {
        Map<String, String> renames = new LinkedHashMap<>();
        oldToNew.forEach((oldPath, newPath) -> {
            String[] oldParts = oldPath.split("[.]");
            String[] newParts = newPath.split("[.]");
            if (oldParts.length != newParts.length) {
                return;
            }
            for (int i = 0; i < oldParts.length; i++) {
                if (!oldParts[i].equals(newParts[i])) {
                    renames.putIfAbsent(oldParts[i], newParts[i]);
                }
            }
        });
        return renames;
    }

    private static String rename(String path, Map<String, String> renames) {
        String[] parts = path.split("[.]");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = renames.getOrDefault(parts[i], parts[i]);
        }
        return String.join(".", parts);
    }

    @Test
    void everyLegacySettingMapsOntoACurrentKey() throws IOException {
        YamlConfiguration legacy = load("/legacy/1.x/config.yml");
        YamlConfiguration current = load("/config.yml");
        Map<String, String> renames = segmentRenames(UnderwaterTreesPlugin.LEGACY_CONFIG_KEY_MIGRATIONS);

        List<String> unmapped = new ArrayList<>();
        Map<String, String> targets = new HashMap<>();
        for (String oldPath : legacy.getKeys(true)) {
            if (legacy.isConfigurationSection(oldPath)) {
                continue;
            }
            String newPath = rename(oldPath, renames);
            String section = newPath.contains(".") ? newPath.substring(0, newPath.indexOf('.')) : "";
            boolean mapped = OPEN_SECTIONS.contains(section)
                    ? current.isConfigurationSection(section)
                    : current.isSet(newPath) && !current.isConfigurationSection(newPath);
            if (!mapped) {
                unmapped.add(oldPath + " -> " + newPath);
            }
            String clash = targets.put(newPath, oldPath);
            assertTrue(clash == null, "Both " + clash + " and " + oldPath + " map to " + newPath);
        }
        assertTrue(unmapped.isEmpty(), "1.x settings without a current key: " + unmapped);
    }

    // A marker in the current config.yml would re-run the upgrade on every start;
    // one missing from the 1.x file would never trigger it.
    @Test
    void markerKeysOnlyExistIn1xConfig() throws IOException {
        YamlConfiguration legacy = load("/legacy/1.x/config.yml");
        YamlConfiguration current = load("/config.yml");
        for (String marker : UnderwaterTreesPlugin.LEGACY_MARKER_KEYS) {
            assertTrue(legacy.contains(marker), marker + " missing from the 1.x config.yml");
            assertFalse(current.contains(marker), marker + " also exists in the current config.yml");
        }
    }
}
