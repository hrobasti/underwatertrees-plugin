# 🌳 UnderwaterTrees (Paper 1.21.x)

Minimal Paper plugin for Minecraft 1.21.x that allows placing saplings underwater with configurable materials, localization, live reload, update checks, metrics, and stability protection.

## 📋 Requirements

- **Java 21** (JDK)
- **Paper Server 1.21.x** (Spigot and vanilla are not supported)
- **Gradle** (optional; wrapper included)

## 🔨 Build

From the project root:

**Linux/macOS**
```bash
./gradlew build
```

**Windows**
```bat
gradlew.bat build
```

**Output:** `build/libs/UnderwaterTrees-<version>.jar`

**Versioning:** The plugin version is read from `version.properties` (`version=x.y.z`). If absent, it falls back to `gradle.properties`. The resolved version is injected into `paper-plugin.yml` and the JAR filename.

## 📦 Installation & Usage

1. Copy the built JAR into your server `plugins` folder.
2. Start (or restart) the server to generate `plugins/UnderwaterTrees/config.yml` and `plugins/UnderwaterTrees/lang/`.
3. Adjust the config as needed (materials, language, logging, auto‑reload, metrics, stability).
4. Reload in-game or console:
   ```
   /underwatertrees reload
   ```

**Notes:**
- Materials are resolved by name at runtime; unknown names are ignored.
- With `auto-reload: true`, config changes are detected every ~5 seconds.
- Metrics can be disabled via `metrics-enabled: false` or globally in `plugins/bStats/config.yml`.

## 🔧 Compatibility

- **Platform:** Paper only (uses Paper-specific APIs). Spigot and vanilla servers are not supported.
- **API Target:** `api-version: 1.21`, built against Paper API `1.21.10`.
- **Older Versions:** Not supported. Unknown materials on older servers are ignored silently.

## ✨ Features

- 🌊 Underwater placement of configured saplings on configured soil blocks.
- 🎚️ Per‑material enable/disable flags (`saplings` and `soils` boolean maps).
- 🔄 Fallback defaults if both sections are empty.
- 🌐 Internationalization (language files in `src/main/resources/lang`).
- ⚡ Live config + language reload via `/underwatertrees reload`.
- 📊 Optional stats logging (`log-stats`) and detailed listing (`log-detail`).
- 🔁 Automatic external config file change detection (`auto-reload`).
- 📈 Optional bStats metrics (`metrics-enabled`) with custom charts: language, sapling count, soil count.
- 🛡️ Stability protection (`protect-underwater-saplings`): prevents unintended breaking from physics/fluids while placement conditions remain valid.
- 🔔 Update checker with Modrinth/Hangar sources, version comparison, and optional pre-release inclusion.

## ⚙️ Configuration (config.yml)

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `language` | string | `en_US` | Language code (must match file in `lang/`, e.g., `en_US`, `de_DE`) |
| `log-stats` | boolean | `true` | Log counts of enabled soils/saplings on startup/reload |
| `log-detail` | boolean | `false` | List each enabled soil and sapling individually |
| `auto-reload` | boolean | `true` | Watch and auto‑reload `config.yml` when externally modified (~5s interval) |
| `require-water-above` | boolean | `false` | Only allow placement if water is directly above target block |
| `protect-underwater-saplings` | boolean | `true` | Cancel physics/fluid events to prevent breaking adjacent underwater saplings (conditions must still be valid) |
| `soils` | map | varies | Enable flags for soil materials (e.g., `DIRT: true`) |
| `saplings` | map | varies | Enable flags for sapling materials (e.g., `OAK_SAPLING: true`) |
| `metrics-enabled` | boolean | `true` | Enable anonymous bStats metrics (plugin ID 28005) |
| `update-check` | boolean | `true` | Master switch for update checking |
| `update-interval-hours` | integer | `24` | Interval in hours between update checks (min 1) |
| `include-prereleases` | boolean | `false` | Include beta/alpha versions when checking for updates |
| `filter-by-server-version` | boolean | `true` | Prefer builds matching the server's Minecraft version |
| `notify-console` | boolean | `true` | Log update availability to console |
| `notify-op-join` | boolean | `true` | Notify ops on join when an update is available |
| `update-sources` | integer | `0` | `0` = Modrinth+Hangar, `1` = Modrinth only, `2` = Hangar only |

**Adding Materials:**  
Add an uppercase Bukkit `Material` key under `soils` or `saplings` with value `true`. Unknown names are ignored silently, allowing safe usage across Minecraft versions.

**Config Defaults Merge:**  
On startup, manual reload, and auto‑reload, new default keys from the bundled `config.yml` are merged into your existing `plugins/UnderwaterTrees/config.yml` without overwriting your values. New options (e.g., `protect-underwater-saplings`) are added automatically after updates.

## 🛡️ Stability Protection

When `protect-underwater-saplings: true`:
- **Physics:** `BlockPhysicsEvent` is canceled for saplings if soil beneath is valid and (optionally) water above is present, preventing neighbor updates from breaking them.
- **Fluids:** `BlockFromToEvent` into sapling blocks is canceled to avoid fluid‑induced breakage.
- **Natural Breakage:** If soil becomes invalid or (with `require-water-above: true`) water is removed, protection no longer applies and the sapling breaks naturally.

## 🎮 Commands & Permissions

| Command | Permission | Default | Description |
| --- | --- | --- | --- |
| `/underwatertrees reload` | `underwatertrees.reload` | op | Reload config, listener, and language |
| *(join notification)* | `underwatertrees.update` | op | Receive update notifications on join |

**Recommended Permission Manager:** [LuckPerms](https://luckperms.net/)

## 🔔 Update Check

- **Sources:** Modrinth (primary) and Hangar (fallback). If both fail, skips silently.
- **Version Comparison:**
  - Numeric first (major > minor > patch)
  - Release > pre‑release (`alpha` < `beta` < `rc`)
  - Pre‑release indices compared (e.g., `beta2` > `beta1`)
  - Letter suffix without hyphen (e.g., `1.0a`) is treated as newer than plain release (`1.0`)

## 🌐 Internationalization

Language codes map to YAML files in `src/main/resources/lang/`. Unsupported or missing keys fall back to English (`en_US`). Reloading updates active messages.

## 📈 Metrics (bStats)

UnderwaterTrees uses [bStats](https://bstats.org) to collect anonymous usage metrics if `metrics-enabled: true`.

**Base Data (handled by bStats):** server UUID, player count, Java/OS info, plugin version.

**Custom Charts:**
- `language` – active language code
- `sapling_count` – number of enabled sapling materials
- `soil_block_count` – number of enabled soil materials

**Disable:** Set `metrics-enabled: false` and reload. For global opt‑out (all plugins), edit `plugins/bStats/config.yml`.

## 🐛 Troubleshooting

| Issue | Solution |
| --- | --- |
| Plugin does not load | Ensure Paper 1.21.x (Spigot/Vanilla unsupported); place JAR in `plugins` folder |
| `/underwatertrees` command unknown or no permission | Check server log; ensure plugin enabled and `underwatertrees.reload` granted (default op) |
| Materials not applied | Names must match Bukkit `Material` (uppercase, underscore); enable `log-detail` to verify |
| New config options do not appear | Restart or `/underwatertrees reload` to trigger defaults merge; ensure JAR updated |
| Auto‑reload not triggering | Ensure `auto-reload: true`; some environments have coarse timestamp updates |
| Waterlogged visuals missing | Vanilla client cannot render waterlogged saplings; this is a client limitation |
| Language not applied | Ensure `language` matches a file in `lang/` (e.g., `en_US`); missing keys fall back to English |

## 📝 Project Notes

Development of UnderwaterTrees included the use of generative AI assistance (e.g., GitHub Copilot) for scaffolding, refactoring, and documentation drafts. All code and text were reviewed, adapted, and validated by the project maintainer to ensure accuracy, licensing compliance, and suitability for the Paper API.

## 📜 License

Licensed under the Apache License, Version 2.0. See `LICENSE` for details.

Third‑party notices are listed in `NOTICE` (e.g., bStats single‑file Metrics usage terms).


