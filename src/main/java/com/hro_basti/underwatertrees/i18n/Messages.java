package com.hro_basti.underwatertrees.i18n;

import com.hro_basti.underwatertrees.Plugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class Messages {

    private final Plugin plugin;
    private final File langDir;
    private YamlConfiguration messages;
    private YamlConfiguration defaults;
    private String language;

    public Messages(Plugin plugin, String language) {
        this.plugin = plugin;
        this.langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            // create folder and copy defaults
            langDir.mkdirs();
        }
        ensureDefaultLangFile("en_US.yml");
        ensureDefaultLangFile("de_DE.yml");
        setLanguage(language);
    }

    private void ensureDefaultLangFile(String name) {
        File f = new File(langDir, name);
        if (!f.exists()) {
            String resourcePath = "lang/" + name;
            if (plugin.getResource(resourcePath) != null) {
                plugin.saveResource(resourcePath, false);
            }
        }
    }

    public void setLanguage(String language) {
        this.language = language;
        // Load defaults (from resource en_US.yml)
        this.defaults = loadFromResource("lang/en_US.yml");

        // Try load selected language from file; fallback to resource; fallback to defaults
        File file = new File(langDir, language + ".yml");
        if (file.exists()) {
            this.messages = YamlConfiguration.loadConfiguration(file);
        } else {
            this.messages = loadFromResource("lang/" + language + ".yml");
            if (this.messages == null) {
                this.messages = new YamlConfiguration();
            }
        }
        if (this.defaults != null) {
            this.messages.setDefaults(this.defaults);
            this.messages.options().copyDefaults(true);
        }
    }

    private YamlConfiguration loadFromResource(String path) {
        InputStream in = plugin.getResource(path);
        if (in == null) return null;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load resource " + path + ": " + e.getMessage());
            return null;
        }
    }

    public String get(String key) {
        return messages.getString(key, key);
    }

    public String get(String key, Map<String, String> placeholders) {
        String base = get(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                base = base.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return base;
    }

    public String getLanguage() {
        return language;
    }

    public void reload(FileConfiguration config) {
        String lang = config.getString("language", this.language != null ? this.language : "en_US");
        setLanguage(lang);
    }
}
