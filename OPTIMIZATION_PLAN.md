# RuleGems 配置与多宝石兑换优化计划

> 重写于 2026-05-21。
> 目标: 保持 gem-centric 架构, 清理配置语法, 并把 `redeem_requirements`
> 扩展为可表达同类多颗、异类多颗、混合配方与多套等价配方的统一机制。
>
> 兼容策略: 旧版插件目前只有两个服务器仍在使用, 因此本计划不追求长期无痛兼容。
> 升级前先备份, 运行时做少量粗兼容和明确警告; 对复杂旧写法允许要求服主手动改配置。

当前状态: P1-P7 已按本计划落地并通过自动化验证。继续推进时不应从
P1 重新施工, 而应先完成 P8 的 Paper/Folia 烟测, 再基于烟测结果或新的
优化目标追加下一阶段计划。

---

## 1. 总体决策

### 1.1 保持 gem-centric, 不做 power-centric

不采用 `powers/` 作为一等配方目录、不引入 `PowerGrantInstance`、
`RecipeEngine`、`RedemptionRecord` 或 power 级别数据重键。

理由:

- 当前真实需求是"多个宝石兑换一个能力": 同类多颗、异类多颗、混合配方、
  一个能力多套等价配方。
- 现有 gem-centric 模型加上增强后的 `redeem_requirements` 足够表达该需求。
- power-centric 会改动归属、撤销、限次命令、数据文件和 GUI 语义, 对当前目标过重。
- RuleGems 当前的玩家体验仍是"兑换某颗宝石获得能力", 不需要把 power 暴露成独立身份。

若未来需要"power 作为一等身份"来做统一撤销、跨宝石统一计数或互斥, 再单独立项。

### 1.2 兼容级别降级为 C1: 备份优先, 粗兼容

旧服数量很少, 所以不做复杂自动迁移器。升级策略如下:

1. 插件启动或 reload 时, 如果检测到旧配置写法, 先备份:
   - `config.yml`
   - `gems/`
   - `powers/`
   - `features/`
   - `data/`
2. 备份目录建议为 `backups/config-optimization-<yyyyMMdd-HHmmss>/`。
3. 能低成本读取的旧键继续读取并输出 warning。
4. 不再静默接受高歧义写法; 无法安全理解时 fail fast, 告诉服主改哪个字段。
5. 不自动重写配置文件。默认资源与 README 只展示新语法。
6. 回滚方式是恢复备份目录并换回旧 jar。

### 1.3 行为边界

保持不变:

- 兑换入口: `/rg redeem`, 长按兑换, 祭坛兑换, `/rg redeemall`。
- 权限、Vault/LuckPerms/Bukkit 组、药水效果的授予和撤销机制。
- 已兑换归属数据、限次命令数据、宝石 UUID 数据的持久化形状。
- `PowerStructureManager` 的引用计数与效果来源处理。
- `GemAllowanceManager` 以 gem UUID 为桶的限次命令额度模型。

允许改变:

- 新版本默认配置语法。
- Java 内部模型 API。
- 已废弃构造函数。
- 高歧义旧配置写法的容错程度。
- 文档和示例中的推荐配置方式。

---

## 2. 当前实现事实

| 事实 | 当前代码位置 | 对计划的影响 |
|------|--------------|--------------|
| `redeem_requirements` 已存在, 但字段是 `List<String>` | `RedeemRequirements` | 需要升级为成分对象与配方对象 |
| parser 目前用 `toStringList` 解析 requirements | `GemDefinitionParser.parseRedeemRequirements` | map 写法会被错误转成字符串, 必须改 parser |
| 当前 `consumes` 会排除正在兑换的主手/祭坛 gem UUID | `GemManager.collectHeldGemIds(player, targetGemId)` | 与自引用 amount 需求相反, 必须重写匹配算法 |
| `requires_redeemed` 当前只看 redeemed key set | `GemManager.normalizedRedeemedKeys` | `amount > 1` 需要按 owner key count 计数 |
| `redeemAll` 对 requirements 有 `allow_redeem_all` 特殊规则 | `GemManager.evaluateRedeemRequirements` | 新 recipe 模型必须保留该规则 |
| `power:` 支持字符串、列表、内联 map、base/template、根节点隐式合并 | `GemDefinitionParser.parsePowerStructure` / `resolveGemPower` | 需要收敛语法并处理旧配置 |
| `vault_group` / `vault_groups` / `permission_group` 都写入同一个组 list | `GemDefinitionParser.parsePowerStructure` | 新键改为 `permission_groups`, 旧键粗兼容 |
| `PowerStructure` 与 `GemDefinition` 有废弃构造函数 | model 包 | 可以删除, 但要同步测试 |
| 已有 `GemDefinitionParserTest` / `RedeemRequirementsTest` / `GemManagerTest` | test 包 | P1-P5 已具备自动化回归基础; 后续重点是实服烟测 |

---

## 3. 新配置语法

### 3.1 `power` 语法

新版本只推荐两类写法。

#### 模板引用

```yaml
justice:
  power: justice

ruler:
  power: [fighter, healer, inspector]
```

#### 内联 map

```yaml
emperor:
  power:
    base: admin_basic
    permissions:
      - emperor.decree
    command_allows:
      - command: /decree
        execute:
          - "console:say %player% issued a decree"
        time_limit: 3
        cooldown: 60
```

规范:

- `base` 保留, 支持字符串或字符串列表。
- `template` 不再作为新语法出现。
- `power` 外层的 gem 根节点不再作为隐式 `PowerStructure` 合并来源。
- 如果想给某颗 gem 增加权限、组、效果或限次命令, 必须写进 `power:` map。

粗兼容:

- `template` 读取为 `base`, 输出 warning。
- 根节点存在 `permissions` / `command_allows` / `effects` / `vault_group` /
  `vault_groups` / `permission_group` 时:
  - 若没有 `power`, 读取为内联 power 并 warning。
  - 若已有 `power`, 本轮仍合并一次并 warning, 但默认配置与文档不再展示。
  - 后续大版本可删除该兼容。

### 3.2 权限组键

新键统一为:

```yaml
power:
  permission_groups:
    - noble
    - ruler
```

适用位置:

- gem 的 `power:` map。
- powers 模板。
- appoint power map。
- `redeem_all` 额外能力。

粗兼容:

- `vault_group: ruler` -> `permission_groups: [ruler]`
- `vault_groups: [a, b]` -> `permission_groups: [a, b]`
- `permission_group: ruler` -> `permission_groups: [ruler]`
- 多个旧键同时出现时合并去重, 输出一次 warning。

### 3.3 `redeem_requirements` 基本形状

旧字符串列表继续有效:

```yaml
territory:
  power: territory_ruler
  redeem_requirements:
    consumes: [crown, scepter, orb]
```

新成分对象支持 `amount`:

```yaml
dragon:
  power: dragon_power
  redeem_requirements:
    consumes:
      - gem: dragon
        amount: 3
      - gem: scale
        amount: 2
```

字符串简写等价于:

```yaml
- gem: crown
  amount: 1
```

### 3.4 `any_of` 多套等价配方

顶层无 `any_of` 时, 整个 `redeem_requirements` 是一套 recipe。
存在 `any_of` 时, 每个元素是一套完整 recipe, 满足任意一套即可。

```yaml
dragon:
  power: dragon_power
  redeem_requirements:
    any_of:
      - consumes:
          - { gem: dragon, amount: 3 }
      - consumes:
          - { gem: dragon, amount: 1 }
          - { gem: egg, amount: 1 }
```

规则:

- `allow_redeem_all` 与 `failure_message` 属于整个 requirements, 不属于单条 recipe。
- 顶层 recipe 字段与 `any_of` 不应混用。
- 如果混用, parser 输出 warning, 并优先使用 `any_of`。

### 3.5 Recipe 字段语义

| 字段 | 是否消耗 | 是否支持 amount | 计数来源 | 说明 |
|------|----------|-----------------|----------|------|
| `requires_held` | 否 | 是 | 当前背包 + 主手/副手目标实例 | 玩家必须持有指定数量 |
| `consumes` | 是 | 是 | 当前背包 + 主手/副手目标实例 | 成功兑换后消耗并重新散落 |
| `requires_redeemed` | 否 | 是 | 当前玩家已兑换归属计数 | 玩家必须已经兑换过指定数量 |
| `requires_any` | 否 | 否, legacy only | 持有或已兑换 key set | 旧 OR 语义, 保留兼容 |
| `requires_count` + `requires_count_from` | 否 | 否, legacy only | 持有或已兑换 key set | 旧 N-of-M 语义, 保留兼容 |

推荐新配置用 `any_of` 表达 OR, 不再新增复杂的 `requires_any` 用法。

### 3.6 自引用和实例匹配规则

必须钉死以下规则, 并写进测试:

1. 主手或祭坛放置的 gem 只决定当前兑换哪颗 gem 的 recipe。
2. 所有 `amount` 都按物理实例 UUID 匹配。
3. 同一个 UUID 只能满足一个成分槽。
4. 正在兑换的目标 gem 也算一个可用实例。
5. `consumes: [{ gem: dragon, amount: 3 }]` 表示总共需要 3 颗 dragon,
   其中可以包含正在兑换的那一颗。
6. 兑换目标 gem 本身由主兑换流程移除和重新散落; 匹配结果里的额外消耗列表不能重复包含它。
7. 副手和背包内容需要按 UUID 去重, 避免同一 ItemStack 被重复枚举。

示例:

| 背包状态 | recipe | 结果 |
|----------|--------|------|
| 主手 dragon A, 背包 dragon B/C | consumes dragon x3 | 允许; 额外消耗 B/C |
| 主手 dragon A, 背包 dragon B | consumes dragon x3 | 拒绝 |
| 主手 dragon A, 背包 scale B/C | consumes dragon x1 + scale x2 | 允许; 额外消耗 B/C |
| 主手 dragon A, 背包 egg B | any_of dragon x3 OR dragon x1 + egg x1 | 第二套允许; 额外消耗 B |

---

## 4. 新 Java 模型

### 4.1 `RedeemRequirements`

建议重构为:

```java
public final class RedeemRequirements {
    public static final RedeemRequirements NONE = ...

    private final List<RedeemRecipe> recipes;
    private final boolean allowRedeemAll;
    private final String failureMessage;

    public boolean hasRequirements();
    public List<RedeemRecipe> getRecipes();
    public boolean isAllowRedeemAll();
    public String getFailureMessage();
}
```

过渡策略:

- P2 引入新 API。
- P3 完成 `GemManager` 迁移后删除旧 `getConsumes()` 等字符串 list getter。
- 如果测试迁移成本高, 可短期保留 deprecated getter, 但最终不依赖它们。

### 4.2 `RedeemRecipe`

```java
public final class RedeemRecipe {
    private final List<RedeemIngredient> requiresHeld;
    private final List<RedeemIngredient> consumes;
    private final List<RedeemIngredient> requiresRedeemed;
    private final List<String> requiresAny;
    private final int requiresCount;
    private final List<String> requiresCountFrom;
}
```

约束:

- list 全部不可变。
- `amount <= 0` 统一归一为 `1`, 并 warning。
- gem key 统一 trim, 空 key 忽略并 warning。

### 4.3 `RedeemIngredient`

```java
public final class RedeemIngredient {
    private final String gemKey;
    private final int amount;
}
```

可实现为普通 final class 或 Java record。若使用 record, 确认项目的 Java 17 编译设置保持不变。

### 4.4 匹配结果

扩展 `RedeemRequirementResult`:

```java
public final class RedeemRequirementResult {
    private final boolean allowed;
    private final String message;
    private final boolean messageIsLanguageKey;
    private final Map<String, String> placeholders;
    private final List<UUID> consumedGemIds;
    private final RedeemRecipe matchedRecipe;
}
```

`consumedGemIds` 只包含额外要消耗的 gem UUID, 不包含当前兑换目标 UUID。

---

## 5. 实现阶段

### P0 文档合并与旧计划清理

状态: 本计划执行时完成。

任务:

- 用本文件替代旧的 `OPTIMIZATION_PLAN.md`。
- 删除 `IMPROVEMENT_PLAN.md`。
- 不把旧改进计划的历史任务继续混在当前优化计划中。

验收:

- 仓库只保留一个当前优化计划。

### P1 升级备份与 legacy 检测

风险: R2 配置读取 / 运维升级。

文件:

- `src/main/java/org/cubexmc/update/BackupHelper.java`
- `src/main/java/org/cubexmc/manager/ConfigManager.java`
- `src/main/java/org/cubexmc/manager/GemDefinitionParser.java`

任务:

1. 增加 legacy syntax 检测:
   - gem 根节点 power 字段外出现 power 相关键。
   - `template`。
   - `vault_group` / `vault_groups` / `permission_group`。
   - `redeem_requirements` 中出现 map list, 但当前 parser 尚未升级。
2. 检测到 legacy syntax 时, 调用已有备份能力或补一个小型备份方法。
3. 备份只复制文件, 不修改原文件。
4. 每次启动最多输出一组汇总 warning, 避免刷屏。
5. warning 文案包含:
   - 旧字段名。
   - 新字段名。
   - 备份目录。
   - "future version may remove this compatibility"。

测试:

- 新增 `GemDefinitionParserTest` 或 `ConfigManagerTest` 覆盖 legacy 检测。
- 不要求测试真实复制整个插件目录, 可 mock backup helper 或测试路径选择。

验收:

```text
mvn "-Dtest=GemDefinitionParserTest" test
```

### P2 配置语法清理

风险: R2 配置解析。

文件:

- `GemDefinitionParser`
- `PowerStructure`
- `PowerStructureTest`
- 默认 `gems/gems.yml`
- 默认 `powers/powers.yml`
- 默认 `config.yml`

任务:

1. 新增 `permission_groups` 解析。
2. 保留旧组键粗兼容并输出 warning。
3. 将 `template` 作为 deprecated alias 读取为 `base`。
4. 修改 `resolveGemPower`:
   - canonical: 只解析 `power` 字段。
   - legacy: 旧根节点 power 字段仍可合并一次并 warning。
5. 默认资源全部改成新语法。
6. `redeem_all.permission_group` 改为 `redeem_all.permission_groups`。
7. 内部仍使用 `PowerStructure.getVaultGroups()` 存储组列表; 本轮不重命名 Java API。

测试:

- `power: templateName`。
- `power: [a, b]`。
- `power: { base: a, permissions: [...] }`。
- `permission_groups`。
- 三个旧组键兼容。
- `template` warning 兼容。
- 根节点隐式 power warning 兼容。

验收:

```text
mvn "-Dtest=GemDefinitionParserTest,PowerStructureTest" test
```

### P3 `redeem_requirements` 模型与 parser

风险: R2 配置解析 / model API。

文件:

- `RedeemRequirements`
- 新增 `RedeemRecipe`
- 新增 `RedeemIngredient`
- `RedeemRequirementResult`
- `GemDefinitionParser`
- `RedeemRequirementsTest`

任务:

1. 新增 ingredient parser:
   - string -> `{ gem: value, amount: 1 }`
   - map -> read `gem` + `amount`
   - invalid item -> warning + skip
2. 新增 recipe parser:
   - 顶层 recipe。
   - `any_of` recipe list。
3. 保留旧字段:
   - `requires_any`
   - `requires_count`
   - `requires_count_from`
4. `allow_redeem_all` 默认保持当前行为:
   - 没有 requirements 时 `NONE` 允许。
   - 有 requirements 且未显式写 `allow_redeem_all` 时不允许 `redeemAll` 绕过。
5. `failure_message` 保持当前语义。
6. `hasRequirements()` 必须在存在任意 recipe 内容时返回 true。
7. list 和 recipe 对象不可变。

测试:

- null config 返回 `RedeemRequirements.NONE`。
- 旧字符串列表解析为 amount 1。
- map ingredient 解析 amount。
- `amount <= 0` 归一为 1。
- `any_of` 解析为多 recipe。
- 顶层字段与 `any_of` 混用时优先 `any_of`。
- legacy `requires_any` / `requires_count` 保留。

验收:

```text
mvn "-Dtest=RedeemRequirementsTest" test
```

### P4 兑换匹配器

风险: R2/R3 兑换状态机。

文件:

- `GemManager`
- 可选新增 `RedeemRequirementMatcher`
- `GemManagerTest`

建议新增小类:

```java
final class RedeemRequirementMatcher {
    RedeemRequirementResult evaluate(Player player, GemDefinition def, UUID targetGemId, RedeemContext context);
}
```

如果为了少改文件, 可以先作为 `GemManager` private helper 实现, 但必须让算法边界清晰。

任务:

1. 构建 held instance pool:
   - inventory contents。
   - offhand。
   - 显式加入 `targetGemId`。
   - 按 UUID 去重。
   - 按 normalized gem key 分组。
2. 构建 redeemed count:
   - 优先使用 `GemPermissionManager` 的 owner key count。
   - 没有 count 时 fallback 到 redeemed key set, count 视为 1。
3. 对每套 recipe 依次尝试:
   - 校验 `requires_held`。
   - 校验 `requires_redeemed`。
   - 校验 legacy `requires_any`。
   - 校验 legacy `requires_count`。
   - 分配 `consumes` 实例。
4. 消耗分配规则:
   - 每个 UUID 只能选择一次。
   - 允许选择 `targetGemId`。
   - 返回前从额外消耗列表移除 `targetGemId`。
5. `any_of` 顺序:
   - 按配置顺序选择第一套可满足 recipe。
   - 所有 recipe 失败时, 返回最后一个最具体失败原因; 如果无法判断, 用通用缺失提示。
6. 失败提示:
   - 继续使用当前语言 key。
   - amount 缺失时 placeholder 加 `amount`。
   - 如果语言文件暂不新增 amount 文案, 仍可用旧 `%gem%` 文案, 但 README 要说明。

测试:

- self consumes amount 3 时主手计入。
- self consumes amount 3 只有两颗时拒绝。
- 混合配方正确额外消耗。
- 同一 UUID 不可被两个成分重复使用。
- `any_of` 第一套失败第二套成功。
- `requires_held` amount。
- `requires_redeemed` amount。
- legacy `requires_any` 和 `requires_count` 仍可用。

验收:

```text
mvn "-Dtest=GemManagerTest,RedeemRequirementsTest" test
```

### P5 兑换入口接入

风险: R3 运行时兑换流程。

文件:

- `GemManager`
- `GemConsumeListener` 只需确认复用 `redeemGemInHand`, 通常不改。
- `GemManagerTest`

任务:

1. `/rg redeem`:
   - 在事件触发前评估 requirements。
   - 事件取消时不消耗额外 gem。
   - 事件通过后, 主目标和额外 consumes 都重新散落。
2. 长按兑换:
   - 继续复用 `/rg redeem` 核心路径。
   - 不新增第二套逻辑。
3. 祭坛兑换:
   - 保持已修复的事务式行为。
   - requirements 失败时取消原始 block place, 不改状态。
   - event 取消时不消耗额外 gem。
4. `/rg redeemall`:
   - 保留 `allow_redeem_all` 语义。
   - requirements 不允许时整体拒绝, 不进入 full-set 两阶段提交。
   - 不在 `redeemAll` 中消费 recipe `consumes`, 除非明确设计为允许; 本计划保持当前语义:
     `allow_redeem_all: true` 表示 full set 可绕过该 gem 的额外 requirement。
5. `consumeRequirementGems`:
   - 对额外消耗 UUID 去重。
   - 若额外消耗 UUID 已经是目标 UUID, 跳过。
   - 消耗后调用 `recalculateGrants(player)`。

测试:

- 手持兑换成功额外消耗 N 颗。
- 事件取消不消耗。
- 祭坛失败不放置未登记方块。
- `redeemAll` 遇到不允许 requirements 时全局状态不变。
- `allow_redeem_all: true` 继续允许 full-set。

验收:

```text
mvn "-Dtest=GemManagerTest" test
```

### P6 删除废弃构造函数和死 API

风险: R1/R2 内部 API 清理。

文件:

- `GemDefinition`
- `PowerStructure`
- `PowerStructureTest`
- 可能受影响的测试 helper

任务:

1. 删除 `GemDefinition` 两个 `@Deprecated` public 构造函数。
2. 删除 `PowerStructure` 三个非默认 public 构造函数。
3. 全部调用改用:
   - `new PowerStructure()` + setters/getters。
   - `new GemDefinition.Builder(key)...build()`。
4. 若外部插件可能源码依赖这些构造函数, 这属于本轮可接受破坏; README release note 标明。

测试:

- 更新 `PowerStructureTest`。
- 全仓库搜索 `new GemDefinition(` 和 `new PowerStructure(`。

验收:

```text
rg "new GemDefinition\\(|new PowerStructure\\(" src/main/java src/test/java
mvn "-Dtest=PowerStructureTest,GemManagerTest" test
```

### P7 默认配置、README 与语言同步

风险: R2 用户配置 / 文档。

文件:

- `src/main/resources/gems/gems.yml`
- `src/main/resources/powers/powers.yml`
- `src/main/resources/config.yml`
- `README.md`
- `README_en.md`
- `src/main/resources/lang/zh_CN.yml`
- `src/main/resources/lang/en_US.yml`

任务:

1. 默认示例改成新 power 语法。
2. 增加 `redeem_requirements` 示例:
   - 同类多颗。
   - 异类多颗。
   - 混合配方。
   - `any_of`。
3. 说明 legacy 字段:
   - 仍可粗兼容。
   - 会 warning。
   - 建议手动迁移。
4. 增加升级备份/回滚说明。
5. 如新增 amount 失败提示, 同步中英文语言文件。

验收:

- README 中示例可直接复制到 `gems/*.yml`。
- 默认配置不再展示 `template`, `vault_group`, `vault_groups`, `permission_group`。
- 中英文 README 语义一致。

### P8 全量验证与服务器烟测

风险: R3 发布前验证。

自动化:

```text
mvn "-Dtest=GemDefinitionParserTest,RedeemRequirementsTest,GemManagerTest,PowerStructureTest" test
mvn test
mvn verify
```

Paper 烟测:

- 启动新配置。
- 用旧配置启动, 确认备份生成和 warning 输出。
- `/rg redeem` 普通兑换。
- 长按兑换。
- 祭坛兑换。
- `/rg redeemall`。
- self amount: 3 颗同类兑换。
- 异类 `crown + scepter + orb`。
- 混合 `dragon x3 + scale x2`。
- `any_of` 第一套失败第二套成功。
- event 取消路径不消耗额外 gem。

Folia 烟测:

- 启动不报线程访问错误。
- 兑换、散落、祭坛、长按、权限刷新流程至少各跑一次。

旧服升级演练:

1. 停服。
2. 复制旧 `plugins/RuleGems/` 到临时测试服。
3. 放入新 jar。
4. 启动并确认自动备份目录。
5. 查看 warning, 手动迁移旧配置。
6. 核验核心兑换流程。
7. 失败时恢复备份目录和旧 jar。

---

## 6. 受影响代码清单

| 文件 | 改动 |
|------|------|
| `GemDefinitionParser` | power 语法收敛、组键解析、requirements parser、legacy warning |
| `RedeemRequirements` | 改为 recipe list 模型 |
| `RedeemRecipe` | 新增 recipe 模型 |
| `RedeemIngredient` | 新增 ingredient 模型 |
| `RedeemRequirementResult` | 记录 matched recipe 和额外消耗 UUID |
| `GemManager` | requirements 匹配、实例分配、消耗接入、redeemAll 规则保留 |
| `GemPermissionManager` | 暴露或提供 owner key count 查询, 供 `requires_redeemed amount` 使用 |
| `PowerStructure` | 删除废弃构造函数 |
| `GemDefinition` | 删除废弃构造函数 |
| `BackupHelper` / `ConfigManager` | legacy syntax 备份触发 |
| 默认资源与 README | 新语法和升级说明 |

不改:

- `PowerStructureManager` 引用计数模型。
- `GemAllowanceManager` 限次命令以 gem UUID 分桶的模型。
- `GemStateManager` 数据文件 key。
- `revoke` feature 的撤销语义。
- `appoint` feature 的 power 授予语义。

---

## 7. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| 自引用 amount 重复消费目标 gem | R3 | 匹配结果排除 target UUID, 单测覆盖 |
| 同一 UUID 同时满足多个成分槽 | R3 | 分配器维护 used UUID set |
| 旧配置根节点隐式 power 被误删 | R2 | 一轮粗兼容 + warning + 启动备份 |
| `requires_redeemed amount` 与当前 set 语义冲突 | R2 | 使用 owner key count, fallback 到 set=1 |
| `redeemAll` 绕过或误消费 recipe | R3 | 保持 `allow_redeem_all` 作为唯一通道, 单测覆盖 |
| 删除构造函数影响外部源码编译 | R2 | release note 明确 breaking internal API cleanup |
| 默认配置和 README 不一致 | R1 | P7 同步中英文文档和资源 |

---

## 8. 已定决策

- 保持 gem-centric。
- 不做 power-centric、PowerGrantInstance、RecipeEngine、RedemptionRecord。
- 兼容策略从"完整无痛兼容"调整为"备份 + 粗兼容 + 明确 warning"。
- 新组键为 `permission_groups`。
- 新 `power` 推荐写法只有模板引用和内联 map。
- `template`、根节点隐式 power、旧组键只做过渡读取。
- `redeem_requirements` 以 recipe/ingredient 为核心。
- `any_of` 是多套等价配方的唯一推荐 OR 机制。
- 主手/祭坛目标 gem 计入 `amount`。
- 每个物理 gem UUID 只能满足一个成分槽。
- `redeemAll` 保留 `allow_redeem_all` 语义, 不隐式执行 recipe 消耗。
- 限次命令额度仍按 gem UUID 分桶, 不跨 gem 去重。

---

## 9. 进度追踪

| 阶段 | 状态 |
|------|------|
| P0 文档合并与旧计划清理 | 已完成 |
| P1 升级备份与 legacy 检测 | 已完成 |
| P2 配置语法清理 | 已完成 |
| P3 `redeem_requirements` 模型与 parser | 已完成 |
| P4 兑换匹配器 | 已完成 |
| P5 兑换入口接入 | 已完成 |
| P6 删除废弃构造函数和死 API | 已完成 |
| P7 默认配置、README 与语言同步 | 已完成 |
| P8 全量验证与服务器烟测 | 自动化验证已完成；Paper/Folia 烟测待实服执行 |
