# RuleGems 框架接入与代码质量复审

> 此文记录改进前的审查/上一轮接入快照；后续修复与当前验收结果见 [IMPROVE_PLAN](../IMPROVE_PLAN.md)。

日期：2026-08-27。审查对象：当前工作区（HEAD 为 `e022a64`，包含尚未提交的框架接入及命令参数改动）。
本轮为审查，没有修改生产代码、默认配置或正式测试；仅新增本报告，隔离探针与诊断文件位于忽略的 `build/` 目录。

## 结论

**RuleGems 已实质接入 CubexPlugin 与共享模块，但目前还不是可以供全仓照抄的模范。**
工程化基础良好，整体可评为“中等偏上、仍有可靠性缺口的业务插件”；不能评为示范级或高可靠资金组件。
这不是行业排名或精确分数，而是依据本仓库约定、当前源码、测试和故障复现作出的评价。
仓库指定的框架参考实现仍是 Contract。

本次发现 4 类应修问题，5 个独立故障场景已复现。现有测试全部通过，并不覆盖这些故障。

| 维度 | 评价 | 依据 |
|---|---|---|
| 框架接入与打包 | 良好 | 主类继承 CubexPlugin；8 个共享模块实际使用；内嵌隔离与 jarGate 通过 |
| 生命周期与重载 | 已成体系，但不完整 | 资源托管、命名重载阶段、宝石数据预校验已落地；部分功能仍吞掉加载/保存失败 |
| 资金与权限安全 | 尚未收口 | 不确定转账被上层当作可退次数的失败；损坏的门控配置可能放行 |
| 可维护性 | 中等 | 部分服务已拆分，但大类、公开可变状态、跨管理器协作和旧兼容分支仍多 |
| 自动化验证 | 有良好基础，关键链路有缺口 | 490 项测试通过；业务行覆盖率 43.6%、分支 32.9%；启动/重载主类未被执行覆盖 |
| 性能与运行平台证据 | 有合理设计，缺实服量化 | 补全有限额且无离线玩家全量扫描；未运行实服负载测试或安装升级矩阵 |

## 已确认的问题

### P1：转账已生效或结果不确定时，仍会退回额度且不进入冷却

- 位置：[CustomCommandExecutor.kt](../src/main/java/org/cubexmc/manager/CustomCommandExecutor.kt#L153)、[CommandAllowanceListener.kt](../src/main/java/org/cubexmc/listeners/CommandAllowanceListener.kt#L253)。
- `VaultTransfers` 能区分 `REVIEW_REQUIRED` / `ROLLBACK_FAILED`，但执行器将它们压缩为 `false`；监听器对所有 `false` 都退次数，且不会设置冷却。
- 即使转账明确成功，执行链中后面的 `player:` 命令返回失败，也会将整个结果设为失败，产生同样的退额度行为。前面的资金移动并没有回滚。
- 复现一：模拟经济服务在入账后抛出异常，连续执行两次，每次转账 10。银行从 100 变为 80，收款人从 0 变为 20，使用次数仍为 5，冷却未开始。
- 复现二：转账正常成功，随后 `player:missing-command` 失败，结果与上例相同。
- 探针使用真实 `VaultTransfers`、`CustomCommandExecutor`、`CommandAllowanceListener` 和 `GemAllowanceManager`；只模拟服务器、配置定义和经济提供方。
- 影响条件：显式启用 `economy.transfer_directives_enabled`，并遇到上述异常或命令链组合。默认关闭降低了默认安装的风险，但不能保护启用后的银行收支。
- 建议：用明确的执行结果贯穿调用链，区分“未生效/已补偿”“已提交”“部分提交/待核账”；后两者不可自动退次数并允许无冷却重试。待核账状态需要可追踪的操作记录与后续处理规则，不能只发一条消息。

### P1：Rule 门控 YAML 损坏后可能变为放行，重载仍报告成功

- 位置：[RuleGateFeature.kt](../src/main/java/org/cubexmc/features/rule/RuleGateFeature.kt#L33)、[ConfigManager.kt](../src/main/java/org/cubexmc/manager/ConfigManager.kt#L218)。
- `features/rule.yml` 不在严格预校验范围内；功能自身仍调用 `YamlConfiguration.loadConfiguration`。解析失败被记录到日志，但不向重载链抛出。
- 返回空配置后，`enabled` 默认变为 `false`，而 `canUsePower` 在功能关闭时直接返回 `true`。
- 复现：先启用权限门控，使无权限玩家被拒绝；将文件改为非法 YAML `enabled: [broken` 后重载。`ReloadReport.ok()` 为 `true`，同一玩家通过门控。
- 影响：放开的是 RuleGems 的权力门控，并非授予玩家服务器全部权限；仍然破坏了原来的访问限制。
- 建议：严格读取、校验候选配置，全部成功后再替换运行状态；失败保留旧门控，并让重载报告明确失败。

### P2：撤销功能的数据读取失败会清空冷却，随后覆盖原文件

- 位置：[RevokeFeature.kt](../src/main/java/org/cubexmc/features/revoke/RevokeFeature.kt#L446)。
- `loadData()` 先清空 `cooldownUntil`，随后宽松读取 `data/revokes.yml`；该文件同样未被 ConfigManager 预校验。
- 复现：先加载一条有效冷却，再将数据文件改成非法 YAML，重载报告仍成功，内存冷却变为空；`shutdown()` 随后将空数据写回原路径。
- 同类问题还存在于 `features/revoke.yml` 的宽松读取：配置错误可能被当成正常禁用。
- 建议：将功能数据作为独立 store，采用严格读取、暂存校验、成功后发布，以及读取失败时禁止空状态回写的规则。

### P2：委任数据保存失败仍报告操作成功，重载可恢复已撤销任命

- 位置：[AppointFeature.kt](../src/main/java/org/cubexmc/features/appoint/AppointFeature.kt#L233)、[RuleGems.kt](../src/main/java/org/cubexmc/RuleGems.kt#L293)。
- `saveData()` 捕获 `IOException` 后仅记录警告；`dismiss()` 仍返回 `true`。重载前只强制保存宝石数据，没有对委任 store 做成功门禁。
- 复现：已有任命，模拟 YAML 保存发生 I/O 异常。撤销返回成功、内存任命被移除；再次 `reload()` 读取旧文件，该任命重新出现。
- 建议：保存结果/异常必须传回业务层；有未持久化数据时，重载应先可靠刷新或保留旧内存，不能用旧磁盘内容覆盖它。
- 委任/撤销数据目前也仍直接 `data.save(file)`，未达到宝石主存储的临时文件、原子替换与恢复保护水平。

## 框架接入：已经做对的部分

- 生命周期：`RuleGems : CubexPlugin`；GemManager、FeatureManager、GUIManager 已托管，关闭按资源栈执行；GemManager 未成功加载时不会在关闭阶段保存空状态。
- 配置：使用 ResourceFiles、MigrationRunner、YamlDefaults、ReloadChain/ReloadReport；宝石 YAML 和存储快照先验证，保留 gem-centric 存储形状。
- 语言与界面：各语言段通过 I18nService；使用 CubexText、共享 Menu/MenuRegistry/ItemBuilder，保留业务菜单和旧配置兼容层。
- 调度与数据库：SchedulerUtil 委托共享 LegacySchedulerAdapter；SQLite 连接与 PRAGMA 使用共享数据库模块。
- 命令：动态代理通过共享 CommandMaps 操作，根补全使用 CubexCommandSuggestions；玩家名和金额候选依照来源额度过滤。
- 打包：当前 EMBEDDED 模式正确；CubeXLib 不是硬依赖，共享类和 Kotlin 都进入私有重定位目录。无需为了“接入完整”改为 EXTERNAL，也不应为了凑齐模块而添加空间索引或无用途的服务桥。
- 可选集成：QuickShop 使用提供方 ClassLoader 和显式健康状态；不交易宝石的保护无法建立时阻止启用，有明确失败边界。

这些足以说明已经超过“仅继承一个基类”的形式接入。剩余问题主要在业务与框架之间的失败语义，而不是缺少 import 或依赖声明。

## 代码质量的量化依据

统计范围为 RuleGems 的生产 `.kt` / `.java`，排除 vendored `Metrics.java`；物理行数包含注释和空行，不是复杂度分数。

- 116 个生产源码文件，22,282 行。
- 7 个文件达到 897–1,335 行；GemManager 1,335、GemPlacementManager 1,031、GemPermissionManager 996、GemDefinitionParser 943、AppointFeature 941、GemStateManager 933、GemAllowanceManager 897。
- 现有 Detekt 基线有 654 条；本次独立重扫仍命中 621 条，33 条已失效。现存结果包括 47 条圈复杂度、21 条长方法、7 条大类、40 条嵌套深度问题。
- 这些告警不等于 621 个 bug；例如早返回、数字常量、长行需要结合语义判断。但它们足以说明“Detekt 通过”并不意味着代码已整洁、复杂度已收口。此次没有放宽或改写正式基线。
- JaCoCo 按包汇总并排除 `org/cubexmc/metrics`：行覆盖 5,470 / 12,542 = 43.6%；分支覆盖 2,967 / 9,022 = 32.9%。Kotlin 编译插桩会影响计数，不能把百分比单独当质量分数。
- `RuleGems` 主类行覆盖为 0%，`HistoryLogger` 为 0%；GUIManager 28.8%、AppointFeature 49.8%、ConfigManager 51.0%。尤其缺真实生命周期编排和跨组件故障的自动化验证。

结构性改进空间：

- GemPermissionManager 和 GemAllowanceManager 暴露多个可变 Map，RevokeFeature 直接跨管理器修改映射。权限、次数、持有人与持久化的不变量依赖调用方手工维护。
- `lateinit`、针对未初始化/NPE 的兼容兜底与重复空检查并存，说明初始化依赖仍不够清晰。不能为了“更 Kotlin”一次性删掉，应先明确依赖契约并补测试。
- LuckPerms 桥仍持有初始化时解析的 provider/反射对象，权限提供方不随 RuleGems reload 重新发现；它没有完全采用 Contract 的服务失效检测模式。不能据此宣称支持第三方插件热替换。
- BackupHelper 使用秒级文件名和覆盖复制，批量备份对个别复制错误只记录警告；还不适合作为可验证、不可覆盖的升级备份范例。
- 历史记录查询会遍历全部历史文件并将每个文件读入列表；虽已在异步路径执行，长期运行后的内存与扫描成本仍随日志增长。附近检测约为玩家数 × 已放置宝石数；没有实服压测，不能断言大规模服性能优异。

优先修资金/权限及数据失败路径，再补跨组件回归测试；之后按职责逐步拆分大类和封装可变状态。没有必要为此重写整个插件。

## 验证记录与边界

本轮执行：

```powershell
.\gradlew.bat :RuleGems:build :RuleGems:jarGate --no-daemon
.\gradlew.bat -I RuleGems/build/quality-audit.init.gradle :RuleGems:detektBaseline :RuleGems:auditClasspath :RuleGems:test --no-daemon
java '@RuleGems/build/quality-audit-java.args'
```

- 第一条通过；编译/测试等为 up-to-date，jarGate 实际执行。第二条通过，审计 init 脚本强制重跑 RuleGems:test，94 个测试类、490 个测试，失败/错误/跳过均为 0，并重生成 JaCoCo。
- `detektBaseline` 的本次输出重定向到 `build/reports/detekt/audit-current-baseline.xml`，不改正式 `detekt-baseline.xml`。
- 独立 Java 探针复现上述 5 个故障场景；探针断言的是“现有问题仍存在”，退出码 0 不代表问题已经修复。这 5 个场景不计入正式 490 项测试。
- 探针和数据只位于 `RuleGems/build/`，清理构建目录会删除；未连接真实玩家、银行或服务器。测试使用模拟 Bukkit/经济提供方，不能替代实服验证。
- jarGate：EMBEDDED；未重定位 Kotlin 0、已重定位 Kotlin 1,029、原共享模块条目 0、已重定位共享模块条目 159；插件及共享类 Java 17 字节码。
- JAR SHA-256：`09B4D6FCC15CBB2B41CFCA755FAF54CB6595D82820E1C6C9E4769CB76D46E53F`，与上一轮产物一致。
- 未做：真实 Paper/Spigot 安装启动、缺失可选插件运行、旧服数据完整升级演练、实际经济提供方核账、负载测试、Folia 多区域验证。此轮不修改平台支持声明，也不部署或发布 JAR。

在修复上述问题并完成相应回归验证之前，建议继续让内置 transfer 保持关闭，不将当前版本作为涉及银行资金的示范模板。
