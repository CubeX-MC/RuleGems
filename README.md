# RuleGems

[English](README_en.md) | 中文<br>
[Discord](https://discord.com/invite/7tJeSZPZgv) | [QQ频道](https://pd.qq.com/s/1n3hpe4e7?b=9)

一个用"宝石收集"来流转玩家权限的轻量插件，支持 Folia 与多版本。

## 安装
1. 将 JAR 放入 `plugins` 目录
2. 启动服务器自动生成配置
3. 在 `config.yml` 与 `gems/`、`powers/`、`features/` 目录配置中按需调整

## 命令
- 所有 `/rulegems ...` 命令均可用别名 `/rg ...`（见 plugin.yml 的 `aliases: [rg]`）
- `/rulegems place <gemId> <x|~> <y|~> <z|~>` 将指定宝石实例放置到坐标
- `/rulegems tp <gemId>` 传送到指定宝石位置
- `/rulegems revoke <玩家>` 强制清理指定玩家的宝石权限与限次额度（管理员干预）。若启用了 `inventory_grants` 且玩家仍持有宝石，下一次背包重算时权限会再次授予。
- `/rulegems reload` 重载配置
- `/rulegems rulers` 查看当前权力持有者
- `/rulegems gems` 查看宝石状态
- `/rulegems gui` 打开 GUI 界面
- `/rulegems scatter` 收回所有宝石并随机散布
- `/rulegems redeem` 主手持宝石时兑换单颗
- `/rulegems redeemall` 集齐所有种类后一次性兑换
- `/rulegems history [行数] [玩家名]` 查看宝石历史记录，可选过滤玩家
- `/rulegems setaltar <gemKey>` 在当前位置设置该宝石的祭坛坐标
- `/rulegems removealtar <gemKey>` 移除该宝石的祭坛坐标
- `/rulegems appoint <权限集> <玩家>` 任命玩家获得指定权限集
- `/rulegems dismiss <权限集> <玩家>` 撤销玩家的权限集任命
- `/rulegems appointees [权限集]` 查看被任命者列表

## 权限
- `rulegems.admin` 管理指令（默认 OP）
- `rulegems.redeem` 兑换单颗（默认 true）
- `rulegems.redeemall` 兑换全部（默认 true）
- `rulegems.rulers` 查看当前持有者（默认 true）
- `rulegems.gems` 查看宝石列表（默认 true）
- `rulegems.help` 查看帮助信息（默认 true）
- `rulegems.navigate` 使用指南针导航到最近宝石（默认 false）
- `rulegems.appoint.<权限集>` 任命其他玩家获得指定权限集

## 兼容性
- 服务器：Spigot / Paper 1.16+；兼容 Folia
- 可选依赖：Vault（权限后端）

## 逻辑
该插件允许服务器定制不同种类的权力宝石，每一种可以有指定数量和指定的权限与指令（指令可以限制次数）。每颗宝石都是唯一的。每颗宝石都随时存在于服务器中，意味着它只能处于以下状态之一：被放置或处于线上玩家的背包中。
插件有五种应用宝石的模式：
1. inventory_grants
2. redeem_enabled
3. full_set_grants_all
4. place_redeem_enabled
5. hold_to_redeem_enabled

以上模式并不互斥，可以搭配组合。

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
- 限次指令：`gems.<key>.command_allows` 支持映射与列表两种写法：
  - 映射：`command_allows.<label>: <uses>`（如 `fly: 3`）
  - 列表：`- { commands: "/fly"|[...], time_limit: <uses> }`
  - 特殊值：`time_limit: -1` 表示无限次（使用不扣减）；`redeem_all.command_allows` 采用相同语法，额度记录在虚拟 key `ALL` 下。
- 持有与扣减：
  - 开启 inventory_grants 时：执行限次指令要求当前持有对应类型宝石，并按该类型额度扣减；
  - `redeem_all` 额外限次不要求持有，对应 key 始终为 `ALL`。
- 发放与撤回（按类型计数）：
  - 某玩家对某类型从 0→1 件时：发放该类型权限/组，并初始化限次额度（若已存在额度记录则延续剩余，不重置）。
  - 从 1→0 件时：撤回该类型权限/组，并删除该类型限次额度记录。
- redeem_all 额外特权：根级 `redeem_all` 节支持：
  - `broadcast/titles/sound`（已从旧 `titles.redeem_all` 扁平化）
  - `permissions`: `redeemall` 成功时的额外权限
  - `command_allows`: `redeemall` 成功时的额外限次指令（语法同上）
- 与 Vault 配合时，权限组的授予 / 撤销通过当前权限后端执行。
- 额外兑换方式：
  - `grant_policy.place_redeem_enabled: true` 启用祭坛放置兑换（配合 `/rulegems setaltar`）
  - `grant_policy.hold_to_redeem_enabled: true` 启用长按右键兑换（`hold_to_redeem` 配置）

## 扩展功能

### 宝石导航 (Navigate)
持有 `rulegems.navigate` 权限的玩家可以使用指南针右键导航到最近的宝石位置。
- 配置文件：`features/navigate.yml`
- 启用后，玩家右键指南针会显示最近宝石的方向和距离

### 委任系统 (Appoint)
允许统治者将部分权限委任给其他玩家，形成权力树结构。

#### 核心概念
- **权限集 (Permission Set)**：预定义的一组权限、限次命令和可选的继承关系
- **任命 (Appoint)**：统治者将权限集授予其他玩家
- **级联撤销 (Cascade Revoke)**：当任命者失去权限时，其任命的所有人也会被撤销（可配置）
- **条件系统 (Conditions)**：权限集可设置生效条件（时间/世界），仅在满足条件时生效

#### 配置文件
`features/appoint.yml` 定义权限集：
```yaml
enabled: true
cascade_revoke: true  # 级联撤销（连坐制）
condition_refresh_interval: 30  # 条件刷新间隔（秒），设为0禁用定时刷新

permission_sets:
  knight:  # 权限集 key，对应权限节点 rulegems.appoint.knight
    display_name: "&6骑士"
    description: "拥有基础战斗相关权限"
    max_appointments: 3  # 每个任命者最多任命人数，-1为无限
    permissions:
      - example.permission1
    command_allows:  # 限次命令（语法同宝石）
      - command: "/kit warrior"
        time_limit: 3
    delegate_permissions:  # 可再次委任的权限
      - rulegems.appoint.squire
    inherits:  # 继承其他权限集
      - squire
    on_appoint:  # 任命时执行的命令
      - "console: broadcast %player% 任命 %target% 为骑士"
    on_revoke:  # 撤销时执行的命令
      - "console: broadcast %target% 的骑士头衔被撤销"
    
    # 条件配置（可选）
    conditions:
      time:
        enabled: true
        type: day  # always / day / night / custom
        from: 0    # 自定义时间范围（仅type=custom时生效）
        to: 12000  # Minecraft时间: 0=日出, 6000=正午, 12000=日落
      worlds:
        enabled: true
        mode: whitelist  # whitelist / blacklist
        list:
          - world
          - world_nether
```

#### 条件系统
权限集可设置生效条件，当条件不满足时，该权限集的权限和限次命令暂时失效：

**时间条件** (`conditions.time`)：
- `always`：始终生效（默认）
- `day`：仅白天生效（0-12000 tick）
- `night`：仅夜晚生效（12000-24000 tick）
- `custom`：自定义时间范围（通过 `from` 和 `to` 指定）

**世界条件** (`conditions.worlds`)：
- `whitelist`：仅在指定世界生效
- `blacklist`：在指定世界之外生效

条件刷新时机：
1. 玩家切换世界时立即刷新
2. 根据 `condition_refresh_interval` 定时刷新（用于时间条件）

#### 权力树示例
```
国王（宝石持有者，拥有 rulegems.appoint.duke）
└── 公爵（被任命，通过 delegate_permissions 拥有 rulegems.appoint.knight）
    └── 骑士（被任命）
```
当国王失去宝石 → 公爵被级联撤销 → 骑士也被级联撤销

#### GUI 支持
在统治者列表 GUI 中点击任意统治者可查看其任命的所有玩家及详细信息。

