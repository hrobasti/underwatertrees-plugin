package net.kroet.underwatertrees;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import net.kroet.turtlelib.helper.LocaleFileChecker;
import org.junit.jupiter.api.Test;

/**
 * Checks every bundled lang/*.yml against en_US.yml (keys, tags, strict
 * MiniMessage parse, untranslated values) and matches the keys against those
 * the code uses.
 */
class LocaleConsistencyTest {

    // Placeholders the plugin resolves; any other non-standard tag would be
    // printed literally in-game.
    private static final String[] PLACEHOLDERS = {"backup", "changes", "code", "count", "current", "current_ver",
            "entries", "error", "file", "key", "keys", "label", "latest_ver", "list", "mc_version", "new", "old", "plugin",
            "provider", "required_server", "server_name", "subcommands", "supported_versions", "url", "value",
            "version"};

    @Test
    void bundledLocalesAreConsistentAndMatchTheCode() {
        LocaleFileChecker.Result result = LocaleFileChecker.builder(Path.of("src/main/resources/lang"))
                .placeholders(PLACEHOLDERS)
                .scanSources(Path.of("src/main/java"))
                // Old key names, only used to rename keys in existing files.
                .renames(UnderwaterTreesPlugin.LEGACY_LANG_KEY_MIGRATIONS)
                // Pure placeholders and names, and "Version", which is also German.
                .identicalAllowed("command.version", "log.reload_changes_line", "log.reload_changes_locale_line",
                        "log.stats_line", "log.update_provider_ok")
                .build()
                .check();
        assertFalse(result.hasProblems(), "Locale problems:\n" + result.describeProblems());
    }
}
