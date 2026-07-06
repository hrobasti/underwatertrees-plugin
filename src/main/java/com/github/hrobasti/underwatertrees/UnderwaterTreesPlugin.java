package com.github.hrobasti.underwatertrees;

import com.github.hrobasti.turtlelib.helper.ConfigWatcher;
import com.github.hrobasti.turtlelib.helper.ServerMatcher;
import com.github.hrobasti.turtlelib.helper.StartupBanner;
import com.github.hrobasti.underwatertrees.metrics.Metrics;
import com.github.hrobasti.turtlelib.helper.MessageService;
import com.github.hrobasti.turtlelib.helper.UpdateChecker;
import com.github.hrobasti.turtlelib.helper.VersionComparator;
import com.github.hrobasti.underwatertrees.commands.UnderwaterTreesBasicCommand;
import com.github.hrobasti.underwatertrees.listeners.UnderwaterSaplingsListener;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class UnderwaterTreesPlugin extends JavaPlugin {

    private static final String STARTUP_BANNER_RESOURCE = "banner.txt";
    private static final String REQUIRED_SERVER_BRAND = "Paper";
    private static final String SUPPORTED_VERSION_MIN = "26.2";
    private static final String SUPPORTED_VERSION_MAX = "26.2";
    private static final String SUPPORTED_VERSION_LABEL = "26.2";
    private static final ServerMatcher.IncompatibleAction INCOMPATIBLE_SERVER_ACTION =
        ServerMatcher.IncompatibleAction.WARN_AND_CONTINUE;
    private UnderwaterSaplingsListener saplingsListener;
    private MessageService messages;
    private ConfigWatcher configWatcher;
    private Metrics metrics;
    private UpdateChecker updateChecker;
    private int updateTaskId = -1;
    private volatile UpdateChecker.UpdateInfo latestUpdateInfo;
    private volatile UpdateChecker.ProviderResult preferredUpdateResult;
    private volatile boolean announceNextUpdateSummary = true;
    private Listener updateJoinListener;
    private ServerMatcher serverMatcher;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigDefaults();

        if (getConfig().getBoolean("startup-banner-enabled", true)) {
            logStartupBanner();
        }

        String lang = getConfig().getString("language", "en_US");
        messages = new MessageService(this, "<green>[<prefix_label>]</green>", "UnderwaterTrees");
        messages.load(lang);
        applyChatPrefixLabel();
        setupServerMatcher();
        // Log active language on startup
        getLogger().info(messages.plain("plugin.language-set", java.util.Map.of("code", messages.getLanguage())));

        saplingsListener = new UnderwaterSaplingsListener(this);
        Bukkit.getPluginManager().registerEvents(saplingsListener, this);

        // Register Paper Brigadier basic command at runtime
        registerCommand("underwatertrees", new UnderwaterTreesBasicCommand(this, saplingsListener));

        getLogger().info(messages.plain("plugin.enabled"));

        initMetrics();

        initUpdateChecker();

        initializeConfigWatcher();
        if (configWatcher != null) {
            configWatcher.start();
        }
    }

    @Override
    public void onDisable() {
        if (configWatcher != null) {
            configWatcher.stop();
        }
        stopUpdateCheck();
        if (messages == null) {
            getLogger().info("UnderwaterTrees plugin disabled.");
        } else {
            getLogger().info(messages.plain("plugin.disabled"));
        }
    }

    public MessageService getMessages() {
        return messages;
    }

    public void reloadMessages() {
        if (this.messages == null) {
            this.messages = new MessageService(this, "<green>[<prefix_label>]</green>", "UnderwaterTrees");
        }
        this.messages.reload(getConfig());
        applyChatPrefixLabel();
        setupServerMatcher();
        // Log active language on reload
        getLogger().info(messages.plain("plugin.language-set", java.util.Map.of("code", messages.getLanguage())));
        initMetrics();
        initUpdateChecker();
        refreshConfigWatcherBaseline();
    }

    // Initialize or disable bStats metrics based on config flag
    private void initMetrics() {
        boolean enabled = getConfig().getBoolean("metrics-enabled", true);
        if (enabled) {
            if (metrics == null) {
                metrics = new Metrics(this, 28005);
                // Custom charts: language, sapling count, soil count
                metrics.addCustomChart(new Metrics.SimplePie("language", () -> messages.getLanguage()));
                metrics.addCustomChart(new Metrics.SingleLineChart("sapling_count", saplingsListener::getSaplingCount));
                metrics.addCustomChart(new Metrics.SingleLineChart("soil_block_count", saplingsListener::getSoilCount));
            }
        } else {
            if (metrics != null) {
                // Cannot fully stop submissions immediately; shutdown scheduler
                metrics.shutdown();
                metrics = null;
            }
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
            .allowRange(SUPPORTED_VERSION_MIN, SUPPORTED_VERSION_MAX)
            .incompatibleAction(INCOMPATIBLE_SERVER_ACTION)
            .onMismatch(this::handleServerMismatch)
            .build();
        serverMatcher.enforce();
    }

    private void handleServerMismatch(ServerMatcher.MatchResult result) {
        java.util.Map<String, String> placeholders = new HashMap<>();
        placeholders.put("required_server", REQUIRED_SERVER_BRAND);
        placeholders.put("supported_versions", SUPPORTED_VERSION_LABEL);
        placeholders.put("server_name", result.serverName() != null ? result.serverName() : "unknown");
        placeholders.put("mc_version", result.minecraftVersion() != null ? result.minecraftVersion() : "unknown");

        if (messages != null) {
            var console = getServer().getConsoleSender();
            if (console != null) {
                console.sendMessage(messages.format("warn.unsupported-server-version", placeholders));
            }
            getLogger().warning(messages.plain("warn.unsupported-server-version", placeholders));
        } else {
            getLogger().warning("UnderwaterTrees officially supports " + REQUIRED_SERVER_BRAND
                + " " + SUPPORTED_VERSION_LABEL + ". Detected "
                + result.serverName() + " " + result.minecraftVersion() + '.');
        }
    }

    private void initUpdateChecker() {
        boolean enabled = readUpdateBoolean("enabled", true);
        if (!enabled) {
            stopUpdateCheck();
            return;
        }

        int providerMode = Math.max(0, Math.min(2, readUpdateInt("sources", 0)));
        boolean includePrereleases = readUpdateBoolean("include-prereleases", false);
        boolean filterByServerVersion = readUpdateBoolean("filter-by-server-version", true);
        updateChecker = new UpdateChecker(this, providerMode, includePrereleases, filterByServerVersion, "underwatertrees", "hro_basti/underwatertrees");
        announceNextUpdateSummary = true;
        registerUpdateNotifier();

        requestUpdateCheck(true);

        long hours = Math.max(1, readUpdateLong("interval-hours", 24L));
        long periodTicks = hours * 60L * 60L * 20L;
        if (updateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(updateTaskId);
        }
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
        latestUpdateInfo = null;
        preferredUpdateResult = null;
        announceNextUpdateSummary = true;
    }

    private void requestUpdateCheck(boolean logConsole) {
        if (updateChecker == null) {
            return;
        }
        updateChecker.checkAsync()
                .thenAccept(info -> {
                    if (!UnderwaterTreesPlugin.this.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(UnderwaterTreesPlugin.this, () -> handleUpdateInfo(info, logConsole));
                })
                .exceptionally(ex -> {
                    getLogger().fine("Update check failed: " + ex.getMessage());
                    return null;
                });
    }

    private void handleUpdateInfo(UpdateChecker.UpdateInfo info, boolean logConsole) {
        if (info == null) {
            return;
        }
        latestUpdateInfo = info;
        UpdateChecker.ProviderResult chosen = selectPreferredProvider(info);
        preferredUpdateResult = chosen;
        boolean notifyConsole = readUpdateBoolean("notify-console", true);
        boolean alwaysShow = readUpdateBoolean("notify-console-always-shown", false);
        boolean wantsLog = notifyConsole && (announceNextUpdateSummary || logConsole || info.hasUpdate());
        if (wantsLog && (info.hasUpdate() || alwaysShow)) {
            logUpdateSummary(info);
            announceNextUpdateSummary = false;
        }
        if (!info.hasUpdate() || !notifyConsole || chosen == null) {
            return;
        }
    }

    private UpdateChecker.ProviderResult selectPreferredProvider(UpdateChecker.UpdateInfo info) {
        if (info.providers() == null) {
            return null;
        }
        UpdateChecker.ProviderResult selected = null;
        for (UpdateChecker.ProviderResult result : info.providers()) {
            if (result == null || !result.success()) {
                continue;
            }
            String remote = result.latestVersion();
            if (remote == null || !VersionComparator.isGreater(remote, info.currentVersion())) {
                continue;
            }
            if (selected == null || VersionComparator.isGreater(remote, selected.latestVersion())) {
                selected = result;
            }
        }
        return selected;
    }

    private void registerUpdateNotifier() {
        if (updateJoinListener != null) {
            return;
        }
        updateJoinListener = new Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(PlayerJoinEvent event) {
                notifyPlayerAboutUpdate(event.getPlayer());
            }
        };
        Bukkit.getPluginManager().registerEvents(updateJoinListener, this);
    }

    private void notifyPlayerAboutUpdate(Player player) {
        if (!readUpdateBoolean("notify-op-join", true)) {
            return;
        }
        UpdateChecker.ProviderResult result = preferredUpdateResult;
        UpdateChecker.UpdateInfo info = latestUpdateInfo;
        if (result == null || info == null) {
            return;
        }
        if (!VersionComparator.isGreater(result.latestVersion(), info.currentVersion())) {
            return;
        }
        if (!(player.isOp() || player.hasPermission("underwatertrees.update.notify"))) {
            return;
        }
        String localVersion = getPluginMeta().getVersion();
        java.util.Map<String, String> placeholders = new HashMap<>();
        placeholders.put("remote", result.latestVersion());
        placeholders.put("local", localVersion);
        player.sendMessage(messages.format("update.available", placeholders));
        player.sendMessage(messages.component("update.details"));
    }

    private void applyChatPrefixLabel() {
        if (messages == null) {
            return;
        }
        messages.setPrefixLabel(resolveChatPrefixLabel());
    }

    private String resolveChatPrefixLabel() {
        String label = getConfig().getString("chat-prefix-label", "UnderwaterTrees");
        if (label == null || label.isBlank()) {
            return "UnderwaterTrees";
        }
        return label;
    }

    private void initializeConfigWatcher() {
        if (configWatcher != null) {
            return;
        }
        configWatcher = ConfigWatcher.builder(this)
            .fileSupplier(() -> new java.io.File(getDataFolder(), "config.yml"))
            .enabledSupplier(() -> getConfig().getBoolean("config-watch-enabled", true))
            .intervalSeconds(resolveConfigWatchIntervalSeconds())
            .onChange(this::handleExternalConfigChange)
            .build();
    }

    private long resolveConfigWatchIntervalSeconds() {
        long configured = getConfig().getLong("config-watch-interval-seconds", 5L);
        return Math.max(1L, configured);
    }

    private void handleExternalConfigChange() {
        try {
            reloadConfig();
            ensureConfigDefaults();
            if (saplingsListener != null) {
                saplingsListener.applyConfig(getConfig());
            }
            reloadMessages();
            getLogger().info("Config file changed externally – auto reloaded.");
        } catch (Exception ex) {
            getLogger().warning("Error during automatic config reload: " + ex.getMessage());
        }
    }

    private void refreshConfigWatcherBaseline() {
        if (configWatcher != null) {
            configWatcher.refreshBaseline();
        }
    }

    // Public hook to merge new default keys into existing config without overwriting user values
    public void ensureConfigDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            var cfg = getConfig();
            cfg.setDefaults(def);
            cfg.options().copyDefaults(true);
            saveConfig();
        } catch (Exception ex) {
            getLogger().warning("Failed to merge default config: " + ex.getMessage());
        }
    }

    private void logUpdateSummary(UpdateChecker.UpdateInfo info) {
        java.util.List<UpdateChecker.ProviderResult> providers =
                info.providers() == null ? java.util.Collections.emptyList() : info.providers();
        boolean hasProviderDetails = !providers.isEmpty();
        boolean shouldBracketLog = info.hasUpdate() || hasProviderDetails;

        if (shouldBracketLog) {
            logUpdateDivider();
        }

        if (info.hasUpdate()) {
            getLogger().info("Update found. Current version: " + info.currentVersion() + ".");
        } else {
            getLogger().info("No update found.");
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
        if (!result.success()) {
            getLogger().info("- " + result.provider().displayName() + ": Failed to fetch.");
            return;
        }
        String url = result.url() == null ? "" : result.url();
        getLogger().info("- " + result.provider().displayName() + ": Version "
                + result.latestVersion() + " [" + url + "]");
    }

    private ConfigurationSection updateSettings() {
        return getConfig().getConfigurationSection("update-check");
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

