# RuleGems

[English](README_en.md)|中文<br>
[Discord](https://discord.com/invite/7tJeSZPZgv) | [QQ频道](https://pd.qq.com/s/1n3hpe4e7?b=9)

一个用"宝石收集"来流转玩家权限的轻量插件，支持 Folia 与多版本。

## 安装
1. 将 JAR 放入 `plugins` 目录
2. 启动服务器自动生成配置
3. 在 `config.yml` / `rulegems.yml` 中按需调整

## 命令
- 所有 `/rulegems ...` 命令均可用别名 `/rg ...`（见 plugin.yml 的 `aliases: [rg]`）
- `/rulegems place <gemId> <x|~> <y|~> <z|~>` 将指定宝石实例放置到坐标
- `/rulegems tp <gemId>` 传送到指定宝石位置
- `/rulegems revoke <玩家>` 强制撤销指定玩家的所有宝石权限（管理员干预）
- `/rulegems reload` 重载配置
- `/rulegems rulers` 查看当前权力持有者
- `/rulegems gems` 查看宝石状态
- `/rulegems scatter` 随机散布全部宝石
- `/rulegems redeem` 主手持宝石时兑换单颗
- `/rulegems redeemall` 集齐后一次性兑换
- `/rulegems history [行数] [玩家名]` 查看宝石历史记录

## 权限
- `rulegems.admin` 管理指令（默认 OP）
- `rulegems.redeem` 兑换单颗（默认 true）
- `rulegems.redeemall` 兑换全部（默认 true）
- `rulegems.rulers` 查看当前持有者（默认 true）

## 兼容性
- 服务器：Spigot/Paper 1.16+；Folia 兼容
- 可选依赖：Vault（权限后端）

## 逻辑
该插件允许服务器定制不同种类的权力宝石，每一种可以有指定数量和指定的权限与指令（指令可以限制次数）。每颗宝石都是唯一的。每颗宝石都随时存在于服务器中，意味着它只能处于以下状态之一：被放置或处于线上玩家的背包中。
插件有三种应用宝石的模式：
1. inventory_grants
2. redeem_enabled
3. full_set_grants_all

这三种模式并不互斥，可以搭配组合。

### inventory_grants
玩家破坏并获得宝石（自动放入背包）即可获得该宝石的权限与限次指令使用权。玩家下线/被杀死/或将宝石放置，这些特权自动消失。

限次指令与“最近持有者”绑定：
- 同一玩家再次持有同类宝石时会延续剩余次数（不重置）。
- 当宝石易主到新玩家时，上一任在该类型上的限次记录仅在其不再拥有任何同类实例时被清除（“最后一件才撤”）。

互斥：若玩家先获得宝石A，后获得与A相斥的宝石B，则B的特权不会触发，除非先放弃A（互斥仅在 inventory_grants 与 redeem 模式生效，redeem_all 忽略互斥）。

### redeem_enabled
玩家主手持宝石执行 `/rg redeem` 可获得该宝石的权限与限次指令，同时该宝石会重新散落。

当他人之后兑换了“同一颗”宝石（按 UUID 区分）时，旧持有者仅在其不再拥有该类型的其他实例时才会被撤回该类型的权限与限次（“最后一件才撤”）。若玩家先兑换了宝石A，再尝试兑换与A相斥的宝石B，则兑换会被拒绝（互斥仅在 inventory_grants 与 redeem 生效）。

### full_set_grants_all
当玩家集齐全部“种类”宝石在背包中（每个种类至少 1 件即可，插件支持每类宝石 `count > 1` 同时存在），使用 `/rg redeemall` 将获得全部宝石的权限与指令（忽略互斥）以及 `redeem_all` 的额外特权。直到下次另一位玩家成功 `redeemall`，旧的 full set 持有者才会被清理全部特权（含额外特权）。

## 特性与配置要点
- 每颗宝石唯一：每件宝石有独立 UUID（实例级归属），可通过 `/rulegems place <gemId> ...` 精确放置。
- 每类宝石数量：`gems.<key>.count: <int>`，散落与补齐按 count 生成；“集齐种类”判定为每个 key 至少 1 件。
- 互斥：`gems.<key>.mutual_exclusive: [otherKey, ...]`；仅在 inventory_grants 与 redeem 生效，redeem_all 忽略互斥。
- 限次指令：`gems.<key>.command_allows` 支持两种写法：
  - 映射：`command_allows.<label>: <uses>`（如 `fly: 3`）
  - 列表：`- { commands: "/fly"|[...], time_limit: <uses> }`
  - 特殊值：`time_limit: -1` 表示无限次（使用不扣减）。
- 持有与扣减：
  - 开启 inventory_grants 时：执行限次指令要求当前持有对应类型宝石，并从该类型额度中扣减；
  - redeem_all 的额外限次归入特殊 key `ALL`，不要求持有即可使用。
- 发放与撤回（按类型计数）：
  - 某玩家对某类型从 0→1 件时：发放该类型权限/组，并初始化限次额度（若已存在额度记录则延续剩余，不重置）。
  - 从 1→0 件时：撤回该类型权限/组，并删除该类型限次额度记录。
- redeem_all 额外特权：根级 `redeem_all` 节支持：
  - `broadcast/titles/sound`（已从旧 `titles.redeem_all` 扁平化）
  - `permissions`: 仅在 `redeemall` 成功时授予的额外权限
  - `command_allows`: 仅在 `redeemall` 成功时授予的额外限次指令（语法同上）

