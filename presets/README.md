# RuleGems Presets

这些预设是给服主开服时直接参考或复制的玩法包。它们不会被插件自动加载。

## 使用方式

1. 先备份 `plugins/RuleGems/`。
2. 选择一个预设目录。
3. 将预设里的 `gems/*.yml` 复制到 `plugins/RuleGems/gems/`。
4. 将预设里的 `powers/*.yml` 复制到 `plugins/RuleGems/powers/`。
5. 如预设包含 `features/*.yml`，按需复制到 `plugins/RuleGems/features/`。
6. 检查 `config.yml` 的 `random_place_range.world` 与坐标范围。
7. 执行 `/rg reload`，再执行 `/rg doctor`。

## 当前预设

- `kingdom-power`: 王权、骑士、财政、审判与撤销制衡，适合权力政治服。

