package com.hro_basti.underwatertrees;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import com.hro_basti.underwatertrees.metrics.Metrics;

import com.hro_basti.underwatertrees.i18n.Messages;
import com.hro_basti.underwatertrees.listeners.UnderwaterSaplingsListener;
import com.hro_basti.underwatertrees.commands.UnderwaterTreesBasicCommand;
import com.hro_basti.underwatertrees.update.UpdateChecker;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Plugin extends JavaPlugin {

    private UnderwaterSaplingsListener saplingsListener;
    private Messages messages;
    private long configLastModified;
    private int autoReloadTaskId = -1; // Task id for scheduled auto reload
    private Metrics metrics;
    private UpdateChecker updateChecker;
    private int updateTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigDefaults();

        String lang = getConfig().getString("language", "en_US");
        messages = new Messages(this, lang);
        // Log active language on startup
        getLogger().info(messages.get("plugin.language_set", java.util.Map.of("code", messages.getLanguage())));

        saplingsListener = new UnderwaterSaplingsListener(this);
        Bukkit.getPluginManager().registerEvents(saplingsListener, this);

        // Register Paper Brigadier basic command at runtime
        registerCommand("underwatertrees", new UnderwaterTreesBasicCommand(this, saplingsListener));

        getLogger().info(messages.get("plugin.enabled"));

        // Initialize timestamp for config auto hot-reload
        java.io.File cfgFile = new java.io.File(getDataFolder(), "config.yml");
        configLastModified = cfgFile.lastModified();
        updateAutoReloadFlag();

        initMetrics();

        initUpdateChecker();
    }

    @Override
    public void onDisable() {
        stopAutoReload();
        stopUpdateCheck();
        if (messages == null) {
            getLogger().info("UnderwaterTrees plugin disabled.");
        } else {
            getLogger().info(messages.get("plugin.disabled"));
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public void reloadMessages() {
        if (this.messages == null) {
            String lang = getConfig().getString("language", "en_US");
            this.messages = new Messages(this, lang);
        } else {
            this.messages.reload(getConfig());
        }
        // Log active language on reload
        getLogger().info(messages.get("plugin.language_set", java.util.Map.of("code", messages.getLanguage())));
        // Apply auto-reload flag after a manual reload as well
        updateAutoReloadFlag();
        initMetrics();
        initUpdateChecker();
    }

    private void startAutoReload() {
        // Check every 5 seconds (100 ticks)
        autoReloadTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                java.io.File cfgFile = new java.io.File(getDataFolder(), "config.yml");
                long lm = cfgFile.lastModified();
                if (lm != 0 && lm != configLastModified) {
                    configLastModified = lm;
                    // External change detected: reload config, listener and messages
                    reloadConfig();
                    ensureConfigDefaults();
                    saplingsListener.applyConfig(getConfig());
                    reloadMessages();
                    getLogger().info("Config file changed externally – auto reloaded.");
                }
            } catch (Exception ex) {
                getLogger().warning("Error during auto-reload check: " + ex.getMessage());
            }
        }, 100L, 100L).getTaskId();
    }

    private void stopAutoReload() {
        if (autoReloadTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoReloadTaskId);
            autoReloadTaskId = -1;
        }
    }

    private void updateAutoReloadFlag() {
        boolean enabled = getConfig().getBoolean("auto-reload", true);
        if (enabled) {
            if (autoReloadTaskId == -1) {
                startAutoReload();
            }
        } else {
            if (autoReloadTaskId != -1) {
                stopAutoReload();
                getLogger().info("Auto-reload disabled via config.");
            }
        }
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

    private void initUpdateChecker() {
        boolean enabled = getConfig().getBoolean("update-check", true);
        if (!enabled) {
            stopUpdateCheck();
            return;
        }
        if (updateChecker == null) updateChecker = new UpdateChecker(this);
        // Immediate async check
        updateChecker.checkNowAsync();
        // Schedule periodic checks
        long hours = Math.max(1, getConfig().getLong("update-interval-hours", 24));
        long periodTicks = hours * 60L * 60L * 20L;
        if (updateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(updateTaskId);
        }
        updateTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                updateChecker.checkNowAsync();
                if (updateChecker.isUpdateAvailable() && getConfig().getBoolean("notify-console", true)) {
                    getLogger().info("Update available: " + updateChecker.getRemoteVersion() + " (source: " + updateChecker.getRemoteSource() + ") " + (updateChecker.getRemoteUrl() != null ? updateChecker.getRemoteUrl() : ""));
                }
            } catch (Exception ignored) {}
        }, 200L, periodTicks).getTaskId();
        // Join notify listener
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                if (!getConfig().getBoolean("notify-op-join", true)) return;
                if (!updateChecker.isUpdateAvailable()) return;
                var p = e.getPlayer();
                if (!(p.isOp() || p.hasPermission("underwatertrees.update"))) return;
                String vLocal = getDescription().getVersion();
                String vRemote = updateChecker.getRemoteVersion();
                String url = updateChecker.getRemoteUrl();
                p.sendMessage(net.kyori.adventure.text.Component.text("UnderwaterTrees: New version " + vRemote + " available (you run " + vLocal + ")").color(net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                if (url != null && !url.isEmpty()) {
                    p.sendMessage(net.kyori.adventure.text.Component.text("More: " + url).color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
                }
            }
        }, this);
    }

    private void stopUpdateCheck() {
        if (updateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(updateTaskId);
            updateTaskId = -1;
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
}
