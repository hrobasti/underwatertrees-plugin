# UnderwaterTrees – Paper Plugin

Minimal Paper plugin for Minecraft 1.21.x that allows placing saplings underwater with configurable materials, localization and live config reload.

## Build

**Requirements**
- Java 21 (JDK)
- Gradle (optional) – you can also use the included Gradle Wrapper

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

**Requirements**
- Paper Server 1.21.x

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
	# UnderwaterTrees (Paper 1.21.x)

	UnderwaterTrees lets players place saplings underwater with fine‑grained controls, localization, live reload, update checks, metrics, and optional stability protection.

	## Requirements

	- Java 21 (JDK)
	- Paper 1.21.x
	- Gradle (optional; the wrapper is included)

	## Build

	From the project root:

	- Linux/macOS
	```bash
	./gradlew build
	```

	- Windows
	```bat
	gradlew.bat build
	```

	Artifacts: `build/libs/UnderwaterTrees-<version>.jar`

	Versioning: The plugin version comes from `version.properties` (`version=<x.y.z>`). If missing, it falls back to `gradle.properties`. The resolved version is injected into `paper-plugin.yml` and the JAR name.

	## Installation & Usage

	1) Drop the JAR into your server `plugins` folder.
	2) Start the server to generate `plugins/UnderwaterTrees/config.yml` and `plugins/UnderwaterTrees/lang/`.
	3) Adjust the config (materials, language, logging, auto‑reload, metrics, stability) as needed.
	4) Reload in-game or console:
	```
	/underwatertrees reload
	```

	Notes:
	- Materials are matched by name at runtime; unknown names are ignored silently.
	- With `auto-reload: true`, file changes are detected roughly every 5 seconds and reloaded.
	- Metrics can be disabled via `metrics-enabled: false` (or globally in `plugins/bStats/config.yml`).

	## Compatibility

	- Platform: Paper only (uses Paper APIs). Spigot is not supported.
	- API target: `api-version: 1.21`, built against Paper `1.21.10`.
	- Older versions: Not supported. Unknown materials on older servers are ignored.

	## Features

	- Place configured saplings on configured soil blocks underwater.
	- Per‑material toggles via `saplings:` and `soils:` boolean maps.
	- Fallback defaults if both sections are empty.
	- Localization with live reload (language files in `src/main/resources/lang`).
	- Live reload of config and messages via `/underwatertrees reload`.
	- Optional logging: `log-stats` and `log-detail`.
	- Auto‑reload on external config changes (`auto-reload`).
	- Optional bStats metrics (`metrics-enabled`) with custom charts (language, sapling count, soil count).
	- Stability protection (`protect-underwater-saplings`): prevents unintended breaking from physics/fluids while placement conditions remain valid.

	## Configuration (config.yml)

	| Key | Type | Default | Description |
	| --- | --- | --- | --- |
	| `language` | string | `en_US` | Language code found in `lang/` |
	| `require-water-above` | boolean | `false` | Only allow placement if the target has water above |
	| `log-stats` | boolean | `true` | Log counts of loaded soils/saplings |
	| `log-detail` | boolean | `false` | Log each loaded material |
	| `auto-reload` | boolean | `true` | Watch and auto‑reload `config.yml` (~5s) |
	| `metrics-enabled` | boolean | `true` | Enable anonymous bStats metrics (ID 28005) |
	| `protect-underwater-saplings` | boolean | `true` | Cancel harmful physics/fluids so adjacent underwater saplings don’t break if conditions are still valid |
	| `soils` | map<string, boolean> | varies | Enable flags for soil materials |
	| `saplings` | map<string, boolean> | varies | Enable flags for sapling materials |

	Adding materials: Put an uppercase Bukkit `Material` under `soils:` or `saplings:` with value `true`. Unknown names are ignored; this is safe across MC versions.

	### Stability Protection Details

	- Physics: If soil beneath is valid and (optionally) water above is present, `BlockPhysicsEvent` is canceled for the sapling, preventing neighbor updates from breaking it.
	- Fluids: `BlockFromToEvent` into a sapling block is canceled to avoid fluid‑induced breakage.
	- If soil becomes invalid or (with `require-water-above: true`) water is removed, protection no longer applies and the sapling can break naturally.

	## Commands & Permissions

	- `/underwatertrees reload`
	  - Permission: `underwatertrees.reload` (default: op)
	- Update notifications on join
	  - Permission: `underwatertrees.update` (default: op)

	## Update Check

	- Sources: Modrinth (primary) and Hangar (fallback). If both fail, it skips silently.
	- Config: `update-check`, `update-interval-hours`, `include-prereleases`, `filter-by-server-version`, `notify-console`, `notify-op-join`, `update-sources` (0=both, 1=Modrinth, 2=Hangar).
	- Version comparison:
	  - Numeric first (major > minor > patch)
	  - Release > pre‑release (`alpha` < `beta` < `rc`), with numeric indices (e.g., `beta2` > `beta1`)
	  - Letter suffix without hyphen (e.g., `1.0a`) is treated as newer than the plain release (`1.0`)

	## Config Defaults Merge (Auto‑add New Keys)

	On startup, manual reload, and auto‑reload, the plugin merges new default keys from the bundled `config.yml` into your existing `plugins/UnderwaterTrees/config.yml` without overwriting your values. New options (e.g., `protect-underwater-saplings`) appear automatically after updates.

	## Metrics (bStats)

	- Enabled via `metrics-enabled: true` (plugin ID 28005). Custom charts: `language`, `sapling_count`, `soil_block_count`.
	- Global opt‑out is available in `plugins/bStats/config.yml`.

	## Troubleshooting

	- Command not found / no permission: ensure plugin enabled and `underwatertrees.reload` granted (default op).
	- Materials not applied: names must match Bukkit `Material`; enable `log-detail` to verify.
	- New options missing: restart or `/underwatertrees reload` to trigger defaults merge.
	- Auto‑reload not triggering: ensure `auto-reload: true`; some environments have coarse timestamp updates.
	- Waterlogged visuals: the vanilla client cannot render waterlogged saplings; this is a client limitation.

	## License

	Apache License 2.0. See `LICENSE`.

	Third‑party notices are listed in `NOTICE` (e.g., bStats single‑file Metrics usage terms).
