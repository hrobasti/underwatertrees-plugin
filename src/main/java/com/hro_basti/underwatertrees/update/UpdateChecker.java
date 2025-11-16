package com.hro_basti.underwatertrees.update;

import com.hro_basti.underwatertrees.Plugin;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker {
    private final Plugin plugin;
    private volatile boolean updateAvailable = false;
    private volatile String remoteVersion = null;
    private volatile String remoteSource = null;
    private volatile String remoteUrl = null;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String MODRINTH_SLUG = "underwatertrees";
    private static final String HANGAR_SLUG = "hro_basti/underwatertrees";

    public UpdateChecker(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getRemoteVersion() {
        return remoteVersion;
    }

    public String getRemoteSource() {
        return remoteSource;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void checkNowAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::checkOnce);
    }

    private void checkOnce() {
        if (!plugin.getConfig().getBoolean("update-check", true)) return;
        // update-sources as numeric mode: 0 = both (modrinth, hangar), 1 = modrinth only, 2 = hangar only
        int mode = 0;
        try { mode = plugin.getConfig().getInt("update-sources", 0); } catch (Exception ignored) {}
        List<String> sources = switch (mode) {
            case 1 -> List.of("modrinth");
            case 2 -> List.of("hangar");
            default -> List.of("modrinth", "hangar");
        };

        for (String src : sources) {
            String s = src.toLowerCase(Locale.ROOT);
            try {
                if (s.equals("modrinth")) {
                    if (checkModrinth()) return;
                } else if (s.equals("hangar")) {
                    if (checkHangar()) return;
                }
            } catch (Exception ex) {
                plugin.getLogger().fine("Update check failed for source: " + s + " - " + ex.getMessage());
            }
        }
    }

    private boolean checkModrinth() throws Exception {
        String slug = MODRINTH_SLUG;
        String url = "https://api.modrinth.com/v2/project/" + slug + "/version";
        String body = fetch(url);
        if (body == null || body.isEmpty()) return false;

        boolean includePre = plugin.getConfig().getBoolean("include-prereleases", false);
        boolean filterByMc = plugin.getConfig().getBoolean("filter-by-server-version", true);
        String mc = Bukkit.getMinecraftVersion(); // e.g., 1.21.1
        String mcPrefix = mc.contains(".") ? mc.substring(0, mc.lastIndexOf('.')) : mc; // 1.21

        // Iterate over occurrences of objects by version_number and select first matching constraints
        Pattern p = Pattern.compile("\\{[^}]*\\\"version_number\\\":\\\"([^\\\"]+)\\\"[^}]*} ");
        Matcher m = p.matcher(body + " "); // add space to help regex termination
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            String obj = body.substring(start, end);
            String ver = m.group(1);
            if (!includePre && obj.contains("\"version_type\":\"beta\"") || obj.contains("\"version_type\":\"alpha\"")) {
                continue;
            }
            if (!(obj.contains("paper") || obj.contains("Paper"))) continue;
            if (filterByMc && !(obj.contains("\"" + mc + "\"") || obj.contains("\"" + mcPrefix + "\""))) {
                // Require exact MC or same major.minor
                continue;
            }
            String local = getLocalVersion();
            if (isNewer(ver, local)) {
                updateAvailable = true;
                remoteVersion = ver;
                remoteSource = "modrinth";
                remoteUrl = "https://modrinth.com/plugin/" + slug + "/version/" + ver;
            } else {
                updateAvailable = false;
            }
            return true;
        }
        return false;
    }

    private boolean checkHangar() throws Exception {
        String slug = HANGAR_SLUG;
        String url = "https://hangar.papermc.io/api/v1/projects/" + slug + "/versions?limit=20";
        String body = fetch(url);
        if (body == null || body.isEmpty()) return false;

        boolean includePre = plugin.getConfig().getBoolean("include-prereleases", false);
        boolean filterByMc = plugin.getConfig().getBoolean("filter-by-server-version", true);
        String mc = Bukkit.getMinecraftVersion();
        String mcPrefix = mc.contains(".") ? mc.substring(0, mc.lastIndexOf('.')) : mc;

        // Roughly find version entries; prefer channel Release
        Pattern p = Pattern.compile("\\{[^}]*\\}");
        Matcher m = p.matcher(body);
        while (m.find()) {
            String obj = m.group();
            boolean isRelease = obj.contains("Release") || obj.contains("\"channel\":\"Release\"");
            if (!includePre && !isRelease) continue;
            if (!(obj.contains("PAPER") || obj.contains("paper"))) continue;
            if (filterByMc && !(obj.contains(mc) || obj.contains(mcPrefix))) continue;

            String ver = extractAny(obj, List.of("\"name\":\"", "\"version\":\""));
            if (ver == null) continue;
            String local = getLocalVersion();
            if (isNewer(ver, local)) {
                updateAvailable = true;
                remoteVersion = ver;
                remoteSource = "hangar";
                remoteUrl = "https://hangar.papermc.io/" + slug + "/versions";
            } else {
                updateAvailable = false;
            }
            return true;
        }
        return false;
    }

    private String fetch(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "UnderwaterTrees/" + getLocalVersion())
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) return resp.body();
        return null;
    }

    private String extractAny(String text, List<String> prefixes) {
        for (String pref : prefixes) {
            int i = text.indexOf(pref);
            if (i >= 0) {
                int s = i + pref.length();
                int e = text.indexOf('"', s);
                if (e > s) return text.substring(s, e);
            }
        }
        return null;
    }

    private String getLocalVersion() {
        try {
            String v = plugin.getPluginMeta().getVersion();
            return v != null ? v : "0.0.0";
        } catch (Throwable t) {
            String v = plugin.getDescription().getVersion();
            return v != null ? v : "0.0.0";
        }
    }

    private boolean isNewer(String remote, String local) {
        int[] r = parseSemVer(remote);
        int[] l = parseSemVer(local);
        if (r[0] != l[0]) return r[0] > l[0];
        if (r[1] != l[1]) return r[1] > l[1];
        if (r[2] != l[2]) return r[2] > l[2];
        return false;
    }

    private int[] parseSemVer(String v) {
        // Extract digits a.b.c ignoring suffix
        int major = 0, minor = 0, patch = 0;
        try {
            String[] parts = v.split("-", 2)[0].split("\\.");
            if (parts.length > 0) major = parseInt(parts[0]);
            if (parts.length > 1) minor = parseInt(parts[1]);
            if (parts.length > 2) patch = parseInt(parts[2]);
        } catch (Exception ignored) {}
        return new int[]{major, minor, patch};
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
