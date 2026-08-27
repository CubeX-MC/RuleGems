# RuleGems：CubeX 接入实现与升级验收

> 此文记录改进前的审查/上一轮接入快照；后续修复与当前验收结果见 [IMPROVE_PLAN](../IMPROVE_PLAN.md)。

日期：2026-08-27。风险：运行时 R3、打包 R4。对应[修复前审查](cubex-integration-audit.md)。

## 交付结论

框架接入与本轮自动化验收完成，未部署到服务器、未提交或推送。
RuleGems 仍是可独立发布的 EMBEDDED 插件；无需额外安装 CubeXLib。
现使用 core、config、i18n、scheduler、database、command、gui、economy 八个共享模块。
没有需要强接的 CubeX 有状态服务，未为凑模块数引入 integrations 或 spatial。

## 修改范围与行为边界

| 范围 | 实现 | 保留的行为 |
| --- | --- | --- |
| 经济 | 共享 VaultTransfers；明确失败才补偿，异常结果提示人工核账；支持 name:/uuid:/bank:，不再枚举离线玩家目录 | 默认禁用、金额参数约束、执行链失败中止、失败返还次数；VaultEconomy.charge 的消费语义不变 |
| 生命周期 | GemManager/FeatureManager 接入 Reloadable/Terminable，资源创建后立即绑定；未加载成功的 store 不保存空状态 | 原有保存、权限清理和任务取消职责；QuickShop 安全门禁仍然生效 |
| 重载 | ReloadChain 命名阶段、失败停止后续阶段；配置、嵌套定义 YAML、语言、委任文件和宝石存储先校验 | 保留原配置键、宝石 UUID、已用次数及 YAML/SQLite 格式；校验失败不提前改变全局效果时长 |
| 语言与默认值 | 全部语言段由 I18nService 读取/渲染，标题与缺失前缀同样回退；合并改用 YamlDefaults | 保留旧占位符适配、服主自定义值和既有备份目录；要求备份时失败就不覆盖 |
| GUI/文本/命令 | 共享 Menu/MenuRegistry/ItemBuilder/CubexText/根补全；GUI 业务类迁到 org.cubexmc.rulegems.gui | 保留页面、按钮布局、权限、PDC 标记；下方背包物品不执行菜单操作；重载/关闭只关闭本插件菜单 |
| 打包 | 共享模块和 Kotlin 都重定位；限定包边界，command 不误包含 commands；新增门禁与字节码分类测试 | EMBEDDED 可单独运行；EXTERNAL/LIB 规则不变；共享代码保持 Java 17 字节码 |

主要实现位于 RuleGems 主类、ConfigManager/GameplayConfig/GemManager/LanguageManager、
features/、src/main/kotlin/org/cubexmc/rulegems/gui/、modules/ 和 buildSrc/。
旧的本地 EconomyProvider、ItemBuilder、ColorUtils 已删除；业务按钮工厂不是共享工具副本。

未添加原命令自动补全继承；玩家名与金额仍按显式 suggestions 配置补全。
未自动开启 OP 提权或 transfer，未替换实服 powers 配置。
冷却仍是内存状态，重载保留、重启清空；次数不会因冷却到期或重载自动补满。

重载不是跨组件事务：校验之前保留当前运行配置，校验之后的外部权限插件、世界操作等异常
通过阶段报告定位；不会宣称能原子回滚任意外部副作用。
转账也没有跨账户原子事务或崩溃日志。

## 自动化验证

最终通过的统一命令（在仓库根 PowerShell 执行）：

```powershell
.\gradlew.bat :RuleGems:build jarGateAll :modules:cubex-core:test :modules:cubex-config:test :modules:cubex-i18n:test :modules:cubex-gui:test :modules:cubex-economy:test :modules:cubex-command:test :modules:cubex-scheduler:test :modules:cubex-database:test :Contract:test :StateCharge:test --no-daemon
.\gradlew.bat -p buildSrc test --no-daemon
```

也执行过 RuleGems clean 后的编译、测试和打包；最终包边界修正后重新执行了上述全套门禁。
开发中出现的编译问题、Detekt 签名变化和新增测试夹具问题均已修复，最终结果如下：

| 测试范围 | 数量 | 失败 / 跳过 |
| --- | ---: | --- |
| RuleGems | 490 | 0 / 0 |
| 八个共享模块 | 183 | 0 / 0 |
| Contract | 172 | 0 / 0 |
| StateCharge | 43 | 0 / 0 |
| buildSrc | 21 | 0 / 0 |
| 合计 | 909 | 0 / 0 |

RuleGems Detekt 和 JaCoCo 报告生成通过。Detekt 基线仅迁移七处已有问题的符号签名，
没有增加豁免项或放宽阈值。仍有现有的 Kotlin/弃用 API、Gradle 9 兼容性提示，不据此扩大支持版本。

新增回归覆盖：虚拟银行字符串路由、可信 UUID、拒绝伪造账户、异常入账不盲目退款、
坏 YAML/备份失败不覆盖、无效世界/存储/委任文件阻止配置发布、启动失败不保存空状态、
幂等关闭保存、自定义语言标题与前缀回退、GUI 上下背包路由、包名前缀碰撞门禁。

全仓 jarGateAll 通过：12 个插件、CubeXLib 和 5 个 cookbook。
Clarity 自有类仍校验 Java 21，共享模块仍校验 Java 17。
另用隔离 URLClassLoader 实际调用了 RuleGems、Contract、CubeXLib jar 内的根补全和文本纯函数，
验证各自 Kotlin/共享类链接成功；该探针不等同于 Bukkit 启动测试。

## 最终部署包

- 文件：`RuleGems/build/libs/RuleGems-1.1.0.jar`（不是 plain.jar）。
- 大小：8,174,503 字节。
- SHA-256：`09B4D6FCC15CBB2B41CFCA755FAF54CB6595D82820E1C6C9E4769CB76D46E53F`。
- 版本元数据仍为 1.1.0；本次没有自行提升发布版本，用以上哈希识别构建。
- 原始 Kotlin、原始共享包、服务端/Vault API 类、重复 class 条目：均为 0。
- `org/cubexmc/commands/CloudCommandManager.class` 保留原路径，没有被误重定位。
- GUI 业务类位于 `org/cubexmc/rulegems/gui/`；公开 Bukkit 事件包保持不变。
- plugin.yml 主类、命令/权限、可选依赖不变；没有 CubeXLib 硬依赖或 Folia 支持声明。

## 升级与回滚

1. 正常停服，备份旧 jar 和完整 RuleGems 目录；包含 data、lang、配置、定义和自定义路径的 SQLite 文件。
2. 只替换部署 jar，正常启动。此次改变类重定位，不能用插件热加载工具替换；无需安装 CubeXLib，也不要删除配置或重新生成宝石。
3. 启动后运行 /rg doctor，核对宝石数量/UUID/归属、权限、委任及剩余次数。新增默认键会沿用备份规则补齐。
4. transfer 仍需明确启用。虚拟命名账户示例为 `transfer:%arg1% name:cubex_bank %arg2%`；
   如果目标实际是玩家 UUID 账户，优先配置已确认的 UUID。先对实际经济插件做小额双向测试并核对双方余额。
5. 若旧实现曾向生成的 UUID 入账，本次不会自动迁移那些余额，必须由管理员通过经济插件核账。
6. 回滚时再次停服，恢复匹配的旧 jar 和备份目录。不要把本次已写入的新运行状态与较旧备份任意拼接。

## 实服覆盖与剩余风险

本次没有启动真实 Minecraft 服务、没有对 CMI/Vault 实际账户转钱，也没有执行服务器部署。
仍需在实服副本或维护窗口检查：安装/重启、旧数据升级、GUI 导航、持有/兑换/委任权限、
配置重载、银行双向收支及失败反馈。Folia 两区域测试未执行，不恢复 Folia 支持声明。

这是自动化和产物验收通过的交付，不是“所有服务端及经济插件组合都已实测”的承诺。
后续实服事项统一记录在根 PLAN.md §5.3。
