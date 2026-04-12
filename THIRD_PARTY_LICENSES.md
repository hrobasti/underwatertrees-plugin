# Third-Party Licenses

| Dependency | Version (current) | License | Upstream | Notes |
| --- | --- | --- | --- | --- |
| net.kyori:adventure-text-minimessage | 4.17.0 | MIT License | <https://github.com/KyoriPowered/adventure> | Provides MiniMessage formatting for all localized chat/console output. |
| bStats Metrics (embedded class) | 3.2.1 | MIT License | <https://github.com/Bastian/bStats-Metrics> | The single-file Metrics class is included and relocated. Per bStats terms, it must not be modified (except package relocation), obfuscated, or reformatted. For details, see the upstream repository and its terms. `Metrics.java` relocated into `com.github.hrobasti.underwatertrees.metrics` per upstream guidance. |

Full license texts ship inside the plugin under `licenses/`:

- `licenses/mit.txt`

> ℹ️ UnderwaterTrees also bundles the in-house `turtle-lib` project via the composite Gradle build. Because it is proprietary and maintained by the same author, it is documented in `README.md` rather than in this list. See `../turtle-lib/LICENSE` for its terms.

Please keep these notices intact when redistributing UnderwaterTrees or derivative works.
