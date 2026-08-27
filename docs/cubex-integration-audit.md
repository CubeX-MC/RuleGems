# RuleGems：CubeX 体系接入审查

> **历史审查快照（修复前）**：以下问题与包名均描述审查时状态。
> 后续实现和升级验证见 [框架接入验收](cubex-integration-evidence.md)；
> 本报告中的“尚未支持 name:”及原始 jar 数据不代表当前代码。

日期：2026-08-27。基于 `e022a64c6478bf3f7f1e93bc66e197d002b65bf4` 加当前工作区的参数补全改动。

## 结论与范围

**已接入基础体系，但尚未完全对齐仓库规范与 Contract 参考实现。**

RuleGems 使用统一 Gradle 约定、继承 `CubexPlugin`，实际调用六个共享模块。
它目前是可独立发布的 **EMBEDDED** 插件；不依赖安装 CubeXLib 是正确选择，不能把缺少
`depend: [CubeXLib]` 当成接入缺陷，也不能用“接了几个模块”计算完成百分比。

此次为审查：没有修改运行时代码、依赖、配置或打包模式，没有部署、提交或推送。
新增此报告，以及 `build/cubex-audit/` 内的临时测试探针。之前的补全改动保持原样；
自动继承原命令补全仍未实现。

审查覆盖构建和 jar、生命周期、配置/重载、语言、调度、数据库、命令、GUI、经济及可选连接。
修改风险为 R0；检查的资金、权限、持久化与打包表面属于 R3。没有进行实服或性能基准测试。

## 接入矩阵

| 范围 | 现状 | 判断 |
| --- | --- | --- |
| 构建与独立安装 | `cubex-kotlin-plugin`、EMBEDDED、Java 17；无其他 CubeX 业务插件编译依赖 | 当前门禁通过；共享包重定位问题见下文 |
| `cubex-core` | `CubexPlugin`、关闭动作栈、导航/情报的 `Cooldown` | 部分接入：资源绑定时机、文本和根补全尚未统一 |
| `cubex-config` | `ResourceFiles`、`MigrationRunner`、语言迁移 | 部分接入：没有 `ReloadChain`，默认键合并仍是本地实现 |
| `cubex-i18n` | `formatMessage()` 使用 `I18nService` / MiniMessage | 部分接入：原始文本、列表、标题另有读取与渲染链 |
| `cubex-scheduler` | `SchedulerUtil` 委托 `LegacySchedulerAdapter` / `CubexScheduler` | 已接入；不是独立复制一套调度实现 |
| `cubex-database` | SQLite 使用 `SQLiteDatabase`、`SQLitePragmas` | 已接入；连接均通过 `use` 关闭，不能因未实现 `Terminable` 就推断连接泄漏 |
| `cubex-command` | 动态代理使用 `CommandMaps.resolve/knownCommands/unregister` | 已接入；继续使用 Cloud 符合现有命令路线 |
| `cubex-gui` | 未依赖；本地 `ChestMenu`、`GUIManager`、`ItemBuilder` | 存在可复用能力未整合，且有同名类冲突 |
| `cubex-economy` | 未依赖；本地 `EconomyProvider` | 尚未迁移，`PLAN.md` 已列为待办；有实际账户解析风险 |
| `cubex-integrations` | 无 CubeX 服务桥；Vault/权限/QuickShop 为各自适配 | 当前没有必须连接的 CubeX 服务，不应仅为凑模块数添加 |
| `cubex-spatial` | 未依赖 | 本次未发现必须引入空间索引的接入需求 |

依赖依据：`build.gradle.kts:13`。CubeXLib 当前主类仅提供模块运行时，尚未注册计划中的共享
effect / quest / 事务经济服务。RuleGems 的宝石、权限和委任状态是玩法状态，不应搬进 CubeXLib。

## 需要优先处理的问题

### 1. 经济账户解析应在正式启用银行转账前处理

来源：`src/main/java/org/cubexmc/economy/EconomyProvider.kt:31`、`:61`、`:120`。

- 名字转账对付款方和收款方各调用一次 `Bukkit.getOfflinePlayers()`；即使已经找到在线玩家也不提前结束。
  这是每次转账两次全量枚举，不是 Tab 补全产生的成本；具体耗时未测量。
- 收款方取 `resolveAccounts(...).firstOrNull()`。找不到已知玩家时，仍先加入
  `Bukkit.getOfflinePlayer(name)` 的结果，最后才加入 Vault 命名账户。因此虚拟银行收款
  不会自然落到 `depositPlayer(String, amount)`。
- 临时探针已确认：没有在线/已知银行玩家、Bukkit 返回生成的离线 UUID 时，转账仍可返回
  `SUCCESS`，实际调用的是 `depositPlayer(generatedOfflinePlayer, amount)`，没有调用
  `depositPlayer("cubex_bank", amount)`。若经济插件按 UUID 区分账户，就有入错账户或入账失败风险。
  这证明了代码的路由行为，**不等于已经验证 CMI 实服入错账**。

共享模块已有明确的 UUID、玩家名、原始命名账户解析，但 `VaultEconomy.charge()` 的语义是
扣款成功后不回滚，不能直接替换这里的 `transfer()`。应按根 `PLAN.md` §7.4 的既定方向，
先补齐共享模块的独立转账及补偿接口，再迁移调用方，保留现有金额约束、失败中止与补偿结果。

**不要直接把共享模块的 `name:cubex_bank` 语法套到当前 RuleGems：它还没有这项支持。**
内置转账的默认禁用状态在本次审查中没有改变。

### 2. 生命周期资源注册得太晚，启动失败路径没有完整兜底

来源：`src/main/java/org/cubexmc/RuleGems.kt:97`、`:136`、`:198`、`:229`、`:232`。

宝石加载、展示初始化、监听器、功能初始化等发生后，才在启动末尾执行 `bindShutdownActions()`。
例如 QuickShop 安全钩子检查失败会在此之前抛异常。`CubexPlugin` 会关闭已经绑定的资源，
但此时宝石同步保存、展示清理、功能关闭等动作尚未入栈，空的 `disablePlugin()` 也不补救。

这不表示所有任务和监听器都会泄漏：共享调度器有自身绑定，服务器也管理插件任务/监听器。
缺口是 RuleGems 自己必须完成的领域清理不能覆盖所有部分启动状态。

应让有状态组件实现适用的 `Reloadable` / `Terminable`，创建成功就绑定，明确初始化成功标记
与关闭顺序。不能简单在最前面无条件保存一个尚未成功加载的空宝石状态。
当前依据为代码路径审查；未在实服注入启动中断。

### 3. 重载未使用分阶段报告，而且部分配置错误无法传递给调用者

来源：`src/main/java/org/cubexmc/RuleGems.kt:268`、`:314`；
`src/main/java/org/cubexmc/manager/ConfigManager.kt:57`。

`loadPlugin()` / `reloadFromCommand()` 手工串联步骤，只提供 SUCCESS / FAILED / BUSY。
`ConfigManager.loadConfigs()` 遇到不存在的世界或无效范围时只记录日志并返回，不返回失败也不抛异常；
后续加载仍可能继续，若其余步骤成功，命令可能报告重载成功，同时留下部分新配置和旧运行配置。
数据库读取失败虽已在 `GemManager` 层保留旧状态，配置、语言、功能等并未作为整体暂存后发布。

应对齐 Contract 的命名 `ReloadChain`、失败策略、阶段依赖和 `ReloadReport`。
同时让加载器显式报告失败；仅把现有调用包进 `ReloadChain` 不能发现被吞掉的错误，
`ReloadChain` 本身也不提供整体状态回滚。

## 其余接入缺口

### 4. I18n 仍有两套实现，已出现标题回退差异

来源：`src/main/java/org/cubexmc/manager/LanguageManager.kt:55`、`:128`、`:139`、`:260`、`:358`。

`getMessage()` / `getMessageList()` 维护自己的 YAML 缓存、语言回退和 MiniMessage 渲染；
`formatMessage()` 才进入共享服务。`showTitle()` 更是直接读取当前语言的列表，不走回退链。

临时探针已确认：自定义语言缺少标题、英语中存在标题时，`getMessageList()` 可以找到回退文本，
但 `showTitle()` 不发送标题。应统一到共享服务的 `raw/rawList/message` 等入口，并保留现有
占位符兼容行为。Contract 的 `config/LanguageManager.kt` 已示范所有语言段使用同一服务。

### 5. 共享工具仍有本地副本；GUI 不能只加一行依赖

来源：`src/main/java/org/cubexmc/utils/ColorUtils.kt:6`；
`src/main/java/org/cubexmc/gui/ItemBuilder.kt:16`；
`src/main/java/org/cubexmc/update/ConfigUpdater.kt:25`；
`src/main/java/org/cubexmc/update/LanguageUpdater.kt:16`。

- `ColorUtils` 重复了 `CubexText` 的传统颜色与十六进制转换；它额外的旧 API 兜底需要先评估并在共享层补齐。
- `ItemBuilder` 与共享模块类的完整名字都是 `org.cubexmc.gui.ItemBuilder`。直接添加
  `cubex-gui` 会引入重复类；应先迁移通用构建逻辑，把 RuleGems 专属按钮留在自己的业务包。
  现有发光兼容路径与共享实现并不完全相同，不能无测试替换。
- 默认配置与语言合并重复了 `YamlDefaults` 能力。迁移要保持备份、用户配置和旧数据语义，
  不能顺带把现有原子写入的运行数据存储改成普通 YAML 保存。

### 6. 根补全与 agent 文档尚未收口

来源：`src/main/java/org/cubexmc/commands/CloudCommandManager.kt:425`；`AGENTS.md:18`、`:65`。

Bukkit fallback 的根候选仍自行判断和过滤，没有使用仓库规定的
`org.cubexmc.core.CubexCommandSuggestions.root()`。当前已有空数组保护，未发现这里的越界故障；
这是复用规范缺口，不涉及恢复此前决定暂缓的原命令补全继承。

插件 `AGENTS.md` 仍指向不存在的 `pom.xml` 并给出 Maven 命令，pipeline / verification matrix
也有 Maven 残留；项目 profile 已改成 Gradle。应在一次文档同步中统一，避免后续 agent 跑错门禁。

## 与整个仓库有关的打包问题

此项不能算成 RuleGems 单独遗漏的模块，也不应在本次检查中悄悄修改 `buildSrc`。

1. 根规范写的是内嵌共享模块 shade + relocate，但当前约定插件对 EMBEDDED 自动重定位的是 Kotlin。
   RuleGems jar 内六个共享模块仍是原始 `org/cubexmc/{core,config,i18n,scheduler,database,command}/` 包。
   已直接检查 jar；Contract 的已有本地产物也保留共享模块原包名。当前 `jarGate` 接受这一布局。
   因此“门禁通过”不能证明已经满足共享包命名空间隔离约定，需要全仓协调构建规则和门禁。
   本次未验证同服类加载冲突，不据此宣称已发生运行时故障。
2. RuleGems 自有 GUI 位于 `org.cubexmc.gui`、自有经济类位于 `org.cubexmc.economy`。
   `CubexModules.archivePatterns` 的 EXTERNAL 排除规则会匹配并移除这些业务类。
   **当前不能只切一个 EXTERNAL 开关就完成外置化。** 当前 EMBEDDED 无此排除问题；
   也没有外置化 RuleGems 的需求。

## 验证证据

常规门禁：

```powershell
.\gradlew.bat :RuleGems:detekt :RuleGems:test :RuleGems:shadowJar :RuleGems:jarGate :modules:cubex-core:test :modules:cubex-config:test :modules:cubex-i18n:test :modules:cubex-scheduler:test :modules:cubex-database:test :modules:cubex-command:test --no-daemon
```

结果：BUILD SUCCESSFUL。Detekt / RuleGems 测试 / shadowJar 在第一次门禁中为 UP-TO-DATE，
随后强制重跑了所有上述测试：

```powershell
.\gradlew.bat --init-script RuleGems/build/cubex-audit/audit.init.gradle :RuleGems:cubexAuditTest :RuleGems:test --rerun :modules:cubex-core:test --rerun :modules:cubex-config:test --rerun :modules:cubex-i18n:test --rerun :modules:cubex-scheduler:test --rerun :modules:cubex-database:test --rerun :modules:cubex-command:test --rerun --no-daemon
```

| 测试集合 | 数量 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| RuleGems | 482 | 0 / 0 / 0 |
| core / config / i18n / scheduler / database / command | 41 / 15 / 10 / 6 / 8 / 12（合计 92） | 全部 0 |
| 独立临时审查探针 | 2 | 0 / 0 / 0 |

两项探针断言的是**当前有问题的行为确实出现**，通过不代表问题已修复。
探针位于 `build/cubex-audit/`，报告在 `build/test-results/cubexAuditTest/`，不进入插件 jar，
`clean` 后会删除；正式修复时应把相应场景加入现有回归测试并断言期望行为。

jarGate：`mode=EMBEDDED unrelocatedKotlin=0 relocatedKotlin=1029 reflectImpl=0`，
`cubexModuleEntries=138 ownClasses=351 pluginBytecodeMajors=[61] sharedBytecodeMajor=61`。
另查 jar：无重复 ZIP 条目，未打入 Bukkit / Paper / Vault API 类。

核对产物：`build/libs/RuleGems-1.1.0.jar`，8,087,036 字节，SHA-256：
`B189D915BD15E968EBC165F1093350F9052F9ED98EEEB146BFCBFD1E5EC30A6F`。
本次未改变产物内容。保留原有 Gradle 9 弃用、Mockito/JVM agent 等警告；未修改 Detekt baseline。

## 验证边界与优先级建议

先处理经济账户路由、启动失败清理和重载失败传播，再统一 I18n 与 GUI/文本工具。
共享包重定位应单列全仓构建议题；不以更改 RuleGems 打包模式作为修复。
这里是审查建议，不另建计划文件；实施待办仍以根 `PLAN.md` 为唯一入口。

尚需实服验证：真实 CMI 银行账户和补偿失败、插件部分启动后失败、重载无效配置/存储故障、
与 CubeXLib 及其他内嵌插件同服的类加载、GUI 会话清理和自定义语言标题。
没有测量大服转账耗时；没有新增 Folia 支持声明。
