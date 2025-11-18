# UnderwaterTrees – Paper Plugin

Minimal Paper plugin for Minecraft 1.21.10 that allows placing saplings underwater with configurable materials, localization and live config reload.

## Requirements

**Build Requirements**
- Java 21 (JDK)
- Gradle (optional) – you can also use the included Gradle Wrapper

**Server Requirements**
- Paper Server 1.21.x

## Build

From the project root directory:

- Linux/macOS
```bash
./gradlew build
```

- Windows
```bat
gradlew.bat build
```
Underwater stability protection: prevents unintended breaking from water/physics when enabled via `protect-underwater-saplings`.
Versioning: The plugin version is taken from `version.properties` (key `version`) if present, otherwise from `gradle.properties`. The resolved version is injected into both the JAR file name and `paper-plugin.yml`.

## Install & Use

1) Copy the built JAR into your Paper server `plugins` folder.
2) Start (or restart) the server. A default configuration will be created under `plugins/UnderwaterTrees/config.yml`.
3) Adjust configuration (materials, language, logging, auto-reload, metrics) to your needs.
4) Reload the plugin configuration in-game or console:
```
/underwatertrees reload
```
| `protect-underwater-saplings` | boolean | `true` | Prevents physics/fluid updates from breaking adjacent underwater saplings while placement conditions are still valid. |

See also: Commands & Permissions section below.

Notes:
- Materials are resolved by name at runtime. Unknown materials are ignored silently.
	- Comparison rules: numeric first (major > minor > patch), then release > pre-release, then pre-release channels (`alpha` < `beta` < `rc`) and their numeric indices (e.g., `beta2` > `beta1`). Letter suffixes without hyphen (e.g., `1.0a`) are treated as newer than the plain release (`1.0`).

## Compatibility

- Platform: Paper only. Spigot is not supported (uses Paper-only APIs). Vanilla servers do not support plugins.
- API target: `api-version: 1.21` and built against Paper API `1.21.10`.
- Older server versions: Materials are resolved by name at runtime. If a material does not exist on the server version, it is silently ignored. The plugin is designed for 1.21.x; running on older versions is not guaranteed or supported.

## Features

- Underwater placement of configured saplings on configured soil blocks.
- Per-material enable/disable flags (`saplings:` and `soils:` maps with boolean values).
- Fallback defaults if both sections are empty.
- Internationalization (language files in `src/main/resources/lang`).
- Live language + config reload via `/underwatertrees reload`.
- Optional stats logging (`log-stats`) and detailed listing (`log-detail`).
- Automatic external config file change detection (`auto-reload`) – can be disabled.
- Optional anonymous metrics via bStats (`metrics-enabled`) with custom charts (language, sapling count, soil count). Global opt-out in `plugins/bStats/config.yml`.

### Configuration Keys (config.yml)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `language` | string | `en_US` | Language file code from `lang/` folder. |
| `require-water-above` | boolean | `false` | Only allow placement if target block has water above. |
| `log-stats` | boolean | `true` | Logs counts of loaded soils/saplings on enable/reload. |
| `log-detail` | boolean | `false` | Lists every loaded soil and sapling individually if true. |
| `auto-reload` | boolean | `true` | Watches `config.yml` for external changes (5s interval) and auto reloads. |
| `metrics-enabled` | boolean | `true` | Enables bStats anonymous usage metrics. |
| `soils` | map<string, boolean> | varies | Enable flags for soil materials. |
| `saplings` | map<string, boolean> | varies | Enable flags for sapling materials (supports custom additions). |

### Adding New Materials

Add a new uppercase Bukkit `Material` key to `soils:` or `saplings:` with value `true`. On next reload (manual or auto) it becomes active.

Notes:
- Materials are resolved via `Material.matchMaterial(<NAME>)`. Unknown names are ignored without errors.
- This allows safely keeping entries like `MANGROVE_PROPAGULE` or `CHERRY_SAPLING` on servers that might not provide them.

### Auto-Reload Behavior

If `auto-reload` is `true`, the plugin checks the modified timestamp of `config.yml` every 5 seconds. On change it reloads config, reapplies listener state and reloads messages. Set `auto-reload: false` to disable.

Config defaults merge: On startup, manual reload, and auto-reload, the plugin merges new default keys from the bundled `config.yml` into your existing `plugins/UnderwaterTrees/config.yml` without overwriting your values. This way new options are added automatically after updates while your settings stay intact.

### Internationalization

Language codes map to YAML files inside `lang/`. Unsupported or missing keys fall back to English (`en_US`). Reloading updates active messages.

### Stats Logging

With `log-stats: true` a summary line is logged showing counts and flags. Enable `log-detail: true` to also list every material.

### Metrics (bStats)

UnderwaterTrees uses bStats (https://bstats.org) to collect anonymous usage metrics if `metrics-enabled: true`.

Collected base data (handled by bStats): server UUID, player count, Java/OS info, plugin version.

Custom charts:
- `language` – active language code.
- `sapling_count` – number of enabled sapling materials.
- `soil_block_count` – number of enabled soil materials.

Disable locally: set `metrics-enabled: false` and reload. For full opt-out (all plugins) use `plugins/bStats/config.yml`.

## Commands & Permissions

- Command: `/underwatertrees reload` – Reloads config, listener setup and language.
- Permission: `underwatertrees.reload` (default: op) - Allows reloading the config.
- Permission: `underwatertrees.update` (default: op) – Receive update notifications on join.
- Recommended permission management: LuckPerms – https://luckperms.net/

## Update Check

UnderwaterTrees can periodically check for new releases and notify the console on startup and players with the `underwatertrees.update` permission when they join.

- Sources: Modrinth (primary) and Hangar (fallback). If both fail, it skips silently.
- Version handling: Compares the local plugin version against the latest available; optional pre-release inclusion and server-version filtering.

Configuration (in `config.yml`):
- `update-check` (boolean, default `true`): Enables/disables update checking.
- `update-interval-hours` (integer, default `24`): Interval for periodic checks.
- `include-prereleases` (boolean, default `false`): Allow pre-release versions.
- `filter-by-server-version` (boolean, default `true`): Prefer builds matching your server’s MC version.
- `notify-console` (boolean, default `true`): Log update availability to console.
- `notify-op-join` (boolean, default `true`): Notify ops on join.
- `update-sources` (integer): `0` = Modrinth+Hangar, `1` = Modrinth only, `2` = Hangar only.

## Troubleshooting

- Plugin does not load:
	- Use Paper 1.21.x (Spigot/Vanilla are not supported).
	- Place `UnderwaterTrees-*.jar` into the server `plugins` folder.
- `/underwatertrees` command unknown or no permission:
	- Check server log for errors; ensure plugin is enabled.
	- Permission needed: `underwatertrees.reload` (default: op).
- Materials not applied:
	- Names must match Bukkit `Material` constants (uppercase, underscore).
	- Some materials exist only on newer MC versions; unknown names are ignored.
	- Set `log-detail: true` to log each loaded soil/sapling for verification.
- New config options do not appear:
	- Restart the server or run `/underwatertrees reload`. The plugin merges new default keys from its bundled `config.yml` into your existing file on startup and on reload without overwriting your values.
	- Ensure you are editing the correct file at `plugins/UnderwaterTrees/config.yml` and that the plugin JAR was updated.
- Auto-reload seems not working:
	- Ensure `auto-reload: true`. The file timestamp is checked about every 5 seconds.
	- Some environments may not update timestamps reliably; use `/underwatertrees reload`.
- Saplings cannot be waterlogged:
	- The Minecraft client currently does not support waterlogged saplings. This is a vanilla limitation and cannot be changed by the plugin.
- Language not applied:
	- Ensure `language` matches a file in `lang/` (e.g., `en_US`).
	- Missing keys fall back to English.
- Metrics opt-out:
	- Set `metrics-enabled: false` and reload.
- Build issues (Java/Gradle):
	- Require JDK 21. Use the Gradle Wrapper: `./gradlew build` (Linux/macOS) or `gradlew.bat build` (Windows).
	- If wrapper is missing, run `gradle wrapper` first.

## License

Licensed under the Apache License, Version 2.0. See `LICENSE` for details.

This distribution also includes third-party components. See `NOTICE` for attribution and additional terms (e.g., bStats single-file Metrics class usage conditions).
