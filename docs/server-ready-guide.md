# RuleGems Server-Ready Guide

这份指南面向想把 RuleGems 当作服务器基础玩法的服主。目标不是把所有配置都讲完，而是给出一条可以开服、公测、回滚和继续迭代的路径。

## 推荐定位

RuleGems 适合做“权力会流转”的服务器类型：

- 王权政治服：少数宝石控制核心权力，统治者可以委任职位。
- 制衡冲突服：强权宝石可以被特定撤销规则反制。
- 赛季争夺服：宝石定期散落或被夺取，玩家围绕权力迁移组织冲突。

如果服务器只是需要普通权限组管理，直接使用 LuckPerms 更合适。RuleGems 的价值在于把权限、组、效果、限次命令和撤销规则变成可争夺的游戏对象。

## 第一次开服

1. 安装 RuleGems，并优先安装 LuckPerms。
2. 启动一次服务器，让插件生成默认文件。
3. 停服并备份 `plugins/RuleGems/`。
4. 选择 `presets/` 中的玩法包，复制到对应目录。
5. 修改 `config.yml` 的 `random_place_range.world` 与坐标范围。
   如果要减少矿透通过区块方块数据发现宝石，可把 `gem_presentation.mode` 改为 `proximity_display`，并设置 `reveal_range` 与不小于它的 `hide_range`。
   如果要防止宝石被长期封存在无权限领地，可启用 `gem_escape.enabled`；重点校准全局轮次间隔 `min_interval` / `max_interval`、未移动门槛 `minimum_unmoved_duration`，以及局部移动的 `max_failed_rounds`。
6. 检查预设里的命令，例如 `/jail`、`/eco`、`/kingbroadcast`，替换成你服务器真实存在的命令。
7. 启动服务器，执行 `/rg doctor`。
8. 修复所有 ERROR，确认 WARN 是否可接受。
9. 用 2-3 名测试玩家跑完烟测清单。

## 烟测清单

- `/rg help` 只展示玩家有权限且已启用的功能。
- `/rg gems` 能看到所有预设宝石。
- `block` 模式下散落范围内能生成宝石方块；`proximity_display` 模式下远处保持空气，进入显示范围后出现显示实体。
- `proximity_display` 下左键显示实体能拾取宝石，跨越显示/隐藏边界不会持续闪烁。
- 在 `block` 与 `proximity_display` 间修改配置并 `/rg reload`，宝石原地切换且坐标、UUID 不变。
- 连续执行两次 `/rg scatter`，同类型且未超出新 `count` 的 UUID 保持不变，但持有者、权限和限次额度被重置。
- 启用逃逸后，未达到 `minimum_unmoved_duration` 的宝石不会移动；达到门槛后每个全局轮次最多移动一颗。
- 制造无法放置的局部候选，确认连续失败达到 `max_failed_rounds` 后只有目标宝石在 `random_place_range` 内重新散落，UUID 保持不变，广播明确提示旧情报失效。
- 拾取宝石后权限或效果按配置生效。
- `/rg redeem` 成功后目标宝石重新散落。
- 有 `redeem_requirements` 的宝石在缺少材料时拒绝兑换。
- `consumes` 材料只在兑换事件未取消后消耗。
- `/rg redeemall` 不绕过未允许的兑换前置条件。
- `/rg cabinet` 能看到可委任职位。
- `/rg appoint` 与 `/rg dismiss` 能正常授予和撤销职位。
- `/rg revoke-power list` 能看到撤销规则。
- `/rg revoke-power <规则> <玩家> <权力>` 的确认、取消、冷却都符合预期。
- `/rg reload` 后上述状态不丢失。
- 停服重启后已兑换权力、委任、撤销冷却和宝石位置仍然正确。

## 生产服建议

- 用 LuckPerms 作为权限后端。Bukkit 默认后端没有持久权限组模型。
- 如果玩家规模较大，优先评估 SQLite，并先在测试服跑导入。
- 把 `allow_op_escalation` 保持为 `false`，除非你完全信任对应限次命令。
- 给 `/rg scatter`、`/rg revoke`、`/rg reload` 严格管理员权限。
- `/rg scatter` 默认保留可复用 UUID，但它仍是全局状态重置操作；降低 `count` 或删除宝石类型会淘汰多余 UUID。
- 旧版 `gem_escape.min_interval` / `max_interval` 无需迁移键名，但升级后它们控制全服轮次而非每颗宝石的独立计时；发布前应按期望的总流动速率重新评估。
- 预设中的命令都是示例，发布前必须替换成服务器真实命令。
- 每次改 `gems/`、`powers/` 或 `features/` 后，先 `/rg doctor`，再小范围烟测。

## 发布前缺口

自动化测试不能替代真实服务器验证。面向公开大服前，至少完成：

- Paper 启动、兑换、委任、撤销、重载和停服保存烟测。
- Folia 启动、粒子任务、物品扫描、兑换和停服保存烟测。
- 分别在 Minecraft 1.19.4+ 与一个旧版兼容目标上测试 `proximity_display` 的接近显示、远离隐藏、拾取、区块卸载和双向热切换。旧版 ArmorStand 后端不能严格逐玩家隐藏，应把实体雷达视为剩余风险。
- 旧配置升级演练：确认备份目录生成，确认回滚路径可用。
- 一次 24 小时测试服运行，观察是否有宝石数量漂移、权限残留或调度错误。
