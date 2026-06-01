# Kingdom Power Preset

这个预设把 RuleGems 配成一个轻量王权服框架：王冠控制最高权力，骑士剑提供治安能力，财政印章提供经济管理入口，审判石用于制衡已经兑换的权力。

## 文件

- `gems/kingdom.yml`: 宝石定义。
- `powers/kingdom.yml`: 可复用 power 模板与委任结构。
- `features/revoke.yml`: 审判石撤销规则。

## 推荐搭配

- LuckPerms：用于 `permission_groups` 和长期权限组管理。
- CoreProtect：用于审计类权限示例。
- CMI 或 EssentialsX：用于示例命令，可按服务器实际命令替换。

## 开服流程

1. 复制本预设文件到 `plugins/RuleGems/` 对应目录。
2. 确认 `config.yml` 的 `random_place_range.world` 是真实世界名。
3. 给服主或测试员 `rulegems.admin`。
4. 执行 `/rg reload`。
5. 执行 `/rg doctor`，修复 ERROR 和 WARN。
6. 用小范围玩家测试 `/rg gems`、`/rg redeem`、`/rg rulers`、`/rg cabinet`、`/rg revoke-power list`。

## 玩法核心

- `crown`: 兑换后成为最高统治者，可任命骑士和财政官。
- `oath_seal`: 王冠兑换材料之一，保证王权不是单颗宝石瞬间完成。
- `knight_sword`: 提供治安能力，可由王冠任命体系扩展。
- `treasury_seal`: 财政能力入口。
- `judgment`: 撤销制衡宝石，可撤销 `crown`、`knight_sword`、`treasury_seal`。

