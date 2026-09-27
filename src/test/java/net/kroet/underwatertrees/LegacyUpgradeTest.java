package net.kroet.underwatertrees;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.kroet.turtlelib.helper.LegacyDataUpgrade;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-time upgrade against real release files: a 1.0.x folder gets fresh
 * files without carried values, and a folder that ran 1.0.x and then 1.1+ is
 * upgraded from its 1.1+ keys while the 1.0.x leftovers are skipped silently.
 */
class LegacyUpgradeTest {
    private static List<String> langFiles;

    @TempDir
    Path dataDir;

    @BeforeAll
    static void listLangFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/lang"))) {
            langFiles = files.map(file -> "lang/" + file.getFileName()).sorted().toList();
        }
    }

    @Test
    void untouched10FolderGetsFreshFilesAndReportsNothing() throws IOException {
        checkUntouched10Folder("1.0");
    }

    @Test
    void untouched101FolderGetsFreshFilesAndReportsNothing() throws IOException {
        checkUntouched10Folder("1.0.1");
    }

    // 1.0.x wrote only the default config.yml and the en_US/de_DE lang files.
    private void checkUntouched10Folder(String release) throws IOException {
        byte[] oldConfig = read("/release-1.0.x/config-" + release + ".yml");
        write("config.yml", oldConfig);
        write("lang/en_US.yml", read("/legacy/1.0.x/lang/en_US.yml"));
        write("lang/de_DE.yml", read("/legacy/1.0.x/lang/de_DE.yml"));

        UnderwaterTreesPlugin.LegacyUpgrade upgrade = upgrade();

        LegacyDataUpgrade.Report report = upgrade.report();
        assertTrue(report.performed());
        assertTrue(upgrade.from10());
        assertEquals(List.of(), report.carried());
        assertEquals(List.of(), report.dropped());
        assertEquals(List.of(), report.customizedFiles());
        assertArrayEquals(oldConfig, Files.readAllBytes(dataDir.resolve("backup-1.x/config.yml")));
        assertTrue(Files.exists(dataDir.resolve("backup-1.x/lang/de_DE.yml")));
        assertArrayEquals(read("/config.yml"), Files.readAllBytes(dataDir.resolve("config.yml")));
        for (String langFile : langFiles) {
            assertArrayEquals(read("/" + langFile), Files.readAllBytes(dataDir.resolve(langFile)), langFile);
        }
    }

    @Test
    void edited10FilesAreReportedButNotCarriedOver() throws IOException {
        checkEdited10Files("1.0");
    }

    @Test
    void edited101FilesAreReportedButNotCarriedOver() throws IOException {
        checkEdited10Files("1.0.1");
    }

    private void checkEdited10Files(String release) throws IOException {
        String config = new String(read("/release-1.0.x/config-" + release + ".yml"), StandardCharsets.UTF_8)
                .replace("update-check: true", "update-check: false");
        write("config.yml", config.getBytes(StandardCharsets.UTF_8));
        write("lang/en_US.yml", read("/legacy/1.0.x/lang/en_US.yml"));
        String german = new String(read("/legacy/1.0.x/lang/de_DE.yml"), StandardCharsets.UTF_8)
                .replace("UnderwaterTrees-Konfiguration neu geladen.", "Eigener Text");
        write("lang/de_DE.yml", german.getBytes(StandardCharsets.UTF_8));

        UnderwaterTreesPlugin.LegacyUpgrade upgrade = upgrade();

        LegacyDataUpgrade.Report report = upgrade.report();
        assertTrue(upgrade.from10());
        assertEquals(List.of(), report.carried());
        assertEquals(Set.of("config.yml", "lang/de_DE.yml"), Set.copyOf(report.customizedFiles()));
    }

    // Built the way 1.1-1.2.x left such a file: copyDefaults merged every missing
    // default into the 1.0.1 file, and the 1.0 keys stayed next to the new ones.
    @Test
    void mixedFolderCarries1xValuesAndSkips10Leftovers() throws IOException, InvalidConfigurationException {
        YamlConfiguration mixed = yaml("/release-1.0.x/config-1.0.1.yml");
        mixed.set("require-water-above", true);
        mixed.set("update-check", false);
        mixed.set("update-interval-hours", 6);
        mixed.set("notify-op-join", false);
        mixed.setDefaults(yaml("/legacy/1.x/config.yml"));
        mixed.options().copyDefaults(true);
        mixed.set("chat-prefix-label", "Trees");
        write("config.yml", mixed.saveToString().getBytes(StandardCharsets.UTF_8));
        // 1.1+ merged its new keys into the active locale only; the other locales
        // kept their 1.0.x content.
        YamlConfiguration english = yaml("/legacy/1.0.x/lang/en_US.yml");
        english.setDefaults(yaml("/legacy/1.x/lang/en_US.yml"));
        english.options().copyDefaults(true);
        write("lang/en_US.yml", english.saveToString().getBytes(StandardCharsets.UTF_8));
        write("lang/de_DE.yml", read("/legacy/1.0.x/lang/de_DE.yml"));

        UnderwaterTreesPlugin.LegacyUpgrade upgrade = upgrade();

        LegacyDataUpgrade.Report report = upgrade.report();
        assertTrue(report.performed());
        assertFalse(upgrade.from10());
        YamlConfiguration result = YamlConfiguration.loadConfiguration(dataDir.resolve("config.yml").toFile());
        assertTrue(result.getBoolean("require_water_above"));
        assertEquals("Trees", result.getString("chat_prefix_label"));
        // Active under 1.2.x as well, where 1.0.x's default had left it enabled.
        assertTrue(result.getBoolean("saplings.MANGROVE_PROPAGULE"));
        assertTrue(result.getBoolean("update_check.enabled"));
        assertEquals(24, result.getInt("update_check.interval_hours"));
        assertTrue(result.getBoolean("update_check.notify_op_join"));
        Stream.concat(report.carried().stream().map(LegacyDataUpgrade.CarriedValue::oldPath),
                report.dropped().stream().map(LegacyDataUpgrade.DroppedValue::oldPath))
                .forEach(path -> assertFalse(isLeftover(path), path + " was carried over or reported"));
        assertTrue(report.carried().stream().anyMatch(value -> value.oldPath().equals("chat-prefix-label")));
        assertEquals(List.of(), report.customizedFiles());
    }

    // A 1.2.x admin's sapling changes survive, and entries that are new since then
    // (POPLAR_SAPLING, PALE_OAK_SAPLING) come with the fresh config.yml.
    @Test
    void release12SaplingChangesAreKeptAndNewDefaultsAdded() throws IOException {
        String release12 = new String(read("/legacy/1.x/config.yml"), StandardCharsets.UTF_8)
                .replace("CHERRY_SAPLING: true", "CHERRY_SAPLING: false")
                .replace("MANGROVE_PROPAGULE: false", "MANGROVE_PROPAGULE: true");
        write("config.yml", release12.getBytes(StandardCharsets.UTF_8));

        UnderwaterTreesPlugin.LegacyUpgrade upgrade = upgrade();

        assertTrue(upgrade.report().performed());
        assertFalse(upgrade.from10());
        YamlConfiguration result = YamlConfiguration.loadConfiguration(dataDir.resolve("config.yml").toFile());
        assertFalse(result.getBoolean("saplings.CHERRY_SAPLING"));
        assertTrue(result.getBoolean("saplings.MANGROVE_PROPAGULE"));
        assertTrue(result.getBoolean("saplings.POPLAR_SAPLING"));
        assertTrue(result.getBoolean("saplings.PALE_OAK_SAPLING"));
    }

    // Running 1.2.x on a 2.0 folder: its copyDefaults and saveConfig add every 1.x
    // key with its 1.x default, so the next 2.0 start upgrades again.
    @Test
    void goingBackTo12AndReturningUpgradesAgainAndKeeps20Values() throws IOException,
            InvalidConfigurationException {
        Files.createDirectories(dataDir.resolve("backup-1.x"));
        YamlConfiguration folder = yaml("/config.yml");
        folder.set("chat_prefix_label", "Trees");
        folder.set("require_water_above", true);
        folder.set("growth_light_override.attempt_chance", 0.5);
        folder.set("update_check.provider", 2);
        folder.set("soils.SAND", true);
        folder.set("soils.MUD", null);
        folder.set("saplings.POPLAR_SAPLING", false);
        folder.setDefaults(yaml("/legacy/1.x/config.yml"));
        folder.options().copyDefaults(true);
        // An edit made while 1.2.x runs, under its old name.
        String written12 = folder.saveToString().replace("chat-prefix-label: UwTrees", "chat-prefix-label: Old");
        assertTrue(written12.contains("chat-prefix-label: Old"));
        write("config.yml", written12.getBytes(StandardCharsets.UTF_8));

        UnderwaterTreesPlugin.LegacyUpgrade upgrade = upgrade();

        LegacyDataUpgrade.Report report = upgrade.report();
        assertTrue(report.performed());
        assertFalse(upgrade.from10());
        assertEquals("backup-1.x-2", report.backupDir().getFileName().toString());
        assertEquals(List.of(), report.customizedFiles());
        YamlConfiguration result = YamlConfiguration.loadConfiguration(dataDir.resolve("config.yml").toFile());
        assertEquals("Trees", result.getString("chat_prefix_label"));
        assertTrue(result.getBoolean("require_water_above"));
        assertEquals(0.5, result.getDouble("growth_light_override.attempt_chance"));
        assertEquals(2, result.getInt("update_check.provider"));
        assertTrue(result.getBoolean("soils.SAND"));
        assertFalse(result.getBoolean("saplings.POPLAR_SAPLING"));
        // 1.2.x wrote the deleted entry back with its 1.x default.
        assertTrue(result.getBoolean("soils.MUD"));
        assertFalse(result.contains("chat-prefix-label"));
        assertFalse(result.contains("update-check"));
    }

    @Test
    void currentFolderIsLeftAlone() throws IOException {
        write("config.yml", read("/config.yml"));

        assertTrue(upgrade().report().notNeeded());
        assertFalse(Files.exists(dataDir.resolve("backup-1.x")));
    }

    // The leftover list must cover every value only 1.0.x had and no value 1.1+
    // read; the markers must tell 1.0.x, 1.1+ and current files apart.
    @Test
    void leftoverKeysAndMarkersMatchTheReleaseFiles() throws IOException, InvalidConfigurationException {
        YamlConfiguration release12 = yaml("/legacy/1.x/config.yml");
        YamlConfiguration current = yaml("/config.yml");
        for (String release : List.of("1.0", "1.0.1")) {
            YamlConfiguration release10 = yaml("/release-1.0.x/config-" + release + ".yml");
            for (String key : release10.getKeys(true)) {
                if (!release10.isConfigurationSection(key)) {
                    boolean readBy1x = release12.isSet(key) && !release12.isConfigurationSection(key);
                    assertEquals(!readBy1x, isLeftover(key), release + ": " + key);
                }
            }
            for (String marker : UnderwaterTreesPlugin.LEGACY_1_0_MARKER_KEYS) {
                assertTrue(release10.isSet(marker), release + " lacks " + marker);
            }
            for (String marker : UnderwaterTreesPlugin.LEGACY_MARKER_KEYS) {
                assertFalse(release10.contains(marker), release + " has the 1.1+ marker " + marker);
            }
        }
        for (String key : release12.getKeys(true)) {
            if (!release12.isConfigurationSection(key)) {
                assertFalse(isLeftover(key), "1.2.x key " + key + " is on the leftover list");
            }
        }
        for (String marker : UnderwaterTreesPlugin.LEGACY_1_0_MARKER_KEYS) {
            assertFalse(release12.contains(marker) || current.contains(marker), marker);
        }
    }

    private static boolean isLeftover(String path) {
        return Arrays.stream(UnderwaterTreesPlugin.LEGACY_1_0_LEFTOVER_KEYS).anyMatch(path::equals);
    }

    private UnderwaterTreesPlugin.LegacyUpgrade upgrade() {
        return UnderwaterTreesPlugin.upgradeFrom1x(dataDir.toFile(),
                path -> LegacyUpgradeTest.class.getResourceAsStream("/" + path), null, langFiles);
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = LegacyUpgradeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not found on the test classpath");
            return in.readAllBytes();
        }
    }

    private static YamlConfiguration yaml(String resource) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(new String(read(resource), StandardCharsets.UTF_8));
        return yaml;
    }

    private void write(String path, byte[] content) throws IOException {
        Path target = dataDir.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }
}
