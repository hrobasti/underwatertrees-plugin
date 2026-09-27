package net.kroet.underwatertrees;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.ConfigDefaultsInserter;
import net.kroet.turtlelib.helper.ConfigKeyMigrator;
import net.kroet.turtlelib.helper.ConfigWatcher;
import net.kroet.turtlelib.helper.FileChangeTracker;
import net.kroet.turtlelib.helper.LangLoader;
import net.kroet.turtlelib.helper.LegacyDataUpgrade;
import net.kroet.turtlelib.helper.MessageService;
import net.kroet.turtlelib.helper.ServerMatcher;
import net.kroet.turtlelib.helper.StartupBanner;
import net.kroet.turtlelib.helper.UpdateChecker;
import net.kroet.underwatertrees.commands.UnderwaterTreesBasicCommand;
import net.kroet.underwatertrees.listeners.UnderwaterSaplingsListener;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class UnderwaterTreesPlugin extends JavaPlugin {

    private static final String STARTUP_BANNER_RESOURCE = "banner.txt";
    private static final String REQUIRED_SERVER_BRAND = "Paper";
    // Accepts the whole 26.3.x series, not just the literal "26.3" - a Mojang
    // hotfix bumping the patch segment shouldn't need a new UnderwaterTrees
    // release.
    private static final String SUPPORTED_MINOR_SERIES = "26.3";
    private static final String SUPPORTED_VERSION_LABEL = "26.3.x";
    private static final ServerMatcher.IncompatibleAction INCOMPATIBLE_SERVER_ACTION = ServerMatcher.IncompatibleAction.WARN_AND_CONTINUE;
    // Config/lang keys use snake_case; ConfigKeyMigrator renames kebab-case
    // keys in existing files in place, and the 1.x upgrade maps values with
    // it. Package-private for LegacyConfigMappingTest.
    static final Map<String, String> LEGACY_CONFIG_KEY_MIGRATIONS = Map.ofEntries(
            Map.entry("chat-prefix-label", "chat_prefix_label"),
            Map.entry("startup-banner-enabled", "startup_banner_enabled"),
            Map.entry("config-watch-enabled", "config_watch_enabled"),
            Map.entry("config-watch-interval-seconds", "config_watch_interval_seconds"),
            Map.entry("log-stats", "log_stats"),
            Map.entry("log-detail", "log_detail"),
            Map.entry("metrics-enabled", "metrics_enabled"),
            Map.entry("update-check.enabled", "update_check.enabled"),
            // Matches Timberella's naming for the same setting ("provider").
            Map.entry("update-check.sources", "update_check.provider"),
            Map.entry("update_check.sources", "update_check.provider"),
            Map.entry("update-check.interval-hours", "update_check.interval_hours"),
            Map.entry("update-check.include-prereleases", "update_check.include_prereleases"),
            Map.entry("update-check.filter-by-server-version", "update_check.filter_by_server_version"),
            Map.entry("update-check.notify-console", "update_check.notify_console"),
            Map.entry("update-check.notify-console-always-shown", "update_check.notify_console_always_shown"),
            Map.entry("update-check.notify-op-join", "update_check.notify_op_join"),
            Map.entry("require-water-above", "require_water_above"),
            Map.entry("protect-underwater-saplings", "protect_underwater_saplings"),
            Map.entry("growth-light-override.minimum-level", "growth_light_override.minimum_level"),
            Map.entry("growth-light-override.attempt-period-seconds", "growth_light_override.attempt_period_seconds"),
            Map.entry("growth-light-override.attempt-chance", "growth_light_override.attempt_chance"),
            // Whole-section move for the master switch + any not-yet-listed sub-key.
            Map.entry("growth-light-override", "growth_light_override"));
    // Free-form material maps: admins add and delete entries there.
    private static final String[] OPEN_CONFIG_SECTIONS = {"soils", "saplings"};
    // Keys only a 1.1-1.2.x config.yml has; any of them triggers the clean upgrade.
    // 1.0.x has none (its update-check is a single switch). Package-private for
    // LegacyConfigMappingTest.
    static final String[] LEGACY_MARKER_KEYS = {"update-check.enabled", "chat-prefix-label", "growth-light-override"};
    // Keys only a 1.0.x config.yml has; without a 1.1+ marker they mark a 1.0.x
    // folder, which gets fresh files without carrying anything over.
    static final String[] LEGACY_1_0_MARKER_KEYS = {"auto-reload", "update-interval-hours", "update-sources"};
    // The 1.2.x default files, the baseline for carrying admin changes over.
    private static final String LEGACY_1X_DEFAULTS_ROOT = "legacy/1.x/";
    // The 1.0.1 default files (1.0 had the same, minus one config key), so
    // untouched files from 1.0.x aren't reported as customized.
    private static final String LEGACY_1_0_DEFAULTS_ROOT = "legacy/1.0.x/";
    // 1.0.x keys that 1.1-1.2.x no longer read but kept in the file; the 1.x
    // upgrade skips them silently. "update-check" matches only the 1.0 switch.
    static final String[] LEGACY_1_0_LEFTOVER_KEYS = {"auto-reload", "update-check", "update-interval-hours",
            "update-sources", "include-prereleases", "filter-by-server-version", "notify-console", "notify-op-join"};
    // Package-private for LegacyLangMigrationTest.
    static final Map<String, String> LEGACY_LANG_KEY_MIGRATIONS = Map.ofEntries(
            Map.entry("plugin.language-set", "plugin.language_set"),
            Map.entry("command.no-permission", "command.no_permission"),
            Map.entry("warn.unsupported-server-version", "warn.unsupported_server_version"));
    private static final String COMMAND_NAME = "underwatertrees";
    // Matches chat_prefix_label in the shipped config.yml.
    private static final String DEFAULT_PREFIX_LABEL = "UwTrees";
    private static final String PREFIX_FORMAT = "<green>[<prefix_label>]</green>";
    private UnderwaterSaplingsListener saplingsListener;
    private MessageService messages;
    private ConfigWatcher configWatcher;
    private FileChangeTracker fileChangeTracker;
    private Metrics metrics;
    private volatile UpdateChecker updateChecker;
    private int updateTaskId = -1;
    // Named to match Timberella's TimberellaPlugin.pendingUpdateInfo, same role:
    // null unless the last check found an update, feeding the op-join chat hint.
    private volatile UpdateChecker.UpdateInfo pendingUpdateInfo;
    private volatile boolean announceNextUpdateSummary = true;
    // Providers whose failure the console has already shown since the last start,
    // reload or successful fetch; main thread only.
    private final Set<UpdateChecker.Provider> reportedProviderFailures = EnumSet.noneOf(UpdateChecker.Provider.class);
    private ServerMatcher serverMatcher;
    private FileConfiguration loadedConfig;
    // Set while config.yml wasn't valid YAML at startup: the plugin runs on the
    // bundled defaults, without bStats and update checks, until a reload succeeds.
    private boolean runningOnDefaults;

    @Override
    public void onEnable() {
        LegacyUpgrade upgrade = upgradeFrom1x();
        saveDefaultConfig();
        String configError = ConfigWatcher.describeYamlError(configFile());
        runningOnDefaults = configError != null;
        // A config.yml that isn't valid YAML stays byte-identical until it is fixed.
        List<String> addedDefaults = runningOnDefaults ? List.of() : ensureConfigDefaults();

        if (getConfig().getBoolean("startup_banner_enabled", true)) {
            logStartupBanner();
        }

        String lang = getConfig().getString("language", "en_US");
        messages = new MessageService(this, PREFIX_FORMAT, DEFAULT_PREFIX_LABEL);
        messages.setLegacyKeyMigrations(LEGACY_LANG_KEY_MIGRATIONS);
        // An old key left next to its renamed key (e.g. after running 1.2.x again)
        // is removed while it still holds the 1.2.x default text.
        messages.setLegacyDefaultsRoot(LEGACY_1X_DEFAULTS_ROOT);
        messages.load(lang);
        applyChatPrefixLabel();
        setupServerMatcher();
        if (runningOnDefaults) {
            messages.log(getLogger(), Level.WARNING, "log.config_invalid_startup", Map.of("error", configError));
        }
        logAddedDefaults(addedDefaults);
        getLogger().info(messages.plain("plugin.language_set", Map.of("code", messages.getLanguage())));
        logUpgradeReport(upgrade);

        saplingsListener = new UnderwaterSaplingsListener(this);
        Bukkit.getPluginManager().registerEvents(saplingsListener, this);
        registerUpdateNotifier();

        registerCommand(COMMAND_NAME, "UnderwaterTrees commands", new UnderwaterTreesBasicCommand(this, COMMAND_NAME));

        initMetrics();

        initUpdateChecker();

        initializeConfigWatcher();
        if (configWatcher != null) {
            configWatcher.start();
        }

        fileChangeTracker = FileChangeTracker.builder(getDataFolder(), getLogger())
                .yamlFile("config.yml")
                .folder("lang", ".yml")
                .build();
        // On defaults, the first snapshot waits for the reload that fixes config.yml,
        // so that reload doesn't list every setting as changed.
        if (!runningOnDefaults) {
            fileChangeTracker.compareAndUpdate();
        }

        getLogger().info(messages.plain("plugin.enabled"));
    }

    @Override
    public void onDisable() {
        shutdownMetrics();
        stopUpdateCheck();
        // Stops TurtleLib's HTTP client and threads, so none outlive a /reload.
        UpdateChecker.shutdown();
        if (configWatcher != null) {
            configWatcher.stop();
            configWatcher = null;
        }
        if (saplingsListener != null) {
            saplingsListener.shutdown();
        }
        if (messages == null) {
            getLogger().info("UnderwaterTrees plugin disabled.");
        } else {
            getLogger().info(messages.plain("plugin.disabled"));
        }
    }

    /** Outcome of the one-time upgrade; {@code from10} marks a 1.0.x folder. */
    record LegacyUpgrade(LegacyDataUpgrade.Report report, boolean from10) {
    }

    private LegacyUpgrade upgradeFrom1x() {
        List<String> langFiles = new ArrayList<>();
        for (String locale : LangLoader.getBundledLocales(this)) {
            String path = "lang/" + locale + ".yml";
            if (getClassLoader().getResource(path) != null) {
                langFiles.add(path);
            }
        }
        return upgradeFrom1x(getDataFolder(), this::getResource, getLogger(), langFiles);
    }

    // Moves a 1.x data folder to backup-1.x and writes fresh files that keep only
    // the values the admin had changed from the 1.x defaults; a 1.0.x folder gets
    // fresh files only. Package-private for LegacyUpgradeTest.
    static LegacyUpgrade upgradeFrom1x(File dataFolder, Function<String, InputStream> resources, Logger logger,
            List<String> langFiles) {
        LegacyDataUpgrade.Report report = LegacyDataUpgrade.builder(dataFolder, resources, logger)
                .markerKeys(LEGACY_MARKER_KEYS)
                .backupDirName("backup-1.x")
                .legacyDefaultsRoot(LEGACY_1X_DEFAULTS_ROOT)
                .olderDefaultsRoot(LEGACY_1_0_DEFAULTS_ROOT)
                .freshResources(langFiles)
                .carryOverValues("config.yml", LEGACY_CONFIG_KEY_MIGRATIONS, OPEN_CONFIG_SECTIONS)
                .ignoreOldKeys("config.yml", LEGACY_1_0_LEFTOVER_KEYS)
                .run();
        if (!report.notNeeded()) {
            return new LegacyUpgrade(report, false);
        }
        LegacyDataUpgrade.Report from10 = LegacyDataUpgrade.builder(dataFolder, resources, logger)
                .markerKeys(LEGACY_1_0_MARKER_KEYS)
                .backupDirName("backup-1.x")
                .olderDefaultsRoot(LEGACY_1_0_DEFAULTS_ROOT)
                .freshResources("config.yml")
                .freshResources(langFiles)
                .run();
        return new LegacyUpgrade(from10, from10.performed());
    }

    // Technical errors are already logged by LegacyDataUpgrade itself.
    private void logUpgradeReport(LegacyUpgrade upgrade) {
        LegacyDataUpgrade.Report report = upgrade.report();
        if (!report.performed())
            return;
        log(upgrade.from10() ? "log.upgrade_from_1_0" : "log.upgrade_header",
                Map.of("plugin", getName(), "backup", displayPath(report.backupDir())));
        for (var value : report.carried()) {
            log("log.upgrade_kept", Map.of("old", value.oldPath(), "new", value.newPath(), "value",
                    value.value()));
        }
        for (var entry : report.removed()) {
            log("log.upgrade_removed", Map.of("key", entry.newPath()));
        }
        for (var value : report.dropped()) {
            // The setting itself still exists when only its value didn't fit.
            boolean settingGone = value.reason() == LegacyDataUpgrade.SkipReason.UNKNOWN_OLD_KEY
                    || value.reason() == LegacyDataUpgrade.SkipReason.NO_NEW_KEY;
            log(settingGone ? "log.upgrade_dropped" : "log.upgrade_dropped_invalid",
                    Map.of("key", value.oldPath(), "value", value.value()));
        }
        for (var list : report.missingListEntries()) {
            log("log.upgrade_new_entries", Map.of("list", list.path(), "entries",
                    String.join(", ", list.entries())));
        }
        for (String file : report.customizedFiles()) {
            log("log.upgrade_replaced_file", Map.of("file", file));
        }
        for (String file : report.filesWithoutBaseline()) {
            // Files the jar ships were written fresh, not left in the backup only.
            if (getClassLoader().getResource(file) == null) {
                log("log.upgrade_backup_only", Map.of("file", file));
            }
        }
    }

    // Relative to the server folder when possible, e.g.
    // plugins/UnderwaterTrees/backup-1.x.
    private String displayPath(Path path) {
        Path root = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        Path shown = absolute.startsWith(root) ? root.relativize(absolute) : absolute;
        return shown.toString().replace('\\', '/');
    }

    private void log(String key, Map<String, String> placeholders) {
        messages.log(getLogger(), Level.INFO, key, placeholders);
    }

    public MessageService getMessages() {
        return messages;
    }

    private File configFile() {
        return new File(getDataFolder(), "config.yml");
    }

    @Override
    public FileConfiguration getConfig() {
        if (loadedConfig == null) {
            reloadConfig();
        }
        return loadedConfig;
    }

    // Loads like JavaPlugin, but a missing or invalid config.yml gives the bundled
    // file itself (its soils and saplings count as set) without Bukkit's SEVERE
    // stack trace; the plugin checks the file first and logs its own warning.
    @Override
    public void reloadConfig() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile());
        } catch (IOException | InvalidConfigurationException ex) {
            config = loadBundledConfig();
        }
        config.setDefaults(loadBundledConfig());
        loadedConfig = config;
    }

    private YamlConfiguration loadBundledConfig() {
        YamlConfiguration bundled = new YamlConfiguration();
        try (InputStream in = getResource("config.yml")) {
            if (in != null) {
                bundled.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException ex) {
            getLogger().warning("Could not read bundled config.yml: " + ex.getMessage());
        }
        return bundled;
    }

    // Shared by /underwatertrees reload and the config watcher. Returns null once
    // reloaded; if config.yml isn't valid YAML, nothing is reloaded or rewritten
    // and the error is returned.
    public String reloadAndMergeConfig() {
        return reloadAndMergeConfig(false);
    }

    // external: started by the config watcher, which announces the reload in the
    // console before the list of changes.
    private String reloadAndMergeConfig(boolean external) {
        String configError = ConfigWatcher.describeYamlError(configFile());
        if (configError != null) {
            messages.log(getLogger(), Level.WARNING, "log.config_invalid_reload", Map.of("error", configError));
            if (configWatcher != null) {
                // Otherwise the watcher would report this same content once more.
                configWatcher.refreshBaseline();
            }
            return configError;
        }
        runningOnDefaults = false;
        reloadConfig();
        var addedDefaults = ensureConfigDefaults();
        messages.reload(getConfig());
        applyChatPrefixLabel();
        // Not re-run here: the server version can't change while the plugin runs, so
        // re-checking it on every reload would only repeat the warning (or, with
        // ERROR_AND_DISABLE, disable the plugin mid-reload).
        logAddedDefaults(addedDefaults);
        getLogger().info(messages.plain("plugin.language_set", Map.of("code", messages.getLanguage())));
        if (saplingsListener != null) {
            saplingsListener.applyConfig(getConfig());
        }
        initMetrics();
        initUpdateChecker();
        refreshConfigWatcherBaseline();
        if (external) {
            messages.log(getLogger(), Level.INFO, "log.config_reloaded_external");
        }
        logFileChanges();
        return null;
    }

    private void logFileChanges() {
        if (fileChangeTracker == null) {
            return;
        }
        FileChangeTracker.Changes changes = fileChangeTracker.compareAndUpdate();
        if (changes.firstSnapshot()) {
            return;
        }
        if (changes.isEmpty()) {
            messages.log(getLogger(), Level.INFO, "log.reload_changes_none");
            return;
        }
        // A file that couldn't be read has already been warned about by the tracker.
        if (!changes.hasListedChanges()) {
            return;
        }
        messages.log(getLogger(), Level.INFO, "log.reload_changes_header");
        for (FileChangeTracker.FileKeyChanges file : changes.keyChanges()) {
            log("log.reload_changes_line", Map.of("file", file.file(), "changes", file.describe()));
        }
        for (String file : changes.allChangedFiles()) {
            log("log.reload_changes_locale_line", Map.of("file", file));
        }
    }

    private void initMetrics() {
        boolean enabled = !runningOnDefaults && getConfig().getBoolean("metrics_enabled", true);
        if (!enabled) {
            shutdownMetrics();
            return;
        }
        if (metrics != null) {
            return;
        }
        metrics = new Metrics(this, 28005);
        metrics.addCustomChart(new SimplePie("language", () -> messages.getLanguage()));
        metrics.addCustomChart(new SingleLineChart("sapling_count", saplingsListener::getSaplingCount));
        metrics.addCustomChart(new SingleLineChart("soil_block_count", saplingsListener::getSoilCount));
    }

    private void shutdownMetrics() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }

    private void logStartupBanner() {
        StartupBanner.builder(this)
                .resource(STARTUP_BANNER_RESOURCE)
                .color("<green>")
                .build()
                .send();
    }

    private void setupServerMatcher() {
        if (messages == null) {
            serverMatcher = null;
            return;
        }
        serverMatcher = ServerMatcher.builder(this)
                .allowMinorSeries(SUPPORTED_MINOR_SERIES)
                .incompatibleAction(INCOMPATIBLE_SERVER_ACTION)
                .onMismatch(this::handleServerMismatch)
                .build();
        serverMatcher.enforce();
    }

    private void handleServerMismatch(ServerMatcher.MatchResult result) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("required_server", REQUIRED_SERVER_BRAND);
        placeholders.put("supported_versions", SUPPORTED_VERSION_LABEL);
        placeholders.put("server_name", result.serverName() != null ? result.serverName() : "unknown");
        placeholders.put("mc_version", result.minecraftVersion() != null ? result.minecraftVersion() : "unknown");

        if (messages != null) {
            // The logger already prefixes the plugin name, so the message has no <prefix>.
            messages.log(getLogger(), Level.WARNING, "warn.unsupported_server_version", placeholders);
        } else {
            getLogger().warning("UnderwaterTrees officially supports " + REQUIRED_SERVER_BRAND
                    + " " + SUPPORTED_VERSION_LABEL + ". Detected "
                    + result.serverName() + " " + result.minecraftVersion() + '.');
        }
    }

    // Also clears the previous checker's pending hint, so a reload starts clean.
    private void initUpdateChecker() {
        stopUpdateCheck();
        if (runningOnDefaults || !readUpdateBoolean("enabled", true)) {
            return;
        }

        int providerMode = Math.max(0, Math.min(2, readUpdateInt("provider", 0)));
        boolean includePrereleases = readUpdateBoolean("include_prereleases", false);
        boolean filterByServerVersion = readUpdateBoolean("filter_by_server_version", true);
        updateChecker = new UpdateChecker(this, providerMode, includePrereleases, filterByServerVersion, "underwatertrees", "hro_basti/underwatertrees");
        // A failed provider is reported once, translated, by logProviderResult.
        updateChecker.setLogFailures(false);

        requestUpdateCheck(true);

        long hours = Math.max(1, readUpdateLong("interval_hours", 24L));
        long periodTicks = hours * 60L * 60L * 20L;
        updateTaskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(this, () -> requestUpdateCheck(false), periodTicks, periodTicks)
                .getTaskId();
    }

    private void stopUpdateCheck() {
        if (updateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(updateTaskId);
            updateTaskId = -1;
        }
        updateChecker = null;
        pendingUpdateInfo = null;
        announceNextUpdateSummary = true;
        reportedProviderFailures.clear();
    }

    private void requestUpdateCheck(boolean logConsole) {
        UpdateChecker checker = updateChecker;
        if (checker == null) {
            return;
        }
        checker.checkAsync()
                .thenAccept(info -> {
                    if (!isEnabled()) {
                        return;
                    }
                    // handleUpdateInfo reads config and messages, so it runs on the main
                    // thread; a result from a checker a reload has since replaced is dropped.
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (checker == updateChecker) {
                            handleUpdateInfo(info, logConsole);
                        }
                    });
                })
                .exceptionally(ex -> {
                    // Network errors arrive as ProviderResult; only unexpected bugs land here.
                    getLogger().log(Level.WARNING, "Update check failed", ex);
                    return null;
                });
    }

    private void handleUpdateInfo(UpdateChecker.UpdateInfo info, boolean logConsole) {
        if (info == null) {
            return;
        }
        boolean notifyConsole = readUpdateBoolean("notify_console", true);
        boolean alwaysShow = readUpdateBoolean("notify_console_always_shown", false);
        boolean wantsLog = notifyConsole && (announceNextUpdateSummary || logConsole || info.hasUpdate());
        boolean summaryLogged = wantsLog && (info.hasUpdate() || alwaysShow);
        if (summaryLogged) {
            logUpdateSummary(info);
            announceNextUpdateSummary = false;
        }
        // Without a summary, a provider's first failure still gets one line; the
        // summary's own line counts as shown.
        List<UpdateChecker.ProviderResult> firstFailures = firstFailures(reportedProviderFailures, info.providers());
        if (notifyConsole && !summaryLogged) {
            for (UpdateChecker.ProviderResult result : firstFailures) {
                messages.log(getLogger(), Level.WARNING, "log.update_provider_failed", Map.of("provider",
                        result.provider().displayName(), "error", result.errorMessage()));
            }
        }
        // Feeds notifyPlayerAboutUpdate()'s op-join chat hint - the actual admin
        // notification, not the console logging above. info.latestVersion() is
        // already the best version across providers, so no need to re-derive one here.
        pendingUpdateInfo = info.hasUpdate() ? info : null;
    }

    // Registered on every enable: Bukkit drops all of a plugin's listeners on
    // disable. The hint stays silent while pendingUpdateInfo is null.
    private void registerUpdateNotifier() {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                notifyPlayerAboutUpdate(event.getPlayer());
            }
        }, this);
    }

    private void notifyPlayerAboutUpdate(Player player) {
        if (!readUpdateBoolean("notify_op_join", true)) {
            return;
        }
        UpdateChecker.UpdateInfo info = pendingUpdateInfo;
        if (info == null) {
            return;
        }
        if (!(player.isOp() || player.hasPermission("underwatertrees.update.notify"))) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("latest_ver", info.latestVersion());
        placeholders.put("current_ver", info.currentVersion());
        player.sendMessage(messages.format("update.available", placeholders));
        // The details line points to the server log, which has none without it.
        if (readUpdateBoolean("notify_console", true)) {
            player.sendMessage(messages.component("update.details"));
        }
    }

    private void applyChatPrefixLabel() {
        if (messages == null) {
            return;
        }
        messages.setPrefixLabel(resolveChatPrefixLabel());
    }

    private String resolveChatPrefixLabel() {
        String label = getConfig().getString("chat_prefix_label", DEFAULT_PREFIX_LABEL);
        return label == null || label.isBlank() ? DEFAULT_PREFIX_LABEL : label;
    }

    private void initializeConfigWatcher() {
        if (configWatcher != null) {
            return;
        }
        configWatcher = ConfigWatcher.builder(this)
                .file(configFile())
                // Initial values only; the background poll never reads getConfig() itself
                // (not thread-safe) - refreshConfigWatcherBaseline() pushes fresh values
                // on every reload instead.
                .enabled(readConfigWatchEnabled())
                .intervalSeconds(resolveConfigWatchIntervalSeconds())
                .onChange(this::handleExternalConfigChange)
                .onSkipped(this::handleSkippedConfigChange)
                .build();
    }

    private long resolveConfigWatchIntervalSeconds() {
        long configured = getConfig().getLong("config_watch_interval_seconds", 5L);
        return Math.max(1L, configured);
    }

    private boolean readConfigWatchEnabled() {
        return getConfig().getBoolean("config_watch_enabled", true);
    }

    private void handleExternalConfigChange() {
        try {
            reloadAndMergeConfig(true);
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Config watcher callback failed", ex);
        }
    }

    // A deleted or emptied config.yml keeps the current settings; the command
    // reload or a restart writes the file again.
    private void handleSkippedConfigChange(ConfigWatcher.SkipReason reason) {
        messages.log(getLogger(), Level.WARNING, reason == ConfigWatcher.SkipReason.MISSING
                ? "log.config_missing_external"
                : "log.config_empty_external");
    }

    private void refreshConfigWatcherBaseline() {
        if (configWatcher != null) {
            configWatcher.setEnabled(readConfigWatchEnabled());
            configWatcher.setIntervalSeconds(resolveConfigWatchIntervalSeconds());
            configWatcher.refreshBaseline();
        }
    }

    // Adds new default keys to the existing config and returns their paths;
    // also migrates kebab-case keys. Callers log the result via
    // logAddedDefaults() once messages are (re)loaded.
    private List<String> ensureConfigDefaults() {
        File configFile = configFile();
        if (!configFile.exists()) {
            saveDefaultConfig();
            getLogger().info("Created default file: config.yml");
            reloadConfig();
        }
        // Rename first, directly in the file (keeps comments/formatting, see
        // ConfigKeyMigrator), then reload so getConfig() reflects the renamed file
        // immediately. An old key still at its 1.2.x default next to its new name goes.
        boolean rewritten = false;
        try (InputStream legacyDefaults = getResource(LEGACY_1X_DEFAULTS_ROOT + "config.yml")) {
            rewritten = ConfigKeyMigrator.rewriteLegacyKeysInFile(configFile, getLogger(), "config.yml",
                    LEGACY_CONFIG_KEY_MIGRATIONS, legacyDefaults);
        } catch (IOException ex) {
            getLogger().warning("Could not read bundled " + LEGACY_1X_DEFAULTS_ROOT + "config.yml: " + ex.getMessage());
        }
        if (rewritten) {
            reloadConfig();
        }
        // Missing keys are added as text with their comments; the rest of the
        // file stays byte-identical. Written synchronously, so the write lands
        // before the watcher's refreshBaseline() and isn't an external edit.
        try (InputStream in = getResource("config.yml")) {
            if (in == null)
                return List.of();
            var added = ConfigDefaultsInserter.insertMissingKeys(configFile, in, getLogger(), "config.yml",
                    List.of(OPEN_CONFIG_SECTIONS));
            if (!added.isEmpty()) {
                reloadConfig();
            }
            return added;
        } catch (IOException ex) {
            getLogger().warning("Could not read bundled config.yml: " + ex.getMessage());
            return List.of();
        }
    }

    // Reports keys added to config.yml and, by messages.load(), to the active
    // lang file.
    private void logAddedDefaults(List<String> configKeys) {
        logAddedKeys("config.yml", configKeys);
        logAddedKeys("lang/" + messages.getLanguage() + ".yml", messages.getLastAddedKeys());
    }

    private void logAddedKeys(String file, List<String> addedKeys) {
        if (addedKeys == null || addedKeys.isEmpty())
            return;
        messages.log(getLogger(), Level.INFO, "log.defaults_updated", Map.of("file", file,
                "count", Integer.toString(addedKeys.size()), "keys", String.join(", ", addedKeys)));
    }

    // Marks every failed provider as shown and returns those failing for the first
    // time since the last reset; a successful fetch resets its provider.
    // Package-private for ProviderFailuresTest.
    static List<UpdateChecker.ProviderResult> firstFailures(Set<UpdateChecker.Provider> reported,
            List<UpdateChecker.ProviderResult> results) {
        List<UpdateChecker.ProviderResult> first = new ArrayList<>();
        if (results == null) {
            return first;
        }
        for (UpdateChecker.ProviderResult result : results) {
            if (result == null) {
                continue;
            }
            if (result.success()) {
                reported.remove(result.provider());
            } else if (reported.add(result.provider())) {
                first.add(result);
            }
        }
        return first;
    }

    private void logUpdateSummary(UpdateChecker.UpdateInfo info) {
        List<UpdateChecker.ProviderResult> providers = info.providers() == null ? Collections.emptyList() : info.providers();
        boolean hasProviderDetails = !providers.isEmpty();
        boolean shouldBracketLog = info.hasUpdate() || hasProviderDetails;

        if (shouldBracketLog) {
            logUpdateDivider();
        }

        if (info.hasUpdate()) {
            messages.log(getLogger(), Level.INFO, "log.update_found", Map.of("current", info.currentVersion()));
        } else {
            messages.log(getLogger(), Level.INFO, "log.update_none");
        }

        if (hasProviderDetails) {
            for (UpdateChecker.ProviderResult result : providers) {
                logProviderResult(result);
            }
        }

        if (shouldBracketLog) {
            logUpdateDivider();
        }
    }

    private void logUpdateDivider() {
        getLogger().info("=======================");
    }

    private void logProviderResult(UpdateChecker.ProviderResult result) {
        if (result == null) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("provider", result.provider().displayName());
        if (!result.success()) {
            placeholders.put("error", result.errorMessage());
            messages.log(getLogger(), Level.INFO, "log.update_provider_error", placeholders);
            return;
        }
        if (result.latestVersion() == null) {
            messages.log(getLogger(), Level.INFO, "log.update_provider_none", placeholders);
            return;
        }
        placeholders.put("version", result.latestVersion());
        placeholders.put("url", result.url() == null ? "" : result.url());
        messages.log(getLogger(), Level.INFO, "log.update_provider_ok", placeholders);
    }

    private ConfigurationSection updateSettings() {
        return getConfig().getConfigurationSection("update_check");
    }

    private boolean readUpdateBoolean(String childKey, boolean defaultValue) {
        ConfigurationSection section = updateSettings();
        if (section != null && section.contains(childKey)) {
            return section.getBoolean(childKey, defaultValue);
        }
        return defaultValue;
    }

    private int readUpdateInt(String childKey, int defaultValue) {
        ConfigurationSection section = updateSettings();
        if (section != null && section.contains(childKey)) {
            return section.getInt(childKey, defaultValue);
        }
        return defaultValue;
    }

    private long readUpdateLong(String childKey, long defaultValue) {
        ConfigurationSection section = updateSettings();
        if (section != null && section.contains(childKey)) {
            return section.getLong(childKey, defaultValue);
        }
        return defaultValue;
    }
}
